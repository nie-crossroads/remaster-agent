package com.remasteragent.llm.context;

/**
 * 基于字符数的启发式 token 估算。
 *
 * <p><b>为什么不用真实 BPE 词表</b>：本项目不内置任何模型的 tokenizer，引入词表等于又绑死一个
 * 模型。而上下文治理的目的是「别把窗口撑爆」，要的是<b>不低估</b>——估算偏大只会更保守，
 * 不会真的溢出窗口。经验值 1 token ≈ 4 字符（英文 / 代码通用），对中文偏保守（中文 token 更密），
 * 但上下文里绝大多数是英文代码，整体仍安全。
 */
public class CharBasedTokenEstimator implements TokenEstimator {

    private final double charsPerToken;

    public CharBasedTokenEstimator(double charsPerToken) {
        if (charsPerToken <= 0) {
            throw new IllegalArgumentException("charsPerToken 必须为正");
        }
        this.charsPerToken = charsPerToken;
    }

    /** 代码 / 英文默认：1 token ≈ 4 字符。 */
    public static TokenEstimator codeDefault() {
        return new CharBasedTokenEstimator(4.0);
    }

    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / charsPerToken);
    }
}
