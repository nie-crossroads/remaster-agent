package com.remasteragent.eval.run;

import com.remasteragent.common.domain.TaskMetrics;
import com.remasteragent.eval.catalog.EvalCase;

import java.time.Instant;
import java.util.List;

/**
 * 一条用例跑完之后的原始结果 —— 报告的唯一输入。
 *
 * <p>这个 record 被刻意设计成<b>纯数据</b>：所有聚合都由
 * {@code EvalReport}（B3）从 {@code List<EvalRun>} 现算。
 * 好处是那批公式（加权通过率、P90 耗时、一次通过率）可以脱离数据库、
 * 脱离模型、脱离 Maven 被毫秒级单测钉住 —— 这恰恰是整个评测里最需要单测的部分。
 *
 * @param project      工程（相对仓库根）
 * @param expect       期望结局
 * @param taskId       任务 id；{@code null} 表示压根没提交成功。
 *                     <b>必须留着它不是可选项</b>：报告里任何一个数都要能顺着它回到
 *                     {@code trace_span} / {@code llm_call} / {@code patch} 去复盘。
 * @param status       任务终态；{@code null} 表示没拿到
 * @param metrics      指标；{@code null} 表示没有（任务未结束，或 CANCELLED 有意不写）
 * @param runDurationMs 实际运行时长；{@code null} = 没有 trace 数据。<b>不能当 0</b>
 * @param wallClockMs  harness 侧观测到的墙钟（含排队），与 {@code metrics.durationMs} 互为印证
 * @param harnessError harness 自身的失败原因；非空表示<b>这条数据不可用</b>，
 *                     与「任务失败」必须分开统计 —— 把工具故障算进成功率是自我欺骗
 */
public record EvalRun(
        String caseId,
        String project,
        String entryFile,
        String title,
        List<String> patterns,
        EvalCase.ExpectedOutcome expect,
        Long taskId,
        String status,
        String failReason,
        TaskMetrics metrics,
        Long runDurationMs,
        long wallClockMs,
        String harnessError,
        Instant recordedAt
) {

    /** 单条用例的判定结果。 */
    public enum Verdict {

        /** 实际结局与期望一致 —— 包括「负样本如期失败」。 */
        AS_EXPECTED,

        /** 实际结局与期望不一致。 */
        UNEXPECTED,

        /** harness 自己没跑通（提交失败、超时、解析失败）—— 这条数据不该进成功率。 */
        HARNESS_ERROR
    }

    public static EvalRun harnessError(EvalCase evalCase, long wallClockMs, String error) {
        return new EvalRun(evalCase.id(), evalCase.project(), evalCase.entryFile(), evalCase.title(),
                evalCase.patterns(), evalCase.expect(), null, null, null, null, null,
                wallClockMs, error, Instant.now());
    }

    public Verdict verdict() {
        if (harnessError != null || status == null) {
            return Verdict.HARNESS_ERROR;
        }
        return status.equals(expect.name()) ? Verdict.AS_EXPECTED : Verdict.UNEXPECTED;
    }

    public boolean isNegative() {
        return expect == EvalCase.ExpectedOutcome.FAILED;
    }

    /** 数据是否可用（可用于聚合）。 */
    public boolean usable() {
        return verdict() != Verdict.HARNESS_ERROR;
    }

    /**
     * 编译是否全通过。
     *
     * <p>分母为 0 时返回 {@code false} 而不是 {@code true} ——
     * 「没有文件可编译」不能算成「编译全过」。展示层另行区分「无样本」，
     * 但判定层必须先给一个诚实的值（供加权公式求和）。
     */
    public boolean allCompiled() {
        return metrics != null && metrics.filesTotal() > 0
                && metrics.compilePassed() == metrics.filesTotal();
    }

    /** 单测是否有样本。分母为 0 的用例在报告里必须显示「无样本」，不能显示通过率。 */
    public boolean hasTestSamples() {
        return metrics != null && metrics.testsTotal() > 0;
    }

    /** 是否发生过回退重写（VERIFY 轮次 > 1）。 */
    public boolean retried() {
        return metrics != null && metrics.verifyAttempts() > 1;
    }
}
