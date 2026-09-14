package com.remasteragent.common.rag;

import java.util.List;

/**
 * 检索命中 —— 分块 + 融合后的排名 + 命中的检索路。
 *
 * <p>{@code sources} 记录这一块是被哪几路召回的（vector / keyword / symbol / neighbor），
 * 这是可解释性的一部分：调检索参数时，能直接看出某块是靠向量命中还是靠符号命中，
 * 而不是面对一个黑盒分数。RRF 融合天然只依赖「排名」而非原始分数，所以这里保留
 * 排名信息而<b>不</b>保留各路原始分数（不同路的分数不可比，留着重只会误导）。
 *
 * @param chunk      命中的分块
 * @param rank       融合后的排名（从 1 开始）
 * @param sources    命中来源，见 {@link #SOURCE_VECTOR} 等
 */
public record RetrievedChunk(
        CodeChunk chunk,
        int rank,
        List<String> sources
) {

    public static final String SOURCE_VECTOR = "vector";
    public static final String SOURCE_KEYWORD = "keyword";
    public static final String SOURCE_SYMBOL = "symbol";
    /** 依赖图邻居扩展进来的块 —— 没被检索命中，但因为与命中块有依赖关系而被带上。 */
    public static final String SOURCE_NEIGHBOR = "neighbor";
}
