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
 */
public record TaskView(
        long id,
        String projectRoot,
        String entryFile,
        int targetJdk,
        String status,
        String failReason,
        Instant createdAt,
        Instant updatedAt,
        MetricsView metrics
) {

    /**
     * 量化指标 —— 这个项目最核心的交付物。
     *
     * <p>同时给出原始计数（{@code passed} / {@code total}）和比率，不是冗余：
     * 比率回答「好不好」，计数回答「分母是什么、有没有样本」。
     * 只有比率的话，「单测通过率 100%」在样本为 0 时会产生严重误导 ——
     * 所以 {@code testsTotal} 为 0 时前端必须显示「无单测」而不是「100%」。
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
