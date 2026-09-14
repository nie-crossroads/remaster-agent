package com.remasteragent.core.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QueryTerms} 的确定性单测 —— 纯函数，无 IO。
 *
 * <p>重点是「拼出来的 to_tsquery 表达式永远不含语法字符」这条不变式，
 * 它既是正确性要求，也是一道注入防线。
 */
class QueryTermsTest {

    @Test
    void 切词只保留标识符字符并过滤过短词() {
        List<String> tokens = QueryTerms.tokens("com.example.legacy/LegacyCustomerOrders.java#revenueByMonth");
        assertEquals(List.of("com", "example", "legacy", "LegacyCustomerOrders", "java", "revenueByMonth"), tokens);
    }

    @Test
    void 切词去重且保序() {
        assertEquals(List.of("Foo", "bar"), QueryTerms.tokens("Foo bar Foo"));
    }

    @Test
    void 过短的单字母词被过滤() {
        // 单字母（如泛型 T、变量 i）检索价值低，进入查询只会制造噪声
        assertEquals(List.of("order"), QueryTerms.tokens("T i order a"));
    }

    @Test
    void toTsQuery用或连接各词() {
        assertEquals("OrderService | getStatus", QueryTerms.toTsQuery("OrderService#getStatus"));
    }

    @Test
    void toTsQuery不含查询语法字符() {
        String tsQuery = QueryTerms.toTsQuery("a/b\\c:d*e&f|g!h");
        // 全部语法字符都在切词阶段被吃掉：表达式里只应有字母数字下划线、空格和 |
        assertTrue(tsQuery == null || tsQuery.matches("[A-Za-z0-9_ ]+(\\| ?[A-Za-z0-9_ ]+)*"),
                "tsquery 表达式不应含语法字符，实际: " + tsQuery);
    }

    @Test
    void 无可用词时返回空() {
        assertNull(QueryTerms.toTsQuery("// .# ?!"));
        assertNull(QueryTerms.toTsQuery(""));
        assertNull(QueryTerms.toTsQuery(null));
    }

    @Test
    void 符号片段从路径提取类名() {
        assertEquals("LegacyCustomerOrders",
                QueryTerms.symbolFragment("src/main/java/com/example/legacy/LegacyCustomerOrders.java"));
    }

    @Test
    void 符号片段从全限定符号提取类名() {
        assertEquals("LegacyCustomerOrders",
                QueryTerms.symbolFragment("com.example.legacy.LegacyCustomerOrders#getStatus"));
    }

    @Test
    void 符号片段无法提取时返回空() {
        assertNull(QueryTerms.symbolFragment("   "));
        assertNull(QueryTerms.symbolFragment(null));
    }
}
