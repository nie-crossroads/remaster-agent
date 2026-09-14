package com.remasteragent.core.progress;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * 进度事件 —— 从 Worker 进程流向 API 进程再推给浏览器的最小载体。
 *
 * <p>为什么需要它、而不是直接推 SSE：SSE 的连接对象活在 API 进程里，
 * 而节点状态变化发生在 Worker 进程。跨进程的进度传递必须借一个中间通道（本项目用 Redis Pub/Sub），
 * 事件对象就是这个通道里的消息体。少了这一层，SSE 永远是「连上了但永远没有消息」。
 *
 * @param taskId  任务 id，接收端据此路由到对应的 SSE 连接
 * @param nodeId  节点 id，流程级事件（如任务完成）时为空
 * @param nodeKey 节点业务键，便于前端按节点聚合
 * @param type    事件类型：node_status / task_status / task_metrics
 * @param status  节点或任务的最新状态字符串
 * @param attempt 尝试次数，前端据此展示「第几次重写」
 * @param message 人类可读的补充说明
 * @param at      发生时间
 *
 * <p>本记录经 Redis Pub/Sub 跨进程传输，JSON 形状必须<b>恰好</b>等于上面这 8 个字段。
 * 派生方法必须标 {@link JsonIgnore}：`isHeartbeat()` 这种 {@code boolean} 方法会被 Jackson
 * 当成 getter，在报文里凭空多出一个 {@code heartbeat} 字段 —— 实测已发生（与
 * {@code VerifyResult.isGreen()} 是同一个坑）。{@link JsonIgnoreProperties} 是第二道保险，
 * 保证以后再加派生方法也不会把「对端发来的旧形状报文」读坏。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProgressEvent(
        long taskId,
        Long nodeId,
        String nodeKey,
        String type,
        String status,
        int attempt,
        String message,
        Instant at
) {

    public static final String TYPE_NODE_STATUS = "node_status";
    public static final String TYPE_TASK_STATUS = "task_status";
    public static final String TYPE_TASK_METRICS = "task_metrics";

    /**
     * 链路探活心跳 —— <b>不是任务进度</b>。
     *
     * <p>它存在的唯一理由是：Redis Pub/Sub 的订阅连接是「只读」的，链路上没有任何东西会写它，
     * 所以一条被防火墙/NAT 静默回收的半开连接，客户端<b>永远不会主动发现</b>
     * （TCP 层没有写操作就不会触发重传超时，{@code netstat} 也照样显示 ESTABLISHED）。
     * 表现是：快照正常（走数据库）、SSE 心跳正常（走连接写），唯独增量事件一条都到不了 ——
     * 页面永远停在打开那一刻。
     *
     * <p>让这条消息定期走一遍「发布 → 服务端 → 订阅连接」的完整回路，就同时得到两件事：
     * 既让订阅连接持续有流量（不被判空闲回收），又让订阅端能凭「有没有收到」发现自己已经聋了。
     * 订阅端收到后只刷新链路时间戳，<b>绝不派发</b>（见
     * {@code RedisProgressEventSubscriber}）—— 它不是给人看的进度。
     */
    public static final String TYPE_HEARTBEAT = "heartbeat";

    /** 心跳事件的 taskId。心跳不属于任何任务，用一个不会与真实任务撞车的哨兵值。 */
    public static final long NO_TASK = 0L;

    public static ProgressEvent nodeStatus(long taskId, Long nodeId, String nodeKey,
                                           String status, int attempt, String message) {
        return new ProgressEvent(taskId, nodeId, nodeKey, TYPE_NODE_STATUS, status, attempt, message,
                Instant.now());
    }

    /** 构造一条链路探活心跳，见 {@link #TYPE_HEARTBEAT}。 */
    public static ProgressEvent heartbeat() {
        return new ProgressEvent(NO_TASK, null, null, TYPE_HEARTBEAT, "ALIVE", 0, null, Instant.now());
    }

    /**
     * 是否是链路探活心跳。订阅端据此决定「只刷新链路时间戳、不派发」。
     *
     * <p>{@link JsonIgnore} 不是可选项：方法名以 {@code is} 开头且返回 {@code boolean}，
     * Jackson 会把它当作属性 {@code heartbeat} 一起写进报文。实测在 SSE 报文里看到过
     * {@code "heartbeat":false} —— 那不是设计的一部分，是漏标注解的产物。
     */
    @JsonIgnore
    public boolean isHeartbeat() {
        return TYPE_HEARTBEAT.equals(type);
    }

    public static ProgressEvent taskStatus(long taskId, String status, String message) {
        return new ProgressEvent(taskId, null, null, TYPE_TASK_STATUS, status, 0, message, Instant.now());
    }

    public static ProgressEvent taskMetrics(long taskId, String message) {
        return new ProgressEvent(taskId, null, null, TYPE_TASK_METRICS, "READY", 0, message, Instant.now());
    }
}
