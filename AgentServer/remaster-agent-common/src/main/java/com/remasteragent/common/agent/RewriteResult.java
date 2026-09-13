package com.remasteragent.common.agent;

/**
 * REWRITE 节点的产出。
 *
 * @param filePath   被改写的文件，相对工程根
 * @param newContent 改写后的完整文件内容
 * @param diff       服务端生成的 unified diff，供人工审查
 * @param rationale  模型给出的改写理由
 * @param model      实际使用的模型名（多模型路由下会随节点而不同）
 * @param attempt    第几次尝试；&gt;0 说明这是回退重写的结果
 */
public record RewriteResult(
        String filePath,
        String newContent,
        String diff,
        String rationale,
        String model,
        int attempt
) {
}
