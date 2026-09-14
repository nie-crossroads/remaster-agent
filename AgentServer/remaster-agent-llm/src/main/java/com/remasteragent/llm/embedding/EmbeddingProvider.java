package com.remasteragent.llm.embedding;

/**
 * 文本向量化 —— 代码 RAG 向量路的唯一入口。
 *
 * <h2>为什么做成接口</h2>
 * <p>向量模型是最容易被外部条件卡住的一环：它可能来自 OpenAI 兼容网关、本地 ONNX 模型、
 * 或者干脆暂时没有。把「怎么算向量」抽象成一个接口，混合检索就只依赖
 * {@link #embed(String)} 这一个契约，不会因为换模型而改动检索与编排逻辑。
 *
 * <p>当前项目接入的网关只提供 chat 模型、没有 embedding 通道，所以默认装配的是
 * {@link NoopEmbeddingProvider}（{@link #available()} 为 false）。混合检索检测到这个信号后
 * 会跳过向量路，退化为「全文 + 符号」两路 —— 检索照常可用，只是少了一路召回。
 * 等接入真正的 embedding 服务，只需把 bean 换成 {@link HttpEmbeddingProvider}，检索层零改动。
 *
 * <p><b>维度必须与 {@code code_chunk.embedding} 的 {@code vector(1024)} 对齐</b>，
 * 否则写入会被 PostgreSQL 拒绝。选择模型或使用降维参数时务必确认这一点。
 */
public interface EmbeddingProvider {

    /** 模型名，写进日志与将来的成本记录。 */
    String modelName();

    /** 向量维度；未知时返回 0。用于和 {@code vector(1024)} 做一致性校验。 */
    int dimensions();

    /** 是否可用。false 时调用方应跳过向量路，而不是调用 {@link #embed(String)}。 */
    boolean available();

    /**
     * 把一段文本转成向量。
     *
     * @throws UnsupportedOperationException 提供者不可用时
     * @throws IllegalStateException         远端调用失败时（属于系统故障，调用方应显式降级或失败）
     */
    float[] embed(String text);
}
