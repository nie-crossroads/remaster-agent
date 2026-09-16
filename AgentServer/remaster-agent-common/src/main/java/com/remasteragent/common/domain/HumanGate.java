package com.remasteragent.common.domain;

import java.time.Instant;

/**
 * 一次人工门禁记录 —— GATE 节点挂起时产生，人工审批后落定。
 *
 * <p>它是阶段 3「通用人在回路」的持久化载体，对应 {@code human_gate} 表。一行记录
 * 完整回答四个问题：<b>哪道门</b>（{@code nodeId}）、<b>批没批</b>（{@code status}）、
 * <b>谁批的</b>（{@code reviewer}）、<b>为什么</b>（{@code comment}），
 * 外加两个时间点（挂起、决定）。这些字段全部是审计所需，缺一个「事后回看」就不完整。
 *
 * <h2>为什么它不复用「规划评审」那条路</h2>
 * <p>阶段 2 的规划评审走的是 {@code migration_task.plan_approved} 一个布尔列 ——
 * 因为「这份计划批过没有」是一个任务级、一次性、不可重来的判断题。
 * 而 GATE 是<b>节点级、可重复、可插在任意位置</b>的门：多文件迁移可能有多道门，
 * 每道门都要独立留痕（谁在第二道门放了行，与第一道无关）。用一个布尔列表达不了，
 * 所以另起一张表。
 *
 * @param id        自增主键
 * @param nodeId    对应的 GATE 节点（{@code dag_node.id}）
 * @param status    审批状态
 * @param reviewer  审批人；未决定时为空
 * @param comment   审批意见；驳回时常用来写「为什么不同意」
 * @param createdAt 挂起时间
 * @param decidedAt 审批时间；未决定时为空
 */
public record HumanGate(
        Long id,
        long nodeId,
        GateStatus status,
        String reviewer,
        String comment,
        Instant createdAt,
        Instant decidedAt
) {

    /** 是否仍在等待人工处理 —— 任务能否继续取决于它。 */
    public boolean isOpen() {
        return status == GateStatus.PENDING;
    }
}
