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

import java.util.ArrayDeque;
import java.util.Deque;
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
 *
 * <h2>为什么还要补发「最近事件」</h2>
 * <p>快照能还原任务的<b>状态</b>，却还原不出<b>过程</b>：「刚才发生了什么」不属于任何一张表。
 * 刷新一次页面，之前收到的增量事件就全没了 —— 而用户恰恰是靠那段滚动日志判断
 * 「它在动，还是在重试」的。所以每个任务在内存里留一份最近事件的环形缓冲，
 * 连接建立时连同快照一起补发（见 {@link #recentEventsOf}）。
 *
 * <p>它<b>刻意只活在内存里</b>，不落库：进度事件是加速器不是事实来源（掉一条只损失实时性，
 * 权威状态永远能从 {@code dag_node} / {@code patch} 读回来）。为它加一张表、让每条事件
 * 都写一次库，代价与收益不成比例；代价是 API 进程重启后这段历史会丢，而<b>节点与补丁不会</b>。
 */
@Component
public class SseEventHub implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SseEventHub.class);

    /** 事件名：一次连接只发一次，内容是任务的完整当前状态。 */
    public static final String EVENT_SNAPSHOT = "snapshot";

    /** 事件名：增量进度事件，对应 {@link ProgressEvent}。 */
    public static final String EVENT_PROGRESS = "progress";

    /**
     * 事件名：连接建立时补发的最近若干条增量事件，载荷是 {@code ProgressEvent[]}（新 → 旧）。
     *
     * <p>紧跟在快照之后发出，且与快照在同一次加锁中写完 —— 前端可以放心地
     * 先用它重建事件流，再往前面追加后续增量。
     */
    public static final String EVENT_HISTORY = "history";

    /**
     * 每个任务保留的最近事件条数。
     *
     * <p>取 50 与前端 {@code recentEvents} 的上限对齐：前端只展示 50 条，
     * 后端多留是浪费，少留则刷新后列表明显短一截。
     */
    private static final int RECENT_EVENT_LIMIT = 50;

    /**
     * 最多为多少个任务保留事件缓冲。
     *
     * <p>不设上限的话，「看过很多任务」会让这份内存随任务数单调增长。
     * 真正有意义的只有当前正在看的那个任务，所以超限时按 taskId 丢掉最小值
     * （id 单调递增，最小即最早）—— 近似的 LRU，够用且不必引入 LinkedHashMap 的同步开销。
     */
    private static final int RECENT_TASK_LIMIT = 200;

    private final ProgressEventSubscriber subscriber;
    private final TaskQueryService queryService;
    private final JsonCodec json;

    /** taskId → 正在监听该任务的连接。用 CopyOnWriteArrayList：读多写少，且遍历时不能抛并发修改异常。 */
    private final Map<Long, List<SseEmitter>> emittersByTask = new ConcurrentHashMap<>();

    /**
     * taskId → 最近事件环形缓冲（新 → 旧，最多 {@link #RECENT_EVENT_LIMIT} 条）。
     *
     * <p>所有读写都在对应任务的 {@code perTask} 锁内完成，与「登记连接」互斥 ——
     * 这是「补发的历史」和「随后到达的增量」不重不漏的前提，见 {@link #onProgressEvent}。
     */
    private final Map<Long, Deque<ProgressEvent>> recentByTask = new ConcurrentHashMap<>();

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
     *
     * <p>依次发两帧：**快照**（数据库当前状态）与 **历史**（内存里最近的事件）。
     * 两者都在 {@code perTask} 锁内写完，所以它们之间绝不会插进一条增量。
     */
    public SseEmitter open(long taskId) {
        SseEmitter emitter = new SseEmitter(0L);
        List<SseEmitter> perTask = emittersByTask.computeIfAbsent(taskId, key -> new CopyOnWriteArrayList<>());

        // 锁序在两条路径上都必须固定为「先 perTask、后 emitter」（另一处见 onProgressEvent）。
        // 反过来就会出现 A 持 emitter 等 perTask、B 持 perTask 等 emitter 的死锁。
        //
        // 「取历史 → 登记连接」必须在同一把锁里完成：
        //   · 登记之前发生的事件，一定已经进了这次取出的历史；
        //   · 登记之后发生的事件，一定走增量分发（它也要拿这把锁，进不来这段）。
        // 于是两个集合恰好互补，既不会漏一条，也不会同一条既在历史里又当增量发一次。
        synchronized (perTask) {
            List<ProgressEvent> history = recentEventsOf(taskId);
            perTask.add(emitter);
            synchronized (emitter) {
                try {
                    emitter.send(SseEmitter.event()
                            .name(EVENT_SNAPSHOT)
                            .data(toJson(queryService.taskDetail(taskId))));
                    if (!history.isEmpty()) {
                        emitter.send(SseEmitter.event()
                                .name(EVENT_HISTORY)
                                .data(toJson(history)));
                    }
                } catch (Exception e) {
                    // 快照都发不出去，说明这条连接已经废了 —— 回滚注册，别留下一个死连接
                    log.warn("发送任务 {} 的 SSE 快照失败，连接作废", taskId, e);
                    perTask.remove(emitter);
                    dropTaskEntryIfEmpty(taskId, perTask);
                    emitter.completeWithError(e);
                    return emitter;
                }
            }
        }

        // 三种结束路径都要清理，否则处理器列表只增不减（缓慢内存泄漏），
        // 且后续事件会一直往一个已经断开的连接上写
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(error -> remove(taskId, emitter));

        log.info("SSE 连接已建立: task={} 该任务连接数={} 补发历史={} 条",
                taskId, perTask.size(), recentEventsOf(taskId).size());
        return emitter;
    }

    /**
     * 某任务当前的事件缓冲副本（新 → 旧）。
     *
     * <p>返回不可变副本：调用方拿到之后会去发网络 IO，期间缓冲还在被增量事件改写，
     * 直接交出内部 {@code Deque} 会边遍历边被改。
     */
    private List<ProgressEvent> recentEventsOf(long taskId) {
        Deque<ProgressEvent> buffer = recentByTask.get(taskId);
        return buffer == null ? List.of() : List.copyOf(buffer);
    }

    /**
     * 把一条事件记进该任务的缓冲（新 → 旧，超出上限丢最旧）。
     *
     * <p>调用方必须已持有该任务的 {@code perTask} 锁。
     */
    private void remember(ProgressEvent event) {
        Deque<ProgressEvent> buffer = recentByTask.get(event.taskId());
        if (buffer == null) {
            buffer = new ArrayDeque<>();
            recentByTask.put(event.taskId(), buffer);
            evictOldestBuffersIfNeeded();
        }
        buffer.addFirst(event);
        while (buffer.size() > RECENT_EVENT_LIMIT) {
            buffer.removeLast();
        }
    }

    /**
     * 缓冲的任务数超上限时，按 taskId 丢最小的（≈ 最早的任务）。
     *
     * <p>清理是「尽力而为」的：并发下 size 判断可能略有偏差，多留一两个缓冲无关紧要 ——
     * 这里要防的是单调增长，不是精确配额。
     */
    private void evictOldestBuffersIfNeeded() {
        while (recentByTask.size() > RECENT_TASK_LIMIT) {
            Long oldest = recentByTask.keySet().stream().min(Long::compareTo).orElse(null);
            if (oldest == null || recentByTask.remove(oldest) == null) {
                return;
            }
        }
    }

    private void onProgressEvent(ProgressEvent event) {
        List<SseEmitter> perTask = emittersByTask.get(event.taskId());
        if (perTask == null || perTask.isEmpty()) {
            // 没人在看这个任务：既不分发，也不记历史。
            //
            // 「没人看就不记」是刻意的：缓冲只为「用户刷新一下还能看到刚才发生了什么」服务，
            // 而权威状态（节点/补丁/成本）本来就都在库里，刷新后快照会补齐。
            // 若对每个任务都无条件记录，这份内存就会随历史任务数增长 —— 一个纯装饰性的
            // 功能不该有这种代价。
            return;
        }
        String json = toJson(event);
        synchronized (perTask) {
            // 先入缓冲再分发：任何时刻新建的连接取历史时，「此刻之前」的事件必然都在里面。
            // 顺序反过来的话，一条刚分发完、还没进缓冲的事件，会既不在别人的历史里、
            // 也不再作为增量到达（连接是刚建的，早于它的事件都算历史）。
            remember(event);
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

    /**
     * 某任务当前缓冲的事件条数，供健康检查与测试断言。
     *
     * <p>暴露它是因为「补发的历史」没法从快照断言——{@code SseEmitter} 在没有 HTTP 响应对象时
     * 把报文缓冲在内部，单测取不到（见类注释的说明）。能断言的就只有账目：记了几条、有没有上限。
     */
    public int bufferedEventCount(long taskId) {
        Deque<ProgressEvent> buffer = recentByTask.get(taskId);
        return buffer == null ? 0 : buffer.size();
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
        recentByTask.clear();
    }
}
