package com.remasteragent.common.domain;

import java.time.Instant;
import java.util.List;

/**
 * DAG 节点 —— 同时也是 checkpoint 的载体。
 *
 * <p>每次执行的结果（编译日志、单测统计、覆盖率、耗时）都序列化进 {@code resultJson}
 * 落到 {@code dag_node.result}（JSONB）。Worker 崩溃重启后靠它恢复现场，
 * 这是这个项目「断点续跑」能力的唯一数据来源。
 *
 * <p>唯一约束是 {@code (task_id, node_key, attempt)}，不是 {@code (task_id, node_key)} ——
 * 因为回退重写会为同一个 nodeKey 产生 attempt+1 的新行，两者必须能共存。
 *
 * @param id         自增主键
 * @param taskId     所属迁移任务
 * @param nodeKey    节点在任务内的业务键，如 {@code rewrite:com.foo.OrderService}
 * @param nodeType   节点类型
 * @param dependsOn  上游节点 id 列表，调度器据此做拓扑排序
 * @param status     节点状态
 * @param attempt    第几次尝试，从 0 开始；回退重写时 +1
 * @param resultJson 执行结果（JSON 字符串，原样对应 JSONB 列）
 * @param error      失败原因摘要，成功时为空
 * @param startedAt  开始时间
 * @param finishedAt 结束时间
 */
public record DagNode(
        Long id,
        long taskId,
        String nodeKey,
        NodeType nodeType,
        List<Long> dependsOn,
        NodeStatus status,
        int attempt,
        String resultJson,
        String error,
        Instant startedAt,
        Instant finishedAt
) {

    /**
     * 幂等键。节点执行前先查它是否已 SUCCEEDED，是则直接跳过 ——
     * 避免 Worker 重启或消息重投导致同一次改写被重复执行、重复烧 token。
     */
    public String idempotencyKey() {
        return taskId + ":" + nodeKey + ":" + attempt;
    }

    public boolean isTerminal() {
        return status == NodeStatus.SUCCEEDED || status == NodeStatus.FAILED || status == NodeStatus.SKIPPED;
    }
}
