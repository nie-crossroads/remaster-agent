package com.remasteragent.llm.context;

import java.util.List;

/**
 * 一条待投喂给模型的上下文项（已归一化，与具体检索实现解耦）。
 *
 * @param priority 优先级，越小越重要（改写场景里直接复用 RRF 融合排名）
 * @param title    可读标题（如 {@code OrderService#getStatus (L12-40)}）
 * @param filePath 文件路径，相对工程根
 * @param sources  命中来源（vector / keyword / symbol / neighbor），用于 prompt 里标注可信度
 * @param content  代码正文（可能已被单块上限截断）
 * @param label    给日志用的简短标识（通常等于 title）
 */
public record ContextItem(
        int priority,
        String title,
        String filePath,
        List<String> sources,
        String content,
        String label) {

    /** 渲染成 prompt 里的一整块（含序号、来源标注、代码围栏）。 */
    public String render(int index) {
        StringBuilder sb = new StringBuilder();
        sb.append('\n').append('[').append(index).append("] ").append(title)
                .append(" | ").append(filePath)
                .append(" | hits: ").append(String.join("+", sources)).append('\n');
        sb.append("```java\n").append(content).append("\n```\n");
        return sb.toString();
    }

    /** 该项渲染后的估算 token 数（序号对体量影响可忽略，统一用 0 号估算）。 */
    public int estimatedTokens(TokenEstimator estimator) {
        return estimator.estimate(render(0));
    }
}
