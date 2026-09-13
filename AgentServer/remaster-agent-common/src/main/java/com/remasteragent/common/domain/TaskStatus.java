package com.remasteragent.common.domain;

/**
 * 迁移任务的整体状态。
 * 状态迁移：PENDING → RUNNING → (WAITING_HUMAN) → SUCCEEDED / FAILED
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
    FAILED
}
