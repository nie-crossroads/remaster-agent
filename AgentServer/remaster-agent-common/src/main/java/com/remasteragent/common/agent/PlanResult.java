package com.remasteragent.common.agent;

import java.util.List;

/**
 * PLAN 节点的产出 —— 一次迁移的计划。
 *
 * <p>它是「理解整库」和「执行改写」之间的桥梁：PLAN 先决定<b>改哪些文件、为什么改</b>，
 * 再把这份计划翻译成 DAG 上的 REWRITE / VERIFY 节点。
 *
 * <p>{@code steps} 的顺序即执行顺序。人工评审看的正是这份计划 —— 与其让人在改完之后
 * 审一堆 diff，不如让他在动手之前看一眼「打算改这些文件，理由是这些」，
 * 后者的纠错成本低得多。
 *
 * @param summary 一句话概述这次迁移的整体思路（中文，给人和前端看）
 * @param steps   要迁移的文件及其理由，按建议执行顺序排列
 */
public record PlanResult(
        String summary,
        List<PlanStep> steps
) {

    /**
     * 计划中的一步 —— 一个待迁移文件。
     *
     * @param filePath  相对工程根的文件路径
     * @param rationale 为什么这个文件需要迁移（模型给出，中文）
     */
    public record PlanStep(
            String filePath,
            String rationale
    ) {
    }

    /** 计划涉及的文件路径，按顺序。 */
    public List<String> filePaths() {
        return steps == null ? List.of() : steps.stream()
                .map(PlanStep::filePath)
                .filter(path -> path != null && !path.isBlank())
                .toList();
    }
}
