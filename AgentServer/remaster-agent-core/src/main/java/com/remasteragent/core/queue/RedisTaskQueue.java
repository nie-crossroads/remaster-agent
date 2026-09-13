package com.remasteragent.core.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link TaskQueue} 的 Redis Stream 实现。
 *
 * <p>用到的是 Redis Stream 的消费组语义：{@code XADD} 投递、{@code XREADGROUP} 取、
 * {@code XACK} 确认、{@code XPENDING} + {@code XCLAIM} 接管失败消费者的遗留消息。
 * 消息体只有一个 {@code taskId} 字段 —— 任务的所有状态都在 PostgreSQL 里，
 * 队列里放 ID 而不是任务内容，是为了避免「同一份真相存在两个地方然后不一致」。
 */
@Component
public class RedisTaskQueue implements TaskQueue {

    private static final Logger log = LoggerFactory.getLogger(RedisTaskQueue.class);

    private static final String FIELD_TASK_ID = "taskId";

    /**
     * Stream 保留条数。用 {@code XTRIM MAXLEN ~ N} 近似裁剪，避免长期运行后无界增长。
     *
     * <p>取 10 万是因为本项目是「人工触发的迁移任务」，正常量级是几十到几百条，
     * 这个上限在现实中不可能触及 —— 但一旦有人写脚本批量灌任务，它能兜住内存。
     * 真正的取舍在于：裁剪会删掉仍未确认的消息，所以上限必须远大于任何合理的积压量。
     */
    private static final long STREAM_MAX_LENGTH = 100_000L;

    /** XPENDING 单次扫描的上限，防止 PEL 异常膨胀时把内存吃光。 */
    private static final long PENDING_SCAN_LIMIT = 500L;

    private final StringRedisTemplate redis;
    private final QueueProperties properties;

    public RedisTaskQueue(StringRedisTemplate redis, QueueProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public void initialize() {
        byte[] rawKey = properties.streamKey().getBytes(StandardCharsets.UTF_8);
        try {
            // 显式走 xGroupCreate(..., mkStream=true)：这样即使还没有任何任务被投递过、
            // Stream 键都不存在，也能把组建出来。少了这一步，「先起 Worker 后投任务」
            // 这个再正常不过的顺序会直接报「key 不存在」。
            redis.execute((RedisCallback<String>) connection ->
                    connection.streamCommands().xGroupCreate(
                            rawKey, properties.consumerGroup(), ReadOffset.from("0"), true));
            log.info("已创建消费组 {} @ {}（从最早的消息开始消费）",
                    properties.consumerGroup(), properties.streamKey());
        } catch (Exception e) {
            if (isBusyGroup(e)) {
                log.debug("消费组 {} 已存在，沿用", properties.consumerGroup());
                return;
            }
            throw new IllegalStateException(
                    "初始化任务队列失败（stream=" + properties.streamKey() + "）", e);
        }
    }

    @Override
    public void enqueue(long taskId) {
        StreamOperations<String, String, String> ops = redis.opsForStream();
        RecordId id = ops.add(MapRecord.create(properties.streamKey(), Map.of(FIELD_TASK_ID, String.valueOf(taskId))));
        ops.trim(properties.streamKey(), STREAM_MAX_LENGTH, true);
        log.info("任务 #{} 已投递到队列，消息 id={}", taskId, id == null ? "?" : id.getValue());
    }

    @Override
    public List<QueueMessage> poll() {
        StreamOperations<String, String, String> ops = redis.opsForStream();
        List<MapRecord<String, String, String>> records = ops.read(
                Consumer.from(properties.consumerGroup(), properties.consumerName()),
                StreamReadOptions.empty()
                        .count(properties.batchSize())
                        .block(Duration.ofMillis(properties.blockMillis())),
                StreamOffset.create(properties.streamKey(), ReadOffset.lastConsumed()));

        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return decodeAll(records);
    }

    @Override
    public void ack(String handle) {
        Long acked = redis.opsForStream().acknowledge(properties.streamKey(), properties.consumerGroup(), handle);
        if (acked == null || acked == 0L) {
            log.warn("确认消息 {} 未生效（可能已被接管或裁剪）", handle);
        }
    }

    @Override
    public List<QueueMessage> reclaimAbandoned() {
        StreamOperations<String, String, String> ops = redis.opsForStream();

        PendingMessages pending = ops.pending(properties.streamKey(), properties.consumerGroup(),
                Range.unbounded(), PENDING_SCAN_LIMIT);
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }

        Duration threshold = Duration.ofSeconds(properties.reclaimMinIdleSeconds());
        List<RecordId> abandoned = new ArrayList<>();
        for (PendingMessage message : pending) {
            Duration idle = message.getElapsedTimeSinceLastDelivery();
            if (idle != null && idle.compareTo(threshold) >= 0) {
                abandoned.add(message.getId());
            }
        }
        if (abandoned.isEmpty()) {
            return List.of();
        }

        List<MapRecord<String, String, String>> claimed = ops.claim(
                properties.streamKey(), properties.consumerGroup(), properties.consumerName(),
                threshold, abandoned.toArray(RecordId[]::new));
        if (claimed == null || claimed.isEmpty()) {
            return List.of();
        }
        log.warn("接管了 {} 条疑似『上一个 Worker 被杀』遗留的未确认消息（空闲超过 {} 秒）",
                claimed.size(), properties.reclaimMinIdleSeconds());
        return decodeAll(claimed);
    }

    /**
     * 把 Redis 记录解码成队列消息。
     *
     * <p>解不出来的消息会被<b>立刻确认掉</b>并记错误日志：一条格式不对的消息如果留在队列里，
     * 每次读取都会再次失败，变成一个堵住消费循环的毒丸。宁可丢一条脏消息并大声报错，
     * 也不要让整个队列停摆 —— 而任务真相在数据库里，脏消息本来也不携带任何状态。
     */
    private List<QueueMessage> decodeAll(List<MapRecord<String, String, String>> records) {
        List<QueueMessage> messages = new ArrayList<>(records.size());
        for (MapRecord<String, String, String> record : records) {
            String handle = record.getId().getValue();
            String rawTaskId = record.getValue() == null ? null : record.getValue().get(FIELD_TASK_ID);
            try {
                messages.add(new QueueMessage(handle, Long.parseLong(rawTaskId)));
            } catch (RuntimeException e) {
                log.error("队列消息 {} 的 {} 字段无法解析为任务 id（值={}），已丢弃",
                        handle, FIELD_TASK_ID, rawTaskId, e);
                ack(handle);
            }
        }
        return messages;
    }

    /**
     * Redis 在组已存在时回 {@code BUSYGROUP} 错误 —— 那是正常的幂等情形，不是故障。
     *
     * <p>这里比对的是 Redis 协议层的错误码字面量。用字面量而不是某个常量，是因为
     * 它不是 Java 侧的契约而是 Redis 服务端的固定错误码，跨版本稳定；
     * Spring Data Redis 并没有为它提供公开常量。
     */
    private static final String BUSY_GROUP_ERROR = "BUSYGROUP";

    /** Redis 在组已存在时回 {@code BUSYGROUP} 错误 —— 那是正常的幂等情形，不是故障。 */
    private static boolean isBusyGroup(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains(BUSY_GROUP_ERROR)) {
                return true;
            }
        }
        return false;
    }
}
