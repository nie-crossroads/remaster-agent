package com.remasteragent.eval.report;

import com.remasteragent.common.domain.TaskMetrics;
import com.remasteragent.eval.run.EvalRun;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 把 {@code List<EvalRun>} 聚合成一份报告 —— <b>纯函数，无 IO</b>。
 *
 * <p>这是整个评测里最需要单测、也最容易单测的一层：给它一个 List 就能断言，
 * 不需要数据库、不需要模型、不需要 Maven。报告口径（分母是什么、加权还是平均、
 * 无样本该显示什么）全部锁在这一个文件里，渲染层只负责把它打出来。
 *
 * <h2>几个口径上的刻意选择</h2>
 * <ol>
 *   <li><b>「如期望」与「能力通过率」是两个不同的数。</b>前者含负样本，
 *       后者只看正样本。混在一起说会让「负样本全被判失败」这种好事把能力指标抬上去。</li>
 *   <li><b>编译 / 单测通过率按文件数、用例数加权，不是对每条用例的比率再平均。</b>
 *       对 51 个单测的用例和 3 个单测的用例取平均，等于让小样本和大样本等权 ——
 *       那不是通过率，那是「平均而言一条用例表现如何」。</li>
 *   <li><b>harness 故障不进任何分母。</b>API 抖了、解析炸了是工具的问题；
 *       把它算进成功率是往坏的方向自我欺骗。</li>
 *   <li><b>CANCELLED / 无 metrics 的用例不进编译与单测的分母</b>，
 *       但要单独计数 —— 「有多少条没拿到指标」本身是报告该回答的问题。</li>
 * </ol>
 */
public final class EvalReport {

    private final List<EvalRun> runs;

    public EvalReport(List<EvalRun> runs) {
        this.runs = List.copyOf(runs);
    }

    public List<EvalRun> runs() {
        return runs;
    }

    // ------------------------------------------------------------------
    // 判定
    // ------------------------------------------------------------------

    /** 总条数。 */
    public int total() {
        return runs.size();
    }

    /** 数据可用的条数（harness 没出故障）。 */
    public int usable() {
        return (int) runs.stream().filter(EvalRun::usable).count();
    }

    /** harness 自身故障的条数 —— 这些不进任何成功率。 */
    public int harnessErrors() {
        return (int) runs.stream().filter(r -> !r.usable()).count();
    }

    /** 拿到指标的条数；与 {@link #usable()} 的差就是「跑完了但没指标」。 */
    public int withMetrics() {
        return (int) runs.stream().filter(r -> r.metrics() != null).count();
    }

    /** 实际结局与期望一致的比率（含负样本）。 */
    public Ratio asExpected() {
        long hits = runs.stream().filter(r -> r.verdict() == EvalRun.Verdict.AS_EXPECTED).count();
        return new Ratio((int) hits, usable());
    }

    /** 正样本里真正跑成功的比率 —— 这才是「能力」指标。 */
    public Ratio positiveSuccess() {
        List<EvalRun> positives = positives();
        long hits = positives.stream().filter(r -> "SUCCEEDED".equals(r.status())).count();
        return new Ratio((int) hits, positives.size());
    }

    /** 负样本里被如期判失败的比率 —— 护栏 / 收敛能力的指标。 */
    public Ratio negativeCaught() {
        List<EvalRun> negatives = negatives();
        long hits = negatives.stream().filter(r -> "FAILED".equals(r.status())).count();
        return new Ratio((int) hits, negatives.size());
    }

    // ------------------------------------------------------------------
    // 编译 / 单测 / 覆盖率
    // ------------------------------------------------------------------

    /** 按文件数加权的编译通过率。 */
    public Ratio compile() {
        return weighted(metricsRuns(), TaskMetrics::filesTotal, TaskMetrics::compilePassed);
    }

    /** 按单测用例数加权的单测通过率。 */
    public Ratio tests() {
        return weighted(metricsRuns(), TaskMetrics::testsTotal, TaskMetrics::testsPassed);
    }

    /** 覆盖率的分布（逐条用例的覆盖率，不是汇总）。 */
    public Stat coverage() {
        return Stat.of(metricsRuns().stream().map(r -> (double) r.metrics().coverage()).toList());
    }

    // ------------------------------------------------------------------
    // 成本与耗时
    // ------------------------------------------------------------------

    /** LLM 调用总次数。 */
    public long llmCalls() {
        return metricsRuns().stream().mapToLong(r -> r.metrics().llmCalls()).sum();
    }

    /** 提示 + 完成的 token 总量。 */
    public long totalTokens() {
        return metricsRuns().stream()
                .mapToLong(r -> r.metrics().promptTokens() + r.metrics().completionTokens())
                .sum();
    }

    /** 总成本（元）。 */
    public double totalCost() {
        return metricsRuns().stream().mapToDouble(r -> r.metrics().totalCost()).sum();
    }

    /** 单条用例的平均成本；无样本时为空。 */
    public Stat costPerCase() {
        return Stat.of(metricsRuns().stream().map(r -> r.metrics().totalCost()).toList());
    }

    /** 实际运行时长（trace 口径）分布；没有 trace 数据的用例被忽略。 */
    public Stat runDuration() {
        return Stat.of(runs.stream().map(r -> box(r.runDurationMs())).toList());
    }

    /** 端到端时长（任务创建到终态）分布。 */
    public Stat endToEndDuration() {
        return Stat.of(metricsRuns().stream().map(r -> (double) r.metrics().durationMs()).toList());
    }

    /** harness 侧观测到的墙钟分布 —— 与端到端互为印证（差值就是排队时间）。 */
    public Stat wallClock() {
        return Stat.of(runs.stream().map(r -> (double) r.wallClockMs()).toList());
    }

    // ------------------------------------------------------------------
    // 回退与一次通过
    // ------------------------------------------------------------------

    /** 一次就通过（VERIFY 只跑了 1 轮）的比率。 */
    public Ratio firstTry() {
        List<EvalRun> scored = metricsRuns();
        long hits = scored.stream().filter(r -> r.metrics().verifyAttempts() <= 1).count();
        return new Ratio((int) hits, scored.size());
    }

    /** 发生过回退重写的条数。 */
    public int retriedCases() {
        return (int) metricsRuns().stream().filter(EvalRun::retried).count();
    }

    /** VERIFY 轮次的分布 —— 上限是否真的封顶，看这个数的 max。 */
    public Stat verifyAttempts() {
        return Stat.of(metricsRuns().stream().map(r -> (double) r.metrics().verifyAttempts()).toList());
    }

    // ------------------------------------------------------------------
    // 分组
    // ------------------------------------------------------------------

    /** 按遗留模式标签分组。标签按字典序排，保证报告可 diff。 */
    public Map<String, PatternGroup> byPattern() {
        Map<String, List<EvalRun>> grouped = new TreeMap<>();
        for (EvalRun run : runs) {
            for (String pattern : run.patterns()) {
                grouped.computeIfAbsent(pattern, k -> new ArrayList<>()).add(run);
            }
        }
        Map<String, PatternGroup> result = new LinkedHashMap<>();
        grouped.forEach((pattern, group) -> result.put(pattern,
                new PatternGroup(pattern, group.size(), new EvalReport(group).asExpected())));
        return result;
    }

    /** 按工程分组。 */
    public Map<String, ProjectGroup> byProject() {
        Map<String, List<EvalRun>> grouped = new TreeMap<>();
        for (EvalRun run : runs) {
            grouped.computeIfAbsent(run.project(), k -> new ArrayList<>()).add(run);
        }
        Map<String, ProjectGroup> result = new LinkedHashMap<>();
        grouped.forEach((project, group) -> result.put(project,
                new ProjectGroup(project, group.size(), new EvalReport(group).asExpected())));
        return result;
    }

    /** 单个模式标签下的表现。 */
    public record PatternGroup(String pattern, int cases, Ratio asExpected) {
    }

    /** 单个工程下的表现。 */
    public record ProjectGroup(String project, int cases, Ratio asExpected) {
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private List<EvalRun> positives() {
        return runs.stream().filter(r -> r.usable() && !r.isNegative()).toList();
    }

    private List<EvalRun> negatives() {
        return runs.stream().filter(r -> r.usable() && r.isNegative()).toList();
    }

    /** 拿到了指标的用例 —— 编译 / 单测 / 成本 / 回退都以它为分母。 */
    private List<EvalRun> metricsRuns() {
        return runs.stream().filter(r -> r.metrics() != null).toList();
    }

    /**
     * 加权比率：分子分母各自求和，而不是对每条用例的比率取平均。
     *
     * <p>分母为 0 时不要顺手 {@code max(1)} —— 那会把「没有样本」伪装成「0%」。
     */
    private static Ratio weighted(List<EvalRun> runs,
                                  java.util.function.ToIntFunction<TaskMetrics> denominator,
                                  java.util.function.ToIntFunction<TaskMetrics> numerator) {
        int total = runs.stream().mapToInt(r -> denominator.applyAsInt(r.metrics())).sum();
        if (total == 0) {
            return Ratio.EMPTY;
        }
        int passed = runs.stream().mapToInt(r -> Math.min(numerator.applyAsInt(r.metrics()),
                denominator.applyAsInt(r.metrics()))).sum();
        return new Ratio(passed, total);
    }

    private static Double box(Long value) {
        return value == null ? null : (double) value;
    }
}
