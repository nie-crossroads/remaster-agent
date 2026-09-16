package com.remasteragent.web.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 任务详情：概要 + DAG 节点 + 补丁 + 成本 + 迁移计划。
 *
 * <p>一次返回全部，不做「点开某个 Tab 再请求一次」。理由是这个页面的用途是
 * <b>审查一次迁移到底发生了什么</b>：节点状态、失败原因、diff、花了多少钱、打算改哪些文件——
 * 这几样必须放在一起看才能形成判断（比如「规划说改 2 个文件、实际改了 3 轮、花了 4 次调用、
 * 最后还是编译不过」）。拆成多个请求只会增加「拼不起来」的可能，而单任务的数据量是几十 KB 级别。
 *
 * <p>子视图嵌套在这里而不是各自独立成文件，是因为它们<b>只在这个组合里有意义</b>：
 * {@code NodeView} 脱离任务上下文没有用途，单独暴露反而容易被人误用成公共契约。
 *
 * <p>{@code plan} 可为 null —— 未开启 PLAN 的部署（阶段 1 拓扑）本就没有计划，
 * 前端必须把它当成「可能没有」来处理，而不是期望一个空壳对象。
 *
 * <p>{@code gate} 可为 null —— 仅当任务此刻卡在一道等待人工的门禁上时才有值。
 * 它与 {@code plan} 的区别是：计划是「已经发生过的事」（PLAN 的产出，一直在），
 * 门禁是「正挡在路上、要你去处理的事」（处理完就没了）。
 */
public record TaskDetailView(
        TaskView task,
        List<NodeView> nodes,
        List<PatchView> patches,
        CostView cost,
        PlanView plan,
        GateView gate
) {

    /**
     * 一个 DAG 节点。
     *
     * <p>{@code dependsOn} 是上游节点 id 列表，前端据此画 DAG 连线 ——
     * 回退重写会动态往图里追加节点，所以这个结构不是静态的，
     * 前端必须真的按依赖关系渲染，不能假设「三步流水线」。
     *
     * @param verify 仅 VERIFY 节点有值，把 result JSON 里的关键统计摊平出来
     */
    public record NodeView(
            Long id,
            String nodeKey,
            String nodeType,
            String status,
            int attempt,
            List<Long> dependsOn,
            String error,
            Instant startedAt,
            Instant finishedAt,
            VerifyView verify
    ) {
    }

    /** VERIFY 节点的产出摘要 —— 闭环里唯一的「真相来源」，必须在 UI 上可见。 */
    public record VerifyView(
            boolean compiled,
            int testsTotal,
            int testsPassed,
            int testsFailed,
            int testsSkipped,
            double coverage,
            boolean timedOut,
            String failureExcerpt
    ) {
    }

    /**
     * 一次改写产生的补丁，前端用 Monaco 渲染。
     *
     * @param attempt 产出它的 REWRITE 节点的轮次（0 起）。回退重写会给同一个文件产生
     *                <b>多份</b>补丁，光看文件名分不清哪份是哪轮 —— 带轮次才能标成
     *                「第 2 轮」，让「模型第二次改了什么」这件事在界面上看得见。
     */
    public record PatchView(
            long nodeId,
            String filePath,
            String diff,
            int attempt
    ) {
    }

    /** 成本汇总 —— 回答「这一轮迁移花了多少钱」。 */
    public record CostView(
            int calls,
            long promptTokens,
            long completionTokens,
            double totalCost
    ) {
    }

    /**
     * 迁移计划 —— 阶段 2「规划结果人工评审」要展示的东西。
     *
     * <p><b>为什么计划要单独作为一个视图，而不是塞进某个节点里</b>：
     * 计划是<b>人做决策的依据</b>，而节点列表是「机器做过什么」的流水账。
     * 评审界面要的是前者：一句话说清整体思路 + 逐个文件说明为什么改它。
     * 把计划摊在节点流水里，人会淹在 PENDING/RUNNING 里找不到重点。
     *
     * <p>{@code approved} 与计划放在一起，是因为它只对计划有意义 ——
     * 「这份计划批过没有」是一个判断题，前端据此决定要不要显示批准按钮。
     *
     * @param summary  一句话概述整体迁移思路
     * @param steps    待迁移文件及理由，按建议执行顺序
     * @param approved 是否已被人工批准（未开启评审时恒为 false，前端不看它）
     */
    public record PlanView(
            String summary,
            List<PlanStepView> steps,
            boolean approved
    ) {
    }

    /** 计划中的一步。 */
    public record PlanStepView(
            String filePath,
            String rationale
    ) {
    }

    /**
     * 一道等待人工处理的门禁（GATE 节点）—— 阶段 3「通用人在回路」的评审入口。
     *
     * <p>只有任务此刻被某道门挡住时，{@link TaskDetailView#gate()} 才有值；
     * 前端据此显示「批准 / 驳回」卡片。它出现的<b>唯一条件</b>是库里存在一行
     * {@code human_gate.status = PENDING}，所以它一旦有值，就确实在等人。
     *
     * @param id        门禁 id（审批接口不需要它，但排查时能对上库里的行）
     * @param nodeId    对应 GATE 节点，用于在 DAG 图上定位这道门
     * @param nodeKey   节点键，形如 {@code gate:com/foo/Bar.java}
     * @param filePath  被门禁拦下的文件（从 nodeKey 解析），无则 null
     * @param status    审批状态（当前只会是 PENDING，预留 APPROVED/REJECTED 以便将来展示历史）
     * @param comment   挂起说明（「已改写 X，请确认…」）
     * @param createdAt 挂起时间
     * @param decidedAt 决定时间（未决定时为 null）
     */
    public record GateView(
            long id,
            long nodeId,
            String nodeKey,
            String filePath,
            String status,
            String comment,
            Instant createdAt,
            Instant decidedAt
    ) {
    }
}
