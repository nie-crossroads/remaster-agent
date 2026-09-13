package com.remasteragent.core.progress;

import com.remasteragent.core.codec.JsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link ProgressPublisher} 的 Redis Pub/Sub 实现 —— 跑在 <b>Worker 进程</b>里。
 *
 * <h2>为什么进度事件必须过这个通道</h2>
 * <p>SSE 的连接对象活在 API 进程，而节点状态变化发生在 Worker 进程。两者之间没有共享内存，
 * 所以事件必须先经过一个跨进程通道。少了这一层，前端会一直停在「已连接、无消息」——
 * 这是分进程架构里最典型的「看起来能用但什么都不显示」。
 *
 * <h2>为什么用 Pub/Sub 而不是 Stream</h2>
 * <p>诚实的理由：进度事件<b>允许丢</b>。它只是让页面动起来的加速器，
 * 事实来源始终是数据库里的任务状态。对这个场景，Pub/Sub 的「发完即忘、不落盘」正好 ——
 * 换成 Stream 反而要处理消费组和 PEL 清理，为一个可丢的数据付持久化的成本不划算。
 *
 * <p>但「允许丢」要配合一个前提才算完整：<b>订阅方连接时必须先发一份数据库快照</b>。
 * 见 {@code SseEventHub}。只有快照 + 增量这套组合，才能让「连接建立前发生的事件丢失」
 * 不产生任何可观察的影响。
 */
@Component
public class RedisProgressPublisher implements ProgressPublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisProgressPublisher.class);

    private final StringRedisTemplate redis;
    private final JsonCodec json;

    public RedisProgressPublisher(StringRedisTemplate redis, JsonCodec json) {
        this.redis = redis;
        this.json = json;
    }

    @Override
    public void publish(ProgressEvent event) {
        // 用 core 自带的 JsonCodec，而不是注入 Spring Boot 的 ObjectMapper：
        // 本类主要跑在 Worker 进程里，而 Worker 刻意不带 web starter，
        // 那份「已注册 JavaTimeModule」的 mapper 在 Worker 里根本不存在。
        // ProgressEvent 带 Instant，用裸 mapper 会在此处抛 InvalidDefinitionException，
        // 再被下面的兜底 catch 吞掉 → 任务全绿但前端一动不动。这是实测踩到的。
        String payload = json.write(event);
        if (payload == null) {
            log.warn("进度事件序列化失败，已跳过该条: type={} taskId={}", event.type(), event.taskId());
            return;
        }
        try {
            redis.convertAndSend(ProgressChannels.PROGRESS_EVENTS, payload);
        } catch (Exception e) {
            // 进度推送失败绝不能影响任务执行 —— 它只影响页面实时性，不影响正确性。
            log.warn("发布进度事件失败: type={} taskId={}", event.type(), event.taskId(), e);
        }
    }
}
