package com.remasteragent.eval.run;

import com.remasteragent.common.domain.TaskMetrics;
import com.remasteragent.eval.catalog.EvalCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单条结果的判定逻辑 —— 评测里最容易出错、也最值得钉住的一段。
 *
 * <h2>三件必须分清的事</h2>
 * <ol>
 *   <li><b>「如期望」不等于「成功」。</b>负样本的期望是 FAILED，它失败了才是「如期望」。
 *       把「成功」当判据，负样本会全部被判成异常，成功率也就失去意义。</li>
 *   <li><b>harness 故障不算任务失败。</b>API 抖了、解析炸了，是工具的问题。
 *       把它算进成功率是自我欺骗 —— 而且是往坏的方向骗，更容易蒙混过关。</li>
 *   <li><b>分母为 0 不能算「全通过」。</b>{@code allCompiled()} 在「没有文件可编译」时
 *       必须给 false，否则加权公式会把一个空样本当成满分。</li>
 * </ol>
 */
class EvalRunTest {

    private static TaskMetrics metrics(int filesTotal, int compilePassed, int testsTotal, int testsPassed) {
        return new TaskMetrics(filesTotal, compilePassed, testsTotal, testsPassed,
                0.75d, 3, 1_000L, 500L, 0.0123d, 1, 45_000L);
    }

    private static EvalRun run(EvalCase.ExpectedOutcome expect, String status, TaskMetrics metrics) {
        return run(expect, status, metrics, null);
    }

    private static EvalRun run(EvalCase.ExpectedOutcome expect, String status, TaskMetrics metrics,
                               Long runDurationMs) {
        return new EvalRun("case-x", "examples/eval/x", "src/main/java/X.java", "标题",
                List.of("date-time"), expect, 42L, status, null, metrics, runDurationMs,
                50_000L, null, Instant.parse("2026-09-16T08:00:00Z"));
    }

    // ------------------------------------------------------------------
    // 判定
    // ------------------------------------------------------------------

    @Test
    @DisplayName("正样本成功 = 如期望")
    void positiveSampleSucceeded() {
        EvalRun result = run(EvalCase.ExpectedOutcome.SUCCEEDED, "SUCCEEDED", metrics(4, 4, 51, 51));

        assertEquals(EvalRun.Verdict.AS_EXPECTED, result.verdict());
        assertTrue(result.usable());
        assertFalse(result.isNegative());
    }

    @Test
    @DisplayName("正样本失败 = 与期望不符")
    void positiveSampleFailed() {
        EvalRun result = run(EvalCase.ExpectedOutcome.SUCCEEDED, "FAILED", metrics(4, 4, 51, 40));

        assertEquals(EvalRun.Verdict.UNEXPECTED, result.verdict());
    }

    @Test
    @DisplayName("负样本失败 = 如期望（不是「异常」）")
    void negativeSampleFailed() {
        EvalRun result = run(EvalCase.ExpectedOutcome.FAILED, "FAILED", metrics(1, 1, 5, 0));

        assertEquals(EvalRun.Verdict.AS_EXPECTED, result.verdict());
        assertTrue(result.isNegative());
    }

    @Test
    @DisplayName("负样本居然成功 = 与期望不符")
    void negativeSampleUnexpectedlySucceeded() {
        EvalRun result = run(EvalCase.ExpectedOutcome.FAILED, "SUCCEEDED", metrics(1, 1, 5, 5));

        assertEquals(EvalRun.Verdict.UNEXPECTED, result.verdict());
    }

    @Test
    @DisplayName("被取消不是「如期望」，即使期望是失败")
    void cancelledIsNotAsExpected() {
        EvalRun result = run(EvalCase.ExpectedOutcome.FAILED, "CANCELLED", null);

        assertEquals(EvalRun.Verdict.UNEXPECTED, result.verdict(),
                "取消是「没跑完」，与「跑完但没通过」是两件事");
    }

    @Test
    @DisplayName("harness 故障单列，不算任务失败")
    void harnessErrorIsIsolated() {
        EvalRun result = EvalRun.harnessError(
                new EvalCase("case-x", "examples/eval/x", "src/main/java/X.java", 21,
                        EvalCase.ExpectedOutcome.SUCCEEDED, true, "标题", List.of(), null),
                1_234L, "提交任务失败: Connection refused");

        assertEquals(EvalRun.Verdict.HARNESS_ERROR, result.verdict());
        assertFalse(result.usable(), "harness 故障的数据不该进聚合");
        assertEquals(1_234L, result.wallClockMs());
        assertEquals("提交任务失败: Connection refused", result.harnessError());
    }

    @Test
    @DisplayName("没拿到状态也算 harness 故障")
    void missingStatusIsHarnessError() {
        assertEquals(EvalRun.Verdict.HARNESS_ERROR,
                run(EvalCase.ExpectedOutcome.SUCCEEDED, null, null).verdict());
    }

    // ------------------------------------------------------------------
    // 派生判据
    // ------------------------------------------------------------------

    @Test
    @DisplayName("编译全通过：分母 0 时给 false，不能算满分")
    void allCompiledRequiresNonZeroDenominator() {
        assertTrue(run(EvalCase.ExpectedOutcome.SUCCEEDED, "SUCCEEDED", metrics(4, 4, 10, 10)).allCompiled());
        assertFalse(run(EvalCase.ExpectedOutcome.SUCCEEDED, "SUCCEEDED", metrics(4, 3, 10, 10)).allCompiled());
        assertFalse(run(EvalCase.ExpectedOutcome.SUCCEEDED, "SUCCEEDED", metrics(0, 0, 0, 0)).allCompiled(),
                "没有文件可编译 ≠ 编译全过");
        assertFalse(run(EvalCase.ExpectedOutcome.SUCCEEDED, "SUCCEEDED", null).allCompiled());
    }

    @Test
    @DisplayName("单测样本有无，决定报告里显示比率还是「无样本」")
    void hasTestSamplesDistinguishesEmptyDenominator() {
        assertTrue(run(EvalCase.ExpectedOutcome.SUCCEEDED, "SUCCEEDED", metrics(1, 1, 5, 3)).hasTestSamples());
        assertFalse(run(EvalCase.ExpectedOutcome.SUCCEEDED, "SUCCEEDED", metrics(1, 1, 0, 0)).hasTestSamples());
        assertFalse(run(EvalCase.ExpectedOutcome.SUCCEEDED, "SUCCEEDED", null).hasTestSamples());
    }

    @Test
    @DisplayName("verifyAttempts > 1 才算发生过回退重写")
    void retriedOnlyWhenVerifyAttemptsExceedsOne() {
        TaskMetrics firstTry = new TaskMetrics(1, 1, 5, 5, 0.5d, 2, 100L, 50L, 0.01d, 1, 1_000L);
        TaskMetrics secondTry = new TaskMetrics(1, 1, 5, 5, 0.5d, 4, 200L, 100L, 0.02d, 2, 2_000L);

        assertFalse(run(EvalCase.ExpectedOutcome.SUCCEEDED, "SUCCEEDED", firstTry).retried());
        assertTrue(run(EvalCase.ExpectedOutcome.SUCCEEDED, "SUCCEEDED", secondTry).retried());
        assertFalse(run(EvalCase.ExpectedOutcome.SUCCEEDED, "SUCCEEDED", null).retried(),
                "没有指标数据时不能默认「重试过」");
    }

    @Test
    @DisplayName("runDurationMs 为 null 表示「不知道」，与 0 必须区分")
    void nullRunDurationMeansUnknown() {
        EvalRun unknown = run(EvalCase.ExpectedOutcome.SUCCEEDED, "SUCCEEDED", metrics(1, 1, 1, 1));
        assertTrue(unknown.runDurationMs() == null, "没有 trace 数据时必须是 null，不能填 0");

        EvalRun known = run(EvalCase.ExpectedOutcome.SUCCEEDED, "SUCCEEDED", metrics(1, 1, 1, 1), 45_000L);
        assertEquals(45_000L, known.runDurationMs());
    }
}
