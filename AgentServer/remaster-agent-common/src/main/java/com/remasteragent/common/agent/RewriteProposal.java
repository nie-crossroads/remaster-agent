package com.remasteragent.common.agent;

/**
 * 模型产出的改写方案 —— 这是 LLM 与系统之间唯一被接受的数据形状。
 *
 * <p>关键设计：模型要么交出一个结构完整的 {@code RewriteProposal}，要么判定失败。
 * **不接受任何自然语言形式的回复** —— 一旦允许「我建议你把这段改成……」，下游就没法自动执行，
 * 整个闭环会退化成人工复制粘贴。这条红线由 {@code ProposalGuardrail} 强制校验。
 *
 * <p>阶段 1 让模型返回整文件内容而不是 diff，理由见
 * {@code docs/ARCHITECTURE.md} —— 单文件场景整文件替换最可靠，diff 由服务端本地生成。
 *
 * @param filePath   目标文件，相对工程根；必须与原文件一致，模型无权换文件
 * @param newContent 改写后的完整文件内容
 * @param rationale  改写理由，供人工审查时快速理解意图
 */
public record RewriteProposal(
        String filePath,
        String newContent,
        String rationale
) {
}
