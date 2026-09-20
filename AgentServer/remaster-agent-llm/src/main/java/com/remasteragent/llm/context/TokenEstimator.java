package com.remasteragent.llm.context;

/**
 * 把一段文本估算成 token 数。
 *
 * <p>默认实现是启发式（字符数 / 4）。本项目不内置任何模型的 BPE 词表 —— 引入词表等于又绑死一个
 * 模型，而上下文治理只要「别把窗口撑爆」，要的是<b>不低估</b>：估算偏大只会更保守，不会真的溢出。
 * 接口留出来是为了将来可插拔真实 tokenizer（如 tiktoken 绑定）。
 */
public interface TokenEstimator {

    int estimate(String text);
}
