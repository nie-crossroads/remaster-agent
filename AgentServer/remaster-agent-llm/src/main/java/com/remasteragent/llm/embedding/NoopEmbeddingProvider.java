package com.remasteragent.llm.embedding;

/**
 * 空实现 —— 未配置 embedding 模型时的占位。
 *
 * <p>它的存在不是「偷懒」，而是让「没有向量模型」成为一种<b>被显式表达的正常状态</b>：
 * 检索层靠 {@link #available()} 判断是否走向量路，而不是靠 try/catch 一个异常来决定降级。
 * 后者会让「忘记配置」和「服务临时挂了」表现成同一件事，排查时分不清。
 */
public final class NoopEmbeddingProvider implements EmbeddingProvider {

    public static final NoopEmbeddingProvider INSTANCE = new NoopEmbeddingProvider();

    private NoopEmbeddingProvider() {
    }

    @Override
    public String modelName() {
        return "(未配置)";
    }

    @Override
    public int dimensions() {
        return 0;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public float[] embed(String text) {
        // 调用方应先看 available() 再调用；走到这里说明是编程错误，直接暴露而不是返回空向量
        // —— 悄悄返回一个零向量会让检索结果全部变成噪声，比抛异常难查得多
        throw new UnsupportedOperationException(
                "未配置 embedding 模型（remaster.llm.embedding-model 为空），向量检索路不可用");
    }
}
