package com.remasteragent.eval.report;

import com.remasteragent.common.domain.TaskMetrics;
import com.remasteragent.eval.catalog.EvalCase;
import com.remasteragent.eval.run.EvalRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告口径的守卫 —— 这个模块存在的理由。
 *
 * <h2>为什么值得为「几个除法」写测试</h2>
 * <p>报告是评测唯一对外输出的东西。口径错一点，数字就变成一种看起来很专业、
 * 实际上在骗人的东西 —— 而且骗的是自己：跑的人会拿着它去做「要不要继续优化」的判断。
 * 这里钉住的四条是口径里最容易悄悄走样的：分母是谁、无样本怎么显示、
 * 加权还是平均、harness 故障算不算。
 */
class EvalReportTest {

    private static final Instant NOW = Instant.parse("2026-09-16T08:00:00Z");

    /** filesTotal, compilePassed, testsTotal, testsPassed, coverage, llmCalls, prompt, completion, cost, verifyAttempts, durationMs */
    private static TaskMetrics metrics(int files, int compiled, int tests, int passed, int attempts) {
        return new TaskMetrics(files, compiled, tests, passed, 0.8d, 2,
                1_000L, 500L, 0.02d, attempts, 60_000L);
    }

    private static EvalRun run(String id, String status, EvalCase.ExpectedOutcome expect,
                               TaskMetrics metrics, Long runDurationMs, String harnessError, String... patterns) {
        return new EvalRun(id, "examples/eval/" + id, "src/main/java/X.java", "标题",
                List.of(patterns), expect, 1L, status, null, metrics, runDurationMs,
                70_000L, harnessError, NOW);
    }

    private static EvalRun positive(String id, TaskMetrics metrics) {
        return run(id, "SUCCEEDED", EvalCase.ExpectedOutcome.SUCCEEDED, metrics, 60_000L, null, "date-time");
    }

    // ------------------------------------------------------------------
    // 分母是谁
    // ------------------------------------------------------------------

    @Test
    @DisplayName("harness 故障不进任何成功率的分母")
    void harnessErrorsAreExcludedFromRates() {
        EvalReport report = new EvalReport(List.of(
                positive("a", metrics(4, 4, 51, 51, 1)),
                run("b", null, EvalCase.ExpectedOutcome.SUCCEEDED, null, null, "提交失败", "date-time")));

        assertEquals(2, report.total());
        assertEquals(1, report.usable());
        assertEquals(1, report.harnessErrors());
        assertEquals("1/1 (100.0%)", report.asExpected().text(),
                "故障那条不能把成功率拉到 50%");
    }

    @Test
    @DisplayName("「如期望」与「能力通过率」是两个数：负样本只影响前者")
    void asExpectedDiffersFromPositiveSuccess() {
        EvalRun negativeMissed = run("neg", "SUCCEEDED", EvalCase.ExpectedOutcome.FAILED,
                metrics(1, 1, 3, 3, 1), 10_000L, null, "date-time");

        EvalReport report = new EvalReport(List.of(positive("a", metrics(4, 4, 51, 51, 1)), negativeMissed));

        assertEquals("1/2 (50.0%)", report.asExpected().text(), "负样本被改好了 → 与期望不符");
        assertEquals("1/1 (100.0%)", report.positiveSuccess().text(), "但正样本能力仍是满分");
        assertEquals("0/1 (0.0%)", report.negativeCaught().text(), "负样本没被如期判失败");
    }

    // ------------------------------------------------------------------
    // 无样本
    // ------------------------------------------------------------------

    @Test
    @DisplayName("分母为 0 显示「无样本」，不是 0%")
    void emptyDenominatorIsNotZeroPercent() {
        EvalReport report = new EvalReport(List.of());

        assertFalse(report.asExpected().hasSamples());
        assertEquals("无样本", report.asExpected().text());
        assertTrue(report.asExpected().rate().isEmpty(), "没有样本就没有比率，不能默认 0");
        assertEquals("无样本", report.compile().text());
        assertEquals("无样本", report.tests().text());
        assertEquals("无样本", report.coverage().text(d -> d + ""));
    }

    @Test
    @DisplayName("跑完了但没指标（CANCELLED）：不进编译/单测分母，但要单独计数")
    void cancelledCaseExcludedFromMetricDenominators() {
        EvalRun cancelled = run("c", "CANCELLED", EvalCase.ExpectedOutcome.SUCCEEDED, null, 5_000L, null);

        EvalReport report = new EvalReport(List.of(positive("a", metrics(4, 4, 51, 51, 1)), cancelled));

        assertEquals(2, report.usable(), "CANCELLED 是真实结局，不是 harness 故障");
        assertEquals(1, report.withMetrics());
        assertEquals("4/4 (100.0%)", report.compile().text(), "编译率只按有指标的那条算（4 个文件）");
    }

    // ------------------------------------------------------------------
    // 加权，不是平均
    // ------------------------------------------------------------------

    @Test
    @DisplayName("通过率按样本量加权：51 例的那条不能和 3 例的那条等权")
    void ratesAreWeightedNotAveraged() {
        EvalReport report = new EvalReport(List.of(
                positive("big", metrics(4, 4, 51, 51, 1)),
                positive("small", metrics(1, 0, 3, 0, 1))));

        // 加权: 51/54 = 94.4%；等权平均: (100% + 0%) / 2 = 50%
        assertEquals("51/54 (94.4%)", report.tests().text());
        assertEquals("4/5 (80.0%)", report.compile().text());
    }

    // ------------------------------------------------------------------
    // 成本 / 耗时 / 回退
    // ------------------------------------------------------------------

    @Test
    @DisplayName("成本与 token 逐条求和")
    void costIsSummed() {
        EvalReport report = new EvalReport(List.of(
                positive("a", new TaskMetrics(1, 1, 3, 3, 0.8d, 1, 1000, 500, 0.02d, 1, 1000)),
                positive("b", new TaskMetrics(1, 1, 3, 3, 0.8d, 2, 2000, 800, 0.03d, 2, 1000))));

        assertEquals(3, report.llmCalls(), "1 + 2 次调用");
        assertEquals(4_300L, report.totalTokens());
        assertEquals(0.05d, report.totalCost(), 1e-9);
    }

    @Test
    @DisplayName("耗时分布忽略没有 trace 数据的用例，而不是当成 0 毫秒")
    void nullRunDurationIsSkipped() {
        EvalReport report = new EvalReport(List.of(
                positive("with-trace", metrics(1, 1, 3, 3, 1)),
                run("no-trace", "SUCCEEDED", EvalCase.ExpectedOutcome.SUCCEEDED,
                        metrics(1, 1, 3, 3, 1), null, null)));

        Stat duration = report.runDuration();
        assertEquals(1, duration.samples(), "只有一条有 trace 数据");
        assertEquals(60_000d, duration.mean(), 1e-9, "当成 0 会把均值拉到 30 秒");
    }

    @Test
    @DisplayName("P90 取真实发生过的样本（最近秩），不是插值")
    void p90IsANearestRankSample() {
        List<Double> values = List.of(1d, 2d, 3d, 4d, 5d, 6d, 7d, 8d, 9d, 100d);
        Stat stat = Stat.of(values);

        assertEquals(1d, stat.min(), 1e-9);
        assertEquals(100d, stat.max(), 1e-9);
        assertEquals(9d, stat.p90(), 1e-9, "ceil(0.9×10)-1 = 8 → 第 9 个元素");
        assertEquals(5d, stat.median(), 1e-9, "最近秩取第 ceil(0.5×10)=5 个元素，不做插值");
    }

    @Test
    @DisplayName("一次通过率：verifyAttempts > 1 就算回退过")
    void firstTryRate() {
        EvalReport report = new EvalReport(List.of(
                positive("clean", metrics(1, 1, 3, 3, 1)),
                positive("retried", metrics(1, 1, 3, 3, 3))));

        assertEquals("1/2 (50.0%)", report.firstTry().text());
        assertEquals(1, report.retriedCases());
        assertEquals(3d, report.verifyAttempts().max(), 1e-9, "轮次上限看这个数");
    }

    // ------------------------------------------------------------------
    // 分组
    // ------------------------------------------------------------------

    @Test
    @DisplayName("按模式分组，同一条用例可以属于多个标签")
    void groupsByPattern() {
        EvalReport report = new EvalReport(List.of(
                run("a", "SUCCEEDED", EvalCase.ExpectedOutcome.SUCCEEDED,
                        metrics(1, 1, 3, 3, 1), 1_000L, null, "date-time", "boxing"),
                run("b", "FAILED", EvalCase.ExpectedOutcome.SUCCEEDED,
                        metrics(1, 0, 3, 0, 1), 1_000L, null, "boxing")));

        assertEquals(2, report.byPattern().size());
        assertEquals("1/2 (50.0%)", report.byPattern().get("boxing").asExpected().text());
        assertEquals("1/1 (100.0%)", report.byPattern().get("date-time").asExpected().text());
    }
}
