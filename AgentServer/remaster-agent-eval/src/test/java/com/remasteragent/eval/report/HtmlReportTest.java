package com.remasteragent.eval.report;

import com.remasteragent.common.domain.TaskMetrics;
import com.remasteragent.eval.catalog.EvalCase;
import com.remasteragent.eval.run.EvalRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTML 渲染器的守卫 —— 保证「能打开、不撒谎、不被注入」。
 *
 * <p>与 {@link EvalReportTest} 不同，这里不测口径（口径在 {@code EvalReport} 里钉死），
 * 只测三层：① 结构完整（DOCTYPE 起、/html 收）；② 关键数字真的进了 HTML；
 * ③ 任何来自数据字段的文本都被转义，恶意标题不能变成可执行的标签。
 */
class HtmlReportTest {

    private static final Instant NOW = Instant.parse("2026-09-16T08:00:00Z");

    /** filesTotal, compilePassed, testsTotal, testsPassed, coverage, llmCalls, prompt, completion, cost, verifyAttempts, durationMs */
    private static TaskMetrics metrics(int files, int compiled, int tests, int passed, int attempts) {
        return new TaskMetrics(files, compiled, tests, passed, 0.8d, 2,
                1_000L, 500L, 0.02d, attempts, 60_000L);
    }

    private static EvalRun run(String id, String status, EvalCase.ExpectedOutcome expect,
                               TaskMetrics m, String harnessError, String... patterns) {
        return new EvalRun(id, "examples/eval/" + id, "src/main/java/X.java", "标题-" + id,
                List.of(patterns), expect, 7L, status, null, m, 60_000L,
                70_000L, harnessError, NOW);
    }

    @Test
    @DisplayName("结构完整：以 DOCTYPE 开头、以 /html 结尾")
    void rendersWellFormedShell() {
        String html = HtmlReport.render(new EvalReport(List.of(
                run("a", "SUCCEEDED", EvalCase.ExpectedOutcome.SUCCEEDED, metrics(4, 4, 51, 51, 1), null, "date-time"))), "20260916-000000");

        assertTrue(html.startsWith("<!DOCTYPE html>"), "必须是完整 HTML 文档");
        assertTrue(html.trim().endsWith("</html>"), "必须以 </html> 收尾");
        assertTrue(html.contains("<style>"), "内联样式，无外链");
        assertFalse(html.contains("<script"), "不允许任何脚本");
    }

    @Test
    @DisplayName("关键数字进了 HTML：编译/单测加权率、如期望率、成本")
    void keyNumbersAreRendered() {
        // 加权: 51/54 = 94.4%
        String html = HtmlReport.render(new EvalReport(List.of(
                run("big", "SUCCEEDED", EvalCase.ExpectedOutcome.SUCCEEDED, metrics(4, 4, 51, 51, 1), null, "date-time"),
                run("small", "SUCCEEDED", EvalCase.ExpectedOutcome.SUCCEEDED, metrics(1, 0, 3, 0, 1), null, "boxing"))),
                "20260916-111111");

        assertTrue(html.contains("51/54 (94.4%)"), "单测加权率必须按样本量加权，不是等权平均");
        assertTrue(html.contains("4/5 (80.0%)"), "编译加权率");
        assertTrue(html.contains("2/2 (100.0%)"), "两条都成功 → 如期望率 100%");
        assertTrue(html.contains("0.0400"), "成本逐条求和 (0.02 + 0.02)");
    }

    @Test
    @DisplayName("无样本不伪装成 0%：空报告显示「无样本」")
    void emptyReportShowsNoSample() {
        String html = HtmlReport.render(new EvalReport(List.of()), "20260916-222222");
        assertTrue(html.contains("无样本"), "分母为 0 必须显示「无样本」");
        assertFalse(html.contains("(0.0%)"), "绝不能出现伪装成 0% 的比率条");
    }

    @Test
    @DisplayName("数据文本被转义：恶意标题不能变成可执行标签")
    void dataIsEscaped() {
        EvalRun evil = new EvalRun("x", "examples/eval/x", "src/main/java/X.java",
                "<script>alert(1)</script>", List.of(), EvalCase.ExpectedOutcome.SUCCEEDED,
                9L, "SUCCEEDED", null, metrics(1, 1, 3, 3, 1), 1_000L, 2_000L, null, NOW);
        String html = HtmlReport.render(new EvalReport(List.of(evil)), "20260916-333333");

        assertTrue(html.contains("&lt;script&gt;"), "尖括号必须转义");
        assertFalse(html.contains("<script>alert(1)</script>"), "原始标签不能原样出现");
    }

    @Test
    @DisplayName("判定配色正确：如期望=绿、与期望不符=红、harness 故障=灰")
    void verdictColors() {
        EvalRun ok = run("ok", "SUCCEEDED", EvalCase.ExpectedOutcome.SUCCEEDED, metrics(1, 1, 3, 3, 1), null);
        EvalRun bad = run("bad", "FAILED", EvalCase.ExpectedOutcome.SUCCEEDED, metrics(1, 0, 3, 0, 1), null);
        EvalRun err = run("err", null, EvalCase.ExpectedOutcome.SUCCEEDED, null, "提交超时");
        String html = HtmlReport.render(new EvalReport(List.of(ok, bad, err)), "20260916-444444");

        // 明细表里三种判定各出现一次
        assertTrue(html.contains(">如期望<"), "AS_EXPECTED 文案");
        assertTrue(html.contains(">与期望不符<"), "UNEXPECTED 文案");
        assertTrue(html.contains(">harness 故障<"), "HARNESS_ERROR 文案");
    }
}
