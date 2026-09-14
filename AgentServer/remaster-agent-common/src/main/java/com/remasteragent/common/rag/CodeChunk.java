package com.remasteragent.common.rag;

/**
 * 一个代码分块 —— AST 感知切分的产物，混合检索的最小单元。
 *
 * <p>和 {@code code_chunk} 表一一对应。字段刻意保持「纯数据」：没有分数、没有检索来源，
 * 那些属于检索结果（{@link RetrievedChunk}）而不属于分块本身。
 *
 * @param filePath  文件路径，相对工程根
 * @param symbol    符号全限定名，如 {@code com.foo.OrderService#getStatus}；文件级块可为空串
 * @param kind      符号类型：CLASS / METHOD / FIELD / FILE
 * @param startLine 起始行（1-based，含）
 * @param endLine   结束行（1-based，含）
 * @param content   代码原文，检索命中后拼进 prompt
 */
public record CodeChunk(
        String filePath,
        String symbol,
        String kind,
        int startLine,
        int endLine,
        String content
) {

    /** 符号类型常量 —— 用字符串而不是枚举，是为了和 DB 里的 TEXT 列零转换对齐。 */
    public static final String KIND_CLASS = "CLASS";
    public static final String KIND_METHOD = "METHOD";
    public static final String KIND_FIELD = "FIELD";
    public static final String KIND_FILE = "FILE";

    /** 给 prompt 用的可读标题，形如 {@code OrderService#getStatus (L12-40)}。 */
    public String displayTitle() {
        String name = (symbol == null || symbol.isBlank()) ? filePath : symbol;
        return name + " (L" + startLine + "-" + endLine + ")";
    }
}
