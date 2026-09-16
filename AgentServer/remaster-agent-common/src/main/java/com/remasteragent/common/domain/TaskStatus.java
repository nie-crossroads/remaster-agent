package com.remasteragent.common.domain;

/**
 * 迁移任务的整体状态。
 *
 * <p>状态迁移：PENDING → RUNNING → (WAITING_HUMAN) → SUCCEEDED / FAILED / CANCELLED
 *
 * <p>{@code CANCELLED} 与 {@code FAILED} 刻意分开：失败是「跑完了但没通过」，取消是
 * 「人让它别跑了」。两者对指标报表的意义完全不同 —— 把取消算进失败率里，等于让
 * 「我主动停掉的那几十个实验任务」去污染「模型改不对代码」这个结论。
 */
public enum TaskStatus {
    /** 已创建，尚未被 Worker 取走 */
    PENDING,
    /** 正在执行 */
    RUNNING,
    /** 挂起等待人工审批（GATE 节点），此时不占用 Worker */
    WAITING_HUMAN,
    /** 全部节点成功 */
    SUCCEEDED,
    /** 失败且重试已耗尽 */
    FAILED,
    /**
     * 已取消 —— 人在跑到一半时叫停，或在排队/等评审时直接撤掉。
     *
     * <p>协作式停止：{@code migration_task.cancel_requested} 被置位后，Worker 在
     * <b>节点边界</b>处停下并落这个状态，不会强行中断正在跑的节点 ——
     * 沙箱里的 {@code mvn test} 被硬杀会留下半截工作目录，比多跑一个节点更糟。
     */
    CANCELLED
}
