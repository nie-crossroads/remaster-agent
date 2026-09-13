package com.remasteragent.common.agent;

import java.util.List;

/**
 * ANALYZE 节点的产出 —— 喂给 REWRITE 的上下文来源。
 *
 * <p>阶段 1 不做代码 RAG，直接给出整个文件加符号清单；阶段 2 接入 AST 切块与混合检索后，
 * 这里会变成「检索到的相关片段 + 依赖图邻居」的集合。
 *
 * @param filePath      被分析的文件，相对工程根
 * @param packageName   包名
 * @param className     类名
 * @param symbols       类内符号清单（方法 / 字段的全限定名），用于检索与依赖分析
 * @param sourceContent 文件原始内容
 * @param summary       人类可读的摘要，会随任务详情返回给前端展示
 */
public record AnalyzeResult(
        String filePath,
        String packageName,
        String className,
        List<String> symbols,
        String sourceContent,
        String summary
) {
}
