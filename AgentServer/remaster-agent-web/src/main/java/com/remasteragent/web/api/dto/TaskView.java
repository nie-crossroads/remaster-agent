package com.remasteragent.web.api.dto;

import java.time.Instant;

/**
 * 任务列表项 / 任务概要。
 *
 * <p><b>为什么把 metrics 摊平成显式字段，而不是直接透传数据库里那段 JSON。</b>
 * 数据库里的 {@code metrics} 是内部存储格式（字段名、单位都可能随实现变化），
 * 而这是对外契约。让前端去解析一个内部 JSON，等于把内部结构固化成了 API ——
 * 以后想改存储格式就得同时改前端，而这种耦合没有任何收益。
 *
 * @param metrics 指标汇总；任务未结束时为 null
 * @param runDurationMs 累计运行时长 = 各次运行的墙钟之和
 *        （见 {@code TaskTraceView#totalRunDurationMs}），**不含**排队与「取消到重跑之间人去吃饭」的空档。
 *
 *        <p>它回答的是「机器一共干了多久」，而 {@code metrics.durationMs} 回答的是
 *        「你一共等了多久」（入队到本次运行结束）—— 取消重跑过的任务上，两者能差出数量级
 *        （实测任务 #10：77 秒 vs 2 小时 33 分）。只给一个数时，读者无从知道它答的是哪个问题。
 *
 *        <p><b>查询时从 trace_span 现算，不落库</b>：存一份副本就会在链路数据变化后变成对不上的旧账
 *        （本项目在指标派生字段上已踩过「同一语义两个来源」的坑）。
 *        <b>刻意不放进 {@code metrics}</b>：{@code MetricsView} 要与 SSE 的 {@code TaskMetricsSnapshot}
 *        保持同形状（有测试守着），而那个载荷由 Worker 发出，它算这个值时本次运行的根 span
 *        还没结束，只会算出一个偏小的数。
 *
 *        <p>{@code null} = 这个任务一条 span 都没有（埋点接上之前的老任务、或埋点被关掉）。
 *        此时**不能说成「运行了 0 秒」**，只能承认不知道 —— 所以这里是可空的 {@code Long}。
 * @param cancelRequested 是否已被请求取消。与 {@code status} 并列而不合并，因为两者可以同时为
 *        「RUNNING + 已请求取消」—— 那正是「点完取消、当前节点还在跑」的真实状态。
 *        前端据它把按钮显示成「正在取消…」，否则点了取消页面毫无变化，看起来像没生效
 */
public record TaskView(
        long id,
        String projectRoot,
        String entryFile,
        int targetJdk,
        String status,
        String failReason,
        boolean cancelRequested,
        Instant createdAt,
        Instant updatedAt,
        MetricsView metrics,
        Long runDurationMs
) {

    /**
     * 量化指标 —— 这个项目最核心的交付物。
     *
     * <p>同时给出原始计数（{@code passed} / {@code total}）和比率，不是冗余：
     * 比率回答「好不好」，计数回答「分母是什么、有没有样本」。
     * 只有比率的话，「单测通过率 100%」在样本为 0 时会产生严重误导 ——
     * 所以 {@code testsTotal} 为 0 时前端必须显示「无单测」而不是「100%」。
     *
     * <p>{@code durationMs} 是端到端墙钟 = **入队到本次运行结束**：含排队、含被取消的那次、
     * 含「取消到重跑之间人去吃饭」的空档。它是「你一共等了多久」。
     * 「机器干了多久」是 {@code TaskView#runDurationMs}，两者刻意分开而不是合成一个字段。
     *
     * <p>这个 record 的形状必须与 {@code TaskMetricsSnapshot}（SSE 增量载荷）**完全一致**，
     * {@code MetricsEventShapeTest} 在守着。要加字段先想清楚 Worker 那边取不取得到值。
     */
    public record MetricsView(
            int filesTotal,
            int compilePassed,
            double compilePassRate,
            int testsTotal,
            int testsPassed,
            double testPassRate,
            double coverage,
            int llmCalls,
            long promptTokens,
            long completionTokens,
            double totalCost,
            int verifyAttempts,
            boolean retried,
            long durationMs
    ) {
    }
}
