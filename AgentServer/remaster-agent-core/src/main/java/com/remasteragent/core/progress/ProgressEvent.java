package com.remasteragent.core.progress;

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
 */
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

    public static ProgressEvent nodeStatus(long taskId, Long nodeId, String nodeKey,
                                           String status, int attempt, String message) {
        return new ProgressEvent(taskId, nodeId, nodeKey, TYPE_NODE_STATUS, status, attempt, message,
                Instant.now());
    }

    public static ProgressEvent taskStatus(long taskId, String status, String message) {
        return new ProgressEvent(taskId, null, null, TYPE_TASK_STATUS, status, 0, message, Instant.now());
    }

    public static ProgressEvent taskMetrics(long taskId, String message) {
        return new ProgressEvent(taskId, null, null, TYPE_TASK_METRICS, "READY", 0, message, Instant.now());
    }
}
