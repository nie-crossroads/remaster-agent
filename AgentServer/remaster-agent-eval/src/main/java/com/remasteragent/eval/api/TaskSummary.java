package com.remasteragent.eval.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.remasteragent.common.domain.TaskMetrics;

import java.util.Set;

/**
 * harness 眼里的一个任务 —— 只保留量化报告用得上的那几个字段。
 *
 * <p>刻意不是完整镜像 {@code TaskView}：harness 依赖的字段越少，
 * 越不容易被 API 的正常演进碰坏。真正需要「同形状」的是
 * {@link TaskMetrics}（指标语义），那个直接复用 common 里的定义，不另抄一份。
 *
 * @param runDurationMs 实际运行时长（各次运行墙钟之和），{@code null} 表示没有 trace 数据。
 *                      <b>不能当 0 用</b> —— 「不知道」和「运行了 0 秒」是两回事。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskSummary(
        long id,
        String status,
        String failReason,
        boolean cancelRequested,
        Long runDurationMs,
        TaskMetrics metrics
) {

    /** 终态集合。与 worker 的判定保持一致：只有这三个状态不会再变。 */
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED");

    public boolean isTerminal() {
        return status != null && TERMINAL.contains(status);
    }

    public boolean succeeded() {
        return "SUCCEEDED".equals(status);
    }
}
