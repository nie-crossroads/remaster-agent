package com.remasteragent.eval.report;

import com.remasteragent.eval.run.EvalRun;

import java.util.Locale;
import java.util.Map;

/**
 * 把 {@link EvalReport} 渲染成 Markdown。
 *
 * <p>与聚合刻意分开：聚合回答「数是多少」，渲染回答「怎么摆」。
 * 报告模板总是要改很多遍的，把它做成纯字符串拼接，改模板就不用重跑评测、
 * 不用重新烧 token —— 这是 {@code run} 与 {@code report} 分成两个命令的意义所在。
 *
 * <h2>两条排版纪律</h2>
 * <ol>
 *   <li><b>每个数字都要能追问。</b>明细表里带 {@code taskId}，
 *       任何一个数都能顺着它回到 {@code trace_span} / {@code llm_call} / {@code patch}。
 *       只给汇总数的报告是不可质疑的，而不可质疑的报告没有价值。</li>
 *   <li><b>分母永远写在旁边。</b>{@code 51/54 (94.4%)} 而不是 {@code 94.4%} ——
 *       3 个失败的样本是 51 个里面的 3 个，还是 3 个里面的 3 个，结论完全不同。</li>
 * </ol>
 */
public final class MarkdownReport {

    private MarkdownReport() {
    }

    public static String render(EvalReport report, String runId) {
        StringBuilder out = new StringBuilder();
        out.append("# RemasterAgent 迁移评测报告\n\n");
        out.append("运行 id: `").append(runId).append("`\n\n");

        overview(out, report);
        quality(out, report);
        costAndDuration(out, report);
        retries(out, report);
        byPattern(out, report);
        byProject(out, report);
        details(out, report);

        return out.toString();
    }

    // ------------------------------------------------------------------
    // 各段
    // ------------------------------------------------------------------

    private static void overview(StringBuilder out, EvalReport report) {
        out.append("## 一、总览\n\n");
        out.append("| 指标 | 值 |\n|---|---|\n");
        row(out, "用例总数", report.total());
        row(out, "数据可用（harness 没出故障）", report.usable());
        row(out, "harness 故障（不计入任何比率）", report.harnessErrors());
        row(out, "拿到指标", report.withMetrics());
        out.append('\n');

        out.append("- **结局与期望一致**：").append(report.asExpected().text()).append('\n');
        out.append("- **正样本成功率（能力指标）**：").append(report.positiveSuccess().text()).append('\n');
        out.append("- **负样本如期失败率（护栏指标）**：").append(report.negativeCaught().text()).append('\n');
        out.append('\n');
        out.append("> 「如期望」含负样本，会因「负样本被改好了」而变低；\n");
        out.append("> 「正样本成功率」只看该成功的那些。两个数分开给，是因为混在一起会让\n");
        out.append("> 「护栏没拦住」这种问题被能力指标盖过去。\n\n");
    }

    private static void quality(StringBuilder out, EvalReport report) {
        out.append("## 二、改写质量\n\n");
        out.append("| 指标 | 值 |\n|---|---|\n");
        row(out, "编译通过率（按文件数加权）", report.compile().text());
        row(out, "单测通过率（按用例数加权）", report.tests().text());
        row(out, "一次通过率（VERIFY 只跑 1 轮）", report.firstTry().text());
        out.append('\n');
        out.append("- **覆盖率**：").append(report.coverage().text(MarkdownReport::percent)).append('\n');
        out.append('\n');
    }

    private static void costAndDuration(StringBuilder out, EvalReport report) {
        out.append("## 三、成本与耗时\n\n");
        out.append("| 指标 | 值 |\n|---|---|\n");
        row(out, "LLM 调用次数", report.llmCalls());
        row(out, "token 总量（提示 + 完成）", report.totalTokens());
        row(out, "总成本（元）", String.format(Locale.ROOT, "%.4f", report.totalCost()));
        row(out, "单条用例成本", report.costPerCase().text(d -> String.format(Locale.ROOT, "%.4f", d)));
        out.append('\n');

        out.append("- **实际运行（trace 口径，不含排队）**：").append(report.runDuration().text(MarkdownReport::seconds)).append('\n');
        out.append("- **端到端（任务创建到终态）**：").append(report.endToEndDuration().text(MarkdownReport::seconds)).append('\n');
        out.append("- **harness 墙钟（含排队）**：").append(report.wallClock().text(MarkdownReport::seconds)).append('\n');
        out.append('\n');
        out.append("> 三个数按理应当依次递增。若「实际运行」大于「端到端」，说明 trace 跨了多次运行\n");
        out.append("> （取消后重跑会产生多条 trace），需要按运行分组看，而不是直接相减。\n\n");
    }

    private static void retries(StringBuilder out, EvalReport report) {
        out.append("## 四、回退与收敛\n\n");
        out.append("- 发生过回退重写的用例：").append(report.retriedCases()).append(" 条\n");
        out.append("- VERIFY 轮次：").append(report.verifyAttempts().text(MarkdownReport::oneDecimal)).append('\n');
        out.append('\n');
        out.append("> 轮次的分布会被 `max-rewrite-attempts` 封顶。若它随用例数一路上升，\n");
        out.append("> 说明上限没生效 —— 那既是成本问题，也是「失败不收敛」的信号。\n\n");
    }

    private static void byPattern(StringBuilder out, EvalReport report) {
        out.append("## 五、按遗留模式分组\n\n");
        Map<String, EvalReport.PatternGroup> groups = report.byPattern();
        if (groups.isEmpty()) {
            out.append("无样本\n\n");
            return;
        }
        out.append("| 模式 | 用例数 | 如期望 |\n|---|---|---|\n");
        groups.forEach((pattern, group) -> out.append("| `").append(pattern).append("` | ")
                .append(group.cases()).append(" | ").append(group.asExpected().text()).append(" |\n"));
        out.append('\n');
        out.append("> 样本量小的分组只能当线索，不能当结论 —— 分母写在表里就是提醒这件事。\n\n");
    }

    private static void byProject(StringBuilder out, EvalReport report) {
        out.append("## 六、按工程分组\n\n");
        out.append("| 工程 | 用例数 | 如期望 |\n|---|---|---|\n");
        report.byProject().forEach((project, group) -> out.append("| `").append(project).append("` | ")
                .append(group.cases()).append(" | ").append(group.asExpected().text()).append(" |\n"));
        out.append("\n");
    }

    private static void details(StringBuilder out, EvalReport report) {
        out.append("## 七、逐条明细\n\n");
        out.append("| 用例 | 期望 | 实际 | 判定 | taskId | 编译 | 单测 | 覆盖率 | 成本 | 轮次 |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (EvalRun run : report.runs()) {
            out.append('|').append(run.caseId())
                    .append(" | ").append(run.expect())
                    .append(" | ").append(run.status() == null ? "—" : run.status())
                    .append(" | ").append(verdictText(run))
                    .append(" | ").append(run.taskId() == null ? "—" : run.taskId())
                    .append(" | ").append(metricText(run))
                    .append(" | ").append(costText(run))
                    .append(" | ").append(run.metrics() == null ? "—" : run.metrics().verifyAttempts())
                    .append(" |\n");
        }
        out.append('\n');
        out.append("> 判定为「harness 故障」的行不进任何比率 —— 工具出问题不是 Agent 的失败，\n");
        out.append("> 但必须被看见，否则「这一轮跑得不错」可能只是「有一半没跑起来」。\n");
    }

    // ------------------------------------------------------------------
    // 格式化
    // ------------------------------------------------------------------

    private static String metricText(EvalRun run) {
        if (run.metrics() == null) {
            return "— | — | —";
        }
        return run.metrics().compilePassed() + "/" + run.metrics().filesTotal()
                + " | " + run.metrics().testsPassed() + "/" + run.metrics().testsTotal()
                + " | " + percent(run.metrics().coverage());
    }

    private static String costText(EvalRun run) {
        return run.metrics() == null ? "—" : String.format(Locale.ROOT, "%.4f", run.metrics().totalCost());
    }

    private static String verdictText(EvalRun run) {
        return switch (run.verdict()) {
            case AS_EXPECTED -> "如期望";
            case UNEXPECTED -> "**与期望不符**";
            case HARNESS_ERROR -> "harness 故障";
        };
    }

    private static void row(StringBuilder out, String label, Object value) {
        out.append("| ").append(label).append(" | ").append(value).append(" |\n");
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", 100.0d * value);
    }

    private static String seconds(double millis) {
        return String.format(Locale.ROOT, "%.1fs", millis / 1000.0d);
    }

    private static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

}
