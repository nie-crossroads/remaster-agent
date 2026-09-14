package com.remasteragent.common.domain;

/**
 * 指标事件的传输形状 —— 与 web 层 {@code TaskView.MetricsView} 字段一一对齐。
 *
 * <h2>为什么不能直接把 {@link TaskMetrics} 塞进事件里（这是踩出来的）</h2>
 * <p>{@code TaskMetrics} 是<b>存储</b>形状，它的 {@code compilePassRate()} / {@code testPassRate()} /
 * {@code retried()} 都是<b>派生方法</b>（record 的非组件方法）。Jackson 序列化 record 时只看
 * record 组件，派生方法既不是 {@code getXxx} 也不是组件，因此<b>一个都不会出现在 JSON 里</b>。
 *
 * <p>于是出现了一个只在「实时」路径上才发生的错位：
 * <ul>
 *   <li>REST 快照（{@code GET /api/tasks/{id}}）走 {@code TaskMetrics} → {@code MetricsView} 的显式映射，
 *       通过率字段齐全，页面正常；</li>
 *   <li>SSE 增量（{@code task_metrics}）却是把 {@code TaskMetrics} 直接序列化发出去的，
 *       <b>没有 {@code compilePassRate} / {@code testPassRate} / {@code retried}</b>。</li>
 * </ul>
 * 前端收到增量后会用这份载荷<b>整体覆盖</b> {@code task.metrics}，于是通过率字段变成 undefined，
 * 进度条归零、状态被判成失败显示 ✗ —— 而任务其实刚刚成功。最迷惑的地方在于
 * <b>{@code coverage} 是 record 组件，所以覆盖率一直显示正常</b>，让人以为是进度条组件坏了，
 * 而不是「这一路的数据形状和快照不是同一个」。
 *
 * <h2>纪律</h2>
 * <p><b>跨进程事件载荷必须与 REST 快照同形状。</b>前端会把两者叠在同一份状态上，
 * 形状不一致 = 投影出来的状态与快照不一致，而且只在「先拿快照、再来增量」的顺序下暴露。
 * 由 {@code MetricsEventShapeTest} 钉死：本 record 与 {@code TaskView.MetricsView}
 * 的属性名集合必须完全相同。
 *
 * @param filesTotal       参与迁移的文件数
 * @param compilePassed    编译通过的文件数
 * @param compilePassRate  编译通过率（0~1；分母为 0 时为 0，前端须再看 filesTotal）
 * @param testsTotal       单测总数
 * @param testsPassed      单测通过数
 * @param testPassRate     单测通过率（0~1；分母为 0 时为 0，前端须再看 testsTotal）
 * @param coverage         行覆盖率（0~1；<b>-1 表示未采集</b>，与「真的 0%」是两件事）
 * @param llmCalls         LLM 调用总次数
 * @param promptTokens     prompt token 总量
 * @param completionTokens completion token 总量
 * @param totalCost        总成本（美元；前端按 ¥ 展示，见 DATA_MODEL.md 的注释漂移记录）
 * @param verifyAttempts   VERIFY 执行次数，>1 说明发生过回退重写
 * @param retried          是否发生过回退（{@code verifyAttempts > 1}）
 * @param durationMs       端到端耗时
 */
public record TaskMetricsSnapshot(
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

    /** 由存储形状展开成传输形状。派生值在这里算一次，而不是让每个消费方各算一遍。 */
    public static TaskMetricsSnapshot of(TaskMetrics metrics) {
        if (metrics == null) {
            return null;
        }
        return new TaskMetricsSnapshot(
                metrics.filesTotal(),
                metrics.compilePassed(),
                metrics.compilePassRate(),
                metrics.testsTotal(),
                metrics.testsPassed(),
                metrics.testPassRate(),
                metrics.coverage(),
                metrics.llmCalls(),
                metrics.promptTokens(),
                metrics.completionTokens(),
                metrics.totalCost(),
                metrics.verifyAttempts(),
                metrics.retried(),
                metrics.durationMs());
    }
}
