package com.remasteragent.eval.report;

import com.remasteragent.eval.run.EvalRun;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleFunction;

/**
 * 把 {@link EvalReport} 渲染成一份自包含的 HTML 报告。
 *
 * <p>与 {@link MarkdownReport} 同构：聚合在 {@code EvalReport} 里、渲染在这里，
 * 纯字符串拼接、零 IO、零网络。模板可以随便改，改完重跑 {@code report} 子命令即可，
 * 不用重烧模型 token —— 这是 {@code run} 与 {@code report} 拆成两条命令的意义。
 *
 * <p>HTML 是自包含的：内联 CSS、无外链、无 JS 依赖，双击就能在浏览器打开，
 * 也方便作为作品集附件直接发给面试官。配色走「红涨绿跌」之外的工程惯例：
 * 通过=绿、未通过=红、harness 故障=灰，与前端指标面板的语义一致。
 *
 * <h2>两条纪律（与 Markdown 版相同）</h2>
 * <ol>
 *   <li><b>每个数字都要能追问。</b>明细表带 {@code taskId}，任何一个数都能顺着它回到
 *       {@code trace_span} / {@code llm_call} / {@code patch}。</li>
 *   <li><b>分母永远写在旁边。</b>进度条旁是 {@code 51/54 (94.4%)} 而不是孤零零的 {@code 94.4%}；
 *       没有样本时显示「无样本」，绝不伪装成 {@code 0%}。</li>
 * </ol>
 */
public final class HtmlReport {

    private HtmlReport() {
    }

    public static String render(EvalReport report, String runId) {
        StringBuilder out = new StringBuilder();
        out.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
                .append("<title>RemasterAgent 迁移评测报告 — ").append(esc(runId)).append("</title>\n")
                .append("<style>\n").append(STYLE).append("</style>\n</head>\n<body>\n");

        header(out, report, runId);
        summaryCards(out, report);
        overview(out, report);
        quality(out, report);
        costAndDuration(out, report);
        retries(out, report);
        byPattern(out, report);
        byProject(out, report);
        details(out, report);
        footer(out, runId);

        out.append("\n</body>\n</html>\n");
        return out.toString();
    }

    // ------------------------------------------------------------------
    // 段
    // ------------------------------------------------------------------

    private static void header(StringBuilder out, EvalReport report, String runId) {
        out.append("<header>\n<h1>RemasterAgent 迁移评测报告</h1>\n")
                .append("<div class=\"meta\">运行 id: <code>").append(esc(runId)).append("</code>")
                .append(" · 生成于 ").append(OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append(" · 用例 ").append(report.total()).append(" 条</div>\n</header>\n");
    }

    private static void summaryCards(StringBuilder out, EvalReport report) {
        out.append("<section class=\"cards\">\n");
        card(out, "如期望率", ratioBig(report.asExpected()), ratioSub(report.asExpected()), tone(report.asExpected()));
        card(out, "正样本成功率", ratioBig(report.positiveSuccess()), ratioSub(report.positiveSuccess()), tone(report.positiveSuccess()));
        card(out, "编译通过率", ratioBig(report.compile()), ratioSub(report.compile()), tone(report.compile()));
        card(out, "单测通过率", ratioBig(report.tests()), ratioSub(report.tests()), tone(report.tests()));
        card(out, "总成本",
                report.llmCalls() == 0 ? "无样本" : String.format(Locale.ROOT, "¥%.2f", report.totalCost()),
                report.llmCalls() == 0 ? "本轮无指标" : report.llmCalls() + " 次调用 / " + report.totalTokens() + " token",
                "neutral");
        out.append("</section>\n");
    }

    private static void overview(StringBuilder out, EvalReport report) {
        out.append("<section><h2>一、总览</h2>\n");
        out.append("<table class=\"kv\">\n");
        kv(out, "用例总数", String.valueOf(report.total()));
        kv(out, "数据可用（harness 没出故障）", String.valueOf(report.usable()));
        kv(out, "harness 故障（不计入任何比率）", String.valueOf(report.harnessErrors()));
        kv(out, "拿到指标", String.valueOf(report.withMetrics()));
        out.append("</table>\n");
        out.append("<div class=\"badges\">\n");
        badge(out, "结局与期望一致", report.asExpected(), "ok");
        badge(out, "正样本成功率（能力指标）", report.positiveSuccess(), "ok");
        badge(out, "负样本如期失败率（护栏指标）", report.negativeCaught(), "warn");
        out.append("</div>\n");
        out.append("<p class=\"note\">「如期望」含负样本，会因「负样本被改好了」而变低；「正样本成功率」只看该成功的那些。"
                + "两个数分开给，是因为混在一起会让「护栏没拦住」被能力指标盖过去。</p>\n");
        out.append("</section>\n");
    }

    private static void quality(StringBuilder out, EvalReport report) {
        out.append("<section><h2>二、改写质量</h2>\n");
        out.append("<table class=\"kv\">\n");
        kvRatio(out, "编译通过率（按文件数加权）", report.compile());
        kvRatio(out, "单测通过率（按用例数加权）", report.tests());
        kvRatio(out, "一次通过率（VERIFY 只跑 1 轮）", report.firstTry());
        out.append("</table>\n");
        stat(out, "覆盖率", report.coverage(), HtmlReport::percent);
        out.append("</section>\n");
    }

    private static void costAndDuration(StringBuilder out, EvalReport report) {
        out.append("<section><h2>三、成本与耗时</h2>\n");
        out.append("<table class=\"kv\">\n");
        kv(out, "LLM 调用次数", String.valueOf(report.llmCalls()));
        kv(out, "token 总量（提示 + 完成）", String.valueOf(report.totalTokens()));
        kv(out, "总成本（元）", report.llmCalls() == 0 ? "无样本" : String.format(Locale.ROOT, "%.4f", report.totalCost()));
        kv(out, "单条用例成本", report.costPerCase().text(d -> String.format(Locale.ROOT, "%.4f", d)));
        out.append("</table>\n");
        stat(out, "实际运行（trace 口径，不含排队）", report.runDuration(), HtmlReport::seconds);
        stat(out, "端到端（任务创建到终态）", report.endToEndDuration(), HtmlReport::seconds);
        stat(out, "harness 墙钟（含排队）", report.wallClock(), HtmlReport::seconds);
        out.append("<p class=\"note\">三个数按理应当依次递增。若「实际运行」大于「端到端」，说明 trace 跨了多次运行"
                + "（取消后重跑会产生多条 trace），需要按运行分组看，而不是直接相减。</p>\n");
        out.append("</section>\n");
    }

    private static void retries(StringBuilder out, EvalReport report) {
        out.append("<section><h2>四、回退与收敛</h2>\n");
        out.append("<table class=\"kv\">\n");
        kv(out, "发生过回退重写的用例", String.valueOf(report.retriedCases()) + " 条");
        kv(out, "VERIFY 轮次（分布）", report.verifyAttempts().text(HtmlReport::oneDecimal));
        out.append("</table>\n");
        out.append("<p class=\"note\">轮次分布会被 <code>max-rewrite-attempts</code> 封顶。若它随用例数一路上升，"
                + "说明上限没生效 —— 那既是成本问题，也是「失败不收敛」的信号。</p>\n");
        out.append("</section>\n");
    }

    private static void byPattern(StringBuilder out, EvalReport report) {
        out.append("<section><h2>五、按遗留模式分组</h2>\n");
        Map<String, EvalReport.PatternGroup> groups = report.byPattern();
        if (groups.isEmpty()) {
            out.append("<p class=\"note\">无样本</p>\n");
            return;
        }
        out.append("<table class=\"grid\">\n<thead><tr><th>模式</th><th>用例数</th><th>如期望</th></tr></thead>\n<tbody>\n");
        groups.forEach((pattern, group) -> out.append("<tr><td><code>").append(esc(pattern)).append("</code></td><td>")
                .append(group.cases()).append("</td><td>").append(ratioCell(group.asExpected())).append("</td></tr>\n"));
        out.append("</tbody></table>\n");
        out.append("<p class=\"note\">样本量小的分组只能当线索，不能当结论 —— 分母写在表里就是提醒这件事。</p>\n");
        out.append("</section>\n");
    }

    private static void byProject(StringBuilder out, EvalReport report) {
        out.append("<section><h2>六、按工程分组</h2>\n");
        out.append("<table class=\"grid\">\n<thead><tr><th>工程</th><th>用例数</th><th>如期望</th></tr></thead>\n<tbody>\n");
        report.byProject().forEach((project, group) -> out.append("<tr><td><code>").append(esc(project)).append("</code></td><td>")
                .append(group.cases()).append("</td><td>").append(ratioCell(group.asExpected())).append("</td></tr>\n"));
        out.append("</tbody></table>\n</section>\n");
    }

    private static void details(StringBuilder out, EvalReport report) {
        out.append("<section><h2>七、逐条明细</h2>\n");
        out.append("<div class=\"scroll\"><table class=\"grid detail\">\n<thead><tr>")
                .append("<th>用例</th><th>标题</th><th>期望</th><th>实际</th><th>判定</th>")
                .append("<th>taskId</th><th>编译</th><th>单测</th><th>覆盖率</th><th>成本</th><th>轮次</th>")
                .append("</tr></thead>\n<tbody>\n");
        for (EvalRun run : report.runs()) {
            String vClass = switch (run.verdict()) {
                case AS_EXPECTED -> "ok";
                case UNEXPECTED -> "bad";
                case HARNESS_ERROR -> "err";
            };
            String vLabel = switch (run.verdict()) {
                case AS_EXPECTED -> "如期望";
                case UNEXPECTED -> "与期望不符";
                case HARNESS_ERROR -> "harness 故障";
            };
            out.append("<tr>")
                    .append("<td><code>").append(esc(run.caseId())).append("</code></td>")
                    .append("<td class=\"title\">").append(esc(run.title())).append("</td>")
                    .append("<td>").append(esc(run.expect().name())).append("</td>")
                    .append("<td>").append(run.status() == null ? "—" : esc(run.status())).append("</td>")
                    .append("<td><span class=\"badge ").append(vClass).append("\">").append(vLabel).append("</span></td>")
                    .append("<td>").append(run.taskId() == null ? "—" : String.valueOf(run.taskId())).append("</td>")
                    .append("<td>").append(metricCell(run, true)).append("</td>")
                    .append("<td>").append(metricCell(run, false)).append("</td>")
                    .append("<td>").append(coverageCell(run)).append("</td>")
                    .append("<td>").append(run.metrics() == null ? "—"
                            : String.format(Locale.ROOT, "%.4f", run.metrics().totalCost())).append("</td>")
                    .append("<td>").append(run.metrics() == null ? "—" : String.valueOf(run.metrics().verifyAttempts()))
                    .append("</td>")
                    .append("</tr>\n");
        }
        out.append("</tbody></table></div>\n");
        out.append("<p class=\"note\">判定为「harness 故障」的行不进任何比率 —— 工具出问题不是 Agent 的失败，"
                + "但必须被看见，否则「这一轮跑得不错」可能只是「有一半没跑起来」。</p>\n");
        out.append("</section>\n");
    }

    private static void footer(StringBuilder out, String runId) {
        out.append("<footer>\n<p>本报告由评测 harness 的 <code>report</code> 子命令渲染。"
                + "聚合口径（分母、加权、无样本处理）全部锁在 <code>EvalReport</code> 里，渲染层只负责摆出来。"
                + "任一个数字都可顺着明细表的 <code>taskId</code> 回到 <code>trace_span</code> / "
                + "<code>llm_call</code> / <code>patch</code> 复盘。</p>\n")
                .append("<p class=\"muted\">运行 id: ").append(esc(runId)).append("</p>\n</footer>\n");
    }

    // ------------------------------------------------------------------
    // 小组件
    // ------------------------------------------------------------------

    private static void card(StringBuilder out, String label, String big, String sub, String tone) {
        out.append("<div class=\"card tone-").append(tone).append("\">")
                .append("<div class=\"card-label\">").append(esc(label)).append("</div>")
                .append("<div class=\"card-big\">").append(big).append("</div>")
                .append("<div class=\"card-sub\">").append(esc(sub)).append("</div>")
                .append("</div>\n");
    }

    private static void kv(StringBuilder out, String label, String value) {
        out.append("<tr><td>").append(esc(label)).append("</td><td>").append(esc(value)).append("</td></tr>\n");
    }

    private static void kvRatio(StringBuilder out, String label, Ratio ratio) {
        out.append("<tr><td>").append(esc(label)).append("</td><td>").append(ratioCell(ratio)).append("</td></tr>\n");
    }

    private static void badge(StringBuilder out, String label, Ratio ratio, String tone) {
        out.append("<div class=\"badge-row\"><span class=\"badge ").append(tone).append("\">")
                .append(esc(label)).append("</span><span class=\"badge-val\">")
                .append(esc(ratio.text())).append("</span></div>\n");
    }

    private static void stat(StringBuilder out, String label, Stat stat, DoubleFunction<String> fmt) {
        out.append("<div class=\"stat\"><span class=\"stat-label\">").append(esc(label)).append("</span>");
        if (stat.hasSamples()) {
            out.append("<span class=\"stat-main\">").append(esc(stat.text(fmt))).append("</span>")
                    .append("<span class=\"stat-sub\">").append(stat.samples()).append(" 个样本 · 区间 [")
                    .append(fmt.apply(stat.min())).append(", ").append(fmt.apply(stat.max())).append("]</span>");
        } else {
            out.append("<span class=\"stat-main na\">无样本</span>");
        }
        out.append("</div>\n");
    }

    /** 比率进度条 + 文本。无样本显示「无样本」。 */
    private static String ratioCell(Ratio r) {
        if (!r.hasSamples()) {
            return "<span class=\"na\">无样本</span>";
        }
        double pct = 100.0d * r.numerator() / r.denominator();
        String tone = pct >= 90.0d ? "good" : pct >= 60.0d ? "mid" : "bad";
        return "<span class=\"ratio\"><span class=\"track\"><span class=\"fill " + tone
                + "\" style=\"width:" + pct + "%\"></span></span>"
                + "<span class=\"ratio-text\">" + r.numerator() + "/" + r.denominator()
                + " (" + String.format(Locale.ROOT, "%.1f", pct) + "%)</span></span>";
    }

    private static String ratioBig(Ratio r) {
        if (!r.hasSamples()) {
            return "无样本";
        }
        return String.format(Locale.ROOT, "%.1f%%", 100.0d * r.numerator() / r.denominator());
    }

    private static String ratioSub(Ratio r) {
        return r.hasSamples() ? (r.numerator() + "/" + r.denominator() + " 通过") : "本轮无样本";
    }

    private static String tone(Ratio r) {
        if (!r.hasSamples()) {
            return "neutral";
        }
        double pct = 100.0d * r.numerator() / r.denominator();
        return pct >= 90.0d ? "good" : pct >= 60.0d ? "mid" : "bad";
    }

    private static String metricCell(EvalRun run, boolean compile) {
        if (run.metrics() == null) {
            return "—";
        }
        return compile
                ? run.metrics().compilePassed() + "/" + run.metrics().filesTotal()
                : run.metrics().testsPassed() + "/" + run.metrics().testsTotal();
    }

    private static String coverageCell(EvalRun run) {
        if (run.metrics() == null) {
            return "—";
        }
        double cov = run.metrics().coverage();
        double pct = 100.0d * cov;
        String tone = pct >= 80.0d ? "good" : pct >= 50.0d ? "mid" : "bad";
        return "<span class=\"ratio\"><span class=\"track\"><span class=\"fill " + tone
                + "\" style=\"width:" + pct + "%\"></span></span>"
                + "<span class=\"ratio-text\">" + String.format(Locale.ROOT, "%.1f%%", pct) + "</span></span>";
    }

    // ------------------------------------------------------------------
    // 格式化
    // ------------------------------------------------------------------

    private static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.1f%%", 100.0d * fraction);
    }

    private static String seconds(double millis) {
        return String.format(Locale.ROOT, "%.1fs", millis / 1000.0d);
    }

    private static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    // ------------------------------------------------------------------
    // 内联样式
    // ------------------------------------------------------------------

    private static final String STYLE = """
            :root {
              --bg: #f6f8fa; --panel: #ffffff; --ink: #1f2328; --muted: #656d76;
              --line: #d0d7de; --good: #1a7f37; --good-bg: #dafbe1; --mid: #9a6700;
              --mid-bg: #fff8c5; --bad: #cf222e; --bad-bg: #ffebe9; --err: #6e7781; --err-bg: #eaeef2;
              --accent: #0969da;
            }
            * { box-sizing: border-box; }
            body { margin: 0; background: var(--bg); color: var(--ink);
              font: 14px/1.6 -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", Roboto, Helvetica, Arial, sans-serif; }
            header { padding: 28px 32px 12px; }
            h1 { margin: 0 0 6px; font-size: 22px; }
            .meta { color: var(--muted); font-size: 13px; }
            code { background: #eaeef2; padding: 1px 5px; border-radius: 4px; font-size: 12px; }
            section { padding: 8px 32px 16px; }
            h2 { font-size: 16px; border-left: 4px solid var(--accent); padding-left: 10px; margin: 22px 0 12px; }
            .cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
              gap: 14px; padding: 16px 32px; }
            .card { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 16px; }
            .card-label { color: var(--muted); font-size: 12px; }
            .card-big { font-size: 28px; font-weight: 700; margin: 4px 0; }
            .card-sub { color: var(--muted); font-size: 12px; }
            .tone-good .card-big { color: var(--good); }
            .tone-mid .card-big { color: var(--mid); }
            .tone-bad .card-big { color: var(--bad); }
            .tone-neutral .card-big { color: var(--ink); }
            table.kv { border-collapse: collapse; width: 100%; max-width: 720px; background: var(--panel);
              border: 1px solid var(--line); border-radius: 8px; overflow: hidden; }
            table.kv td { padding: 8px 14px; border-bottom: 1px solid var(--line); }
            table.kv tr:last-child td { border-bottom: none; }
            table.kv td:first-child { color: var(--muted); width: 60%; }
            table.grid { border-collapse: collapse; width: 100%; background: var(--panel);
              border: 1px solid var(--line); border-radius: 8px; overflow: hidden; margin-top: 6px; }
            table.grid th, table.grid td { padding: 8px 12px; border-bottom: 1px solid var(--line); text-align: left; }
            table.grid th { background: #f0f3f6; font-weight: 600; font-size: 13px; position: sticky; top: 0; }
            table.grid tbody tr:hover { background: #f6fafd; }
            .detail td.title { color: var(--muted); max-width: 320px; }
            .scroll { overflow-x: auto; }
            .badges { display: flex; flex-wrap: wrap; gap: 10px; margin: 12px 0; }
            .badge-row { display: flex; align-items: center; gap: 8px; background: var(--panel);
              border: 1px solid var(--line); border-radius: 999px; padding: 4px 12px; }
            .badge { display: inline-block; padding: 2px 10px; border-radius: 999px; font-size: 12px; font-weight: 600; white-space: nowrap; }
            .badge.ok { color: var(--good); background: var(--good-bg); }
            .badge.warn { color: var(--mid); background: var(--mid-bg); }
            .badge.bad { color: var(--bad); background: var(--bad-bg); }
            .badge.err { color: var(--err); background: var(--err-bg); }
            .badge-val { font-variant-numeric: tabular-nums; }
            .ratio { display: inline-flex; align-items: center; gap: 8px; }
            .track { width: 90px; height: 8px; background: #eaeef2; border-radius: 999px; overflow: hidden; display: inline-block; }
            .fill { display: block; height: 100%; border-radius: 999px; }
            .fill.good { background: var(--good); }
            .fill.mid { background: var(--mid); }
            .fill.bad { background: var(--bad); }
            .ratio-text { font-variant-numeric: tabular-nums; font-size: 12px; }
            .na { color: var(--muted); }
            .stat { display: flex; flex-direction: column; gap: 2px; padding: 8px 14px; margin: 6px 0;
              background: var(--panel); border: 1px solid var(--line); border-radius: 8px; max-width: 720px; }
            .stat-label { color: var(--muted); font-size: 12px; }
            .stat-main { font-variant-numeric: tabular-nums; font-weight: 600; }
            .stat-sub { color: var(--muted); font-size: 12px; }
            .note { color: var(--muted); font-size: 12px; background: #f0f3f6; border-radius: 6px; padding: 8px 12px; margin: 10px 0; }
            footer { padding: 20px 32px 40px; color: var(--muted); font-size: 12px; border-top: 1px solid var(--line); margin-top: 20px; }
            .muted { color: var(--muted); }
            @media print { body { background: #fff; } .card, table, .stat { break-inside: avoid; } }
            """;
}
