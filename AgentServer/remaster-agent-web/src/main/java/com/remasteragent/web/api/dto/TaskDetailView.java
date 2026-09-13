package com.remasteragent.web.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 任务详情：概要 + DAG 节点 + 补丁 + 成本。
 *
 * <p>一次返回全部，不做「点开某个 Tab 再请求一次」。理由是这个页面的用途是
 * <b>审查一次迁移到底发生了什么</b>：节点状态、失败原因、diff、花了多少钱——
 * 这几样必须放在一起看才能形成判断（比如「改了 3 轮、花了 4 次调用、最后还是编译不过」）。
 * 拆成多个请求只会增加「拼不起来」的可能，而单任务的数据量是几十 KB 级别。
 *
 * <p>子视图嵌套在这里而不是各自独立成文件，是因为它们<b>只在这个组合里有意义</b>：
 * {@code NodeView} 脱离任务上下文没有用途，单独暴露反而容易被人误用成公共契约。
 */
public record TaskDetailView(
        TaskView task,
        List<NodeView> nodes,
        List<PatchView> patches,
        CostView cost
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

    /** 一次改写产生的补丁，前端用 Monaco 渲染。 */
    public record PatchView(
            long nodeId,
            String filePath,
            String diff
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
}
