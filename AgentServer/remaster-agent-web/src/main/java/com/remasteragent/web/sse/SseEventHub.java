package com.remasteragent.web.sse;

import com.remasteragent.core.codec.JsonCodec;
import com.remasteragent.core.progress.ProgressEvent;
import com.remasteragent.core.progress.ProgressEventSubscriber;
import com.remasteragent.web.api.TaskQueryService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE 事件中枢：把 Worker 进程发来的进度事件，分发给「正在看这个任务」的浏览器连接。
 *
 * <h2>快照 + 增量，而不是只有增量</h2>
 * <p>Pub/Sub 是「发完即忘」的：连接建立之前发生的事件就永久丢了。
 * 如果只推增量，会出现两种很难查的现象 —— 页面刚打开时一片空白（错过了早期事件），
 * 或者断线重连后状态永久停在旧值。所以每次连接<b>先推一份数据库快照</b>，
 * 再推后续增量。有了快照，丢事件就只影响「实时性」而非「正确性」。
 *
 * <h2>并发正确性</h2>
 * <p>{@code SseEmitter} 不是线程安全的，而同一个 emitter 可能同时被两条线写：
 * 请求线程发快照、Redis 订阅线程发增量。所以对单个 emitter 的所有 {@code send}
 * 都串行化在同一把锁上。顺带解决了一个更隐蔽的问题：把「注册进列表」和「发第一份快照」
 * 放进同一把锁，保证了快照一定先于任何增量发出，且注册瞬间到达的事件不会丢。
 *
 * <h2>心跳</h2>
 * <p>一次沙箱构建可能几十秒没有任何节点状态变化，中间的连接会被反向代理当成空闲而掐断。
 * 定时发一条 SSE 注释行（{@code : keep-alive}）把连接保活 —— 注释行不会被
 * {@code EventSource} 当成事件，对前端完全透明。
 */
@Component
public class SseEventHub implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SseEventHub.class);

    /** 事件名：一次连接只发一次，内容是任务的完整当前状态。 */
    public static final String EVENT_SNAPSHOT = "snapshot";

    /** 事件名：增量进度事件，对应 {@link ProgressEvent}。 */
    public static final String EVENT_PROGRESS = "progress";

    private final ProgressEventSubscriber subscriber;
    private final TaskQueryService queryService;
    private final JsonCodec json;

    /** taskId → 正在监听该任务的连接。用 CopyOnWriteArrayList：读多写少，且遍历时不能抛并发修改异常。 */
    private final Map<Long, List<SseEmitter>> emittersByTask = new ConcurrentHashMap<>();

    private AutoCloseable registration;

    public SseEventHub(ProgressEventSubscriber subscriber,
                       TaskQueryService queryService,
                       JsonCodec json) {
        this.subscriber = subscriber;
        this.queryService = queryService;
        this.json = json;
    }

    @PostConstruct
    void subscribeToProgressEvents() {
        this.registration = subscriber.subscribe(this::onProgressEvent);
    }

    /**
     * 建立一条 SSE 连接。
     *
     * <p>超时设为 0（不超时）：一次迁移可能跑几分钟，中途被服务端主动断开会让用户
     * 看不到结果。由客户端与心跳共同保证连接的有效性。
     */
    public SseEmitter open(long taskId) {
        SseEmitter emitter = new SseEmitter(0L);
        List<SseEmitter> perTask = emittersByTask.computeIfAbsent(taskId, key -> new CopyOnWriteArrayList<>());

        synchronized (emitter) {
            perTask.add(emitter);
            try {
                emitter.send(SseEmitter.event()
                        .name(EVENT_SNAPSHOT)
                        .data(toJson(queryService.taskDetail(taskId))));
            } catch (Exception e) {
                // 快照都发不出去，说明这条连接已经废了 —— 回滚注册，别留下一个死连接
                log.warn("发送任务 {} 的 SSE 快照失败，连接作废", taskId, e);
                perTask.remove(emitter);
                dropTaskEntryIfEmpty(taskId, perTask);
                emitter.completeWithError(e);
                return emitter;
            }
        }

        // 三种结束路径都要清理，否则处理器列表只增不减（缓慢内存泄漏），
        // 且后续事件会一直往一个已经断开的连接上写
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(error -> remove(taskId, emitter));

        log.info("SSE 连接已建立: task={} 该任务连接数={}", taskId, perTask.size());
        return emitter;
    }

    private void onProgressEvent(ProgressEvent event) {
        List<SseEmitter> perTask = emittersByTask.get(event.taskId());
        if (perTask == null || perTask.isEmpty()) {
            // 没人在看这个任务：直接丢弃。进度事件是加速器，不是事实来源，
            // 不需要为「没人订阅」而落盘或补发
            return;
        }
        String json = toJson(event);
        for (SseEmitter emitter : perTask) {
            synchronized (emitter) {
                try {
                    emitter.send(SseEmitter.event().name(EVENT_PROGRESS).data(json));
                } catch (Exception e) {
                    // 单个连接写失败（浏览器已关页）不应该影响其它观看者
                    log.debug("向任务 {} 的一个 SSE 连接推送失败，移除该连接", event.taskId(), e);
                    remove(event.taskId(), emitter);
                }
            }
        }
    }

    /**
     * 心跳保活。间隔可通过 {@code remaster.web.sse-heartbeat-millis} 调整。
     *
     * <p>用 {@code comment} 而不是自定义事件名：SSE 注释行不会触发前端任何事件回调，
     * 是纯粹的链路保活手段。
     */
    @Scheduled(fixedDelayString = "${remaster.web.sse-heartbeat-millis:20000}")
    public void heartbeat() {
        if (emittersByTask.isEmpty()) {
            return;
        }
        emittersByTask.forEach((taskId, perTask) -> {
            for (SseEmitter emitter : perTask) {
                synchronized (emitter) {
                    try {
                        emitter.send(SseEmitter.event().comment("keep-alive"));
                    } catch (Exception e) {
                        remove(taskId, emitter);
                    }
                }
            }
        });
    }

    private void remove(long taskId, SseEmitter emitter) {
        List<SseEmitter> perTask = emittersByTask.get(taskId);
        if (perTask == null) {
            return;
        }
        perTask.remove(emitter);
        dropTaskEntryIfEmpty(taskId, perTask);
    }

    private void dropTaskEntryIfEmpty(long taskId, List<SseEmitter> perTask) {
        if (perTask.isEmpty()) {
            // remove(key, value) 只在映射仍指向同一个列表时才删，
            // 避免把「刚被并发的 open() 换上的新列表」误删
            emittersByTask.remove(taskId, perTask);
        }
    }

    private String toJson(Object value) {
        String payload = json.write(value);
        if (payload != null) {
            return payload;
        }
        // 序列化失败时给一个合法 JSON，而不是 null —— 前端 JSON.parse(null) 会抛异常，
        // 表现成「页面白了」，比收到一个明确标着失败的载荷难查得多
        log.error("SSE 载荷序列化失败，已用占位载荷代替: {}", value.getClass().getSimpleName());
        return "{\"error\":\"serialization_failed\"}";
    }

    /** 当前连接总数，供健康检查与测试断言。 */
    public int connectionCount() {
        return emittersByTask.values().stream().mapToInt(List::size).sum();
    }

    @Override
    public void destroy() {
        if (registration != null) {
            try {
                registration.close();
            } catch (Exception e) {
                log.warn("取消进度事件订阅失败", e);
            }
        }
        emittersByTask.values().forEach(perTask -> perTask.forEach(SseEmitter::complete));
        emittersByTask.clear();
    }
}
