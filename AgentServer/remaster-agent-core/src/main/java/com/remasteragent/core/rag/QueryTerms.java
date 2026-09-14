package com.remasteragent.core.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从「检索意图」里切出查询词 —— 纯函数，无 IO，可毫秒级单测。
 *
 * <h2>为什么查询串要专门处理</h2>
 * <p>代码检索的查询不是自然语言句子，而是「文件路径 / 符号名」这类带分隔符的标识符串，
 * 例如 {@code src/main/java/com/example/legacy/LegacyCustomerOrders.java#revenueByMonth}。
 * 直接把它丢给 tsvector 查询会全军覆没：{@code to_tsquery} 会把 {@code / . #} 当语法字符，
 * 轻则语法错误、重则召不回任何东西。所以先切词、再拼查询表达式。
 *
 * <p>切词用 {@code [A-Za-z0-9_]+} 而不是 {@code \w+} 加各种边界：这个字符集恰好覆盖
 * Java 标识符，且<b>不含任何 to_tsquery 的语法字符</b>，切出来的词拼进查询表达式永远安全
 * —— 这个性质本身就是一道注入防线。
 */
public final class QueryTerms {

    /** Java 标识符字符集，不含 to_tsquery 的语法字符（| & ! : * 等）。 */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_]+");

    /** 太短的词（如单字母、{@code id}）检索价值低，还会引入大量噪声命中。 */
    private static final int MIN_TOKEN_LENGTH = 2;

    private QueryTerms() {
    }

    /** 切出查询词（保序、去重）。 */
    public static List<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= MIN_TOKEN_LENGTH) {
                unique.add(token);
            }
        }
        return new ArrayList<>(unique);
    }

    /**
     * 拼 tsvector 查询表达式：{@code OrderService | getStatus | legacy}。
     *
     * <p>用 <b>OR</b> 而不是 AND：查询串往往是「类名 + 方法名 + 路径片段」的堆叠，
     * 要求一个块同时命中所有词会几乎召不回东西。召回放宽，精度交给 RRF 融合去回收。
     *
     * @return 查询表达式；没有可用词时返回 {@code null}（调用方据此跳过全文路）
     */
    public static String toTsQuery(String text) {
        List<String> tokens = tokens(text);
        if (tokens.isEmpty()) {
            return null;
        }
        return String.join(" | ", tokens);
    }

    /**
     * 取符号路的查询片段 —— 也就是「这一串里最像类名/方法名的那一个词」。
     *
     * <p>规则：先砍掉 {@code #} 之后的方法部分，再取最后一个路径分隔符之后的文件名，
     * 去掉 {@code .java}，最后取最后一个 {@code .} 之后的段。这样
     * {@code .../legacy/LegacyCustomerOrders.java} 与
     * {@code com.example.legacy.LegacyCustomerOrders#getStatus} 都能得到
     * {@code LegacyCustomerOrders}。
     *
     * @return 查询片段；无法提取时返回 {@code null}
     */
    public static String symbolFragment(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();

        int hash = trimmed.indexOf('#');
        if (hash > 0) {
            trimmed = trimmed.substring(0, hash);
        }

        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        if (slash >= 0) {
            trimmed = trimmed.substring(slash + 1);
        }

        if (trimmed.endsWith(".java")) {
            trimmed = trimmed.substring(0, trimmed.length() - ".java".length());
        }

        int dot = trimmed.lastIndexOf('.');
        if (dot >= 0) {
            trimmed = trimmed.substring(dot + 1);
        }

        return trimmed.isBlank() ? null : trimmed;
    }
}
