package com.remasteragent.common.domain;

/**
 * 单个 DAG 节点的执行状态。
 * 节点级状态机：PENDING → RUNNING → SUCCEEDED / FAILED
 *
 * 注意 FAILED 不是终点：VERIFY 失败会由调度器派生一个 attempt+1 的 REWRITE 节点，
 * 原节点保留 FAILED 作为审计痕迹（失败日志要喂回给模型）。
 */
public enum NodeStatus {
    /** 依赖未就绪或等待调度 */
    PENDING,
    /** 正在执行 */
    RUNNING,
    /** 执行成功 */
    SUCCEEDED,
    /** 执行失败，可能触发上游重写 */
    FAILED,
    /** 被调度器判定无需执行（如上游已放弃） */
    SKIPPED
}
