package com.example.csvreport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link HeaderMap} 的行为契约 —— 迁移前后必须逐条为真。
 *
 * <p>只断言「表头名→下标」映射的内容；Hashtable 还是 HashMap、手写拆分还是
 * {@code split} 是实现细节。
 */
class HeaderMapTest {

    @Test
    @DisplayName("逗号表头行解析成下标映射")
    void indexesHeaders() {
        Map<String, Integer> expected = Map.of("name", 0, "age", 1, "city", 2);
        assertEquals(expected, new HeaderMap().indexHeaders("name,age,city"));
    }

    @Test
    @DisplayName("单列表头与带空格的表头都正确")
    void singleAndSpaced() {
        assertEquals(Map.of("a", 0), new HeaderMap().indexHeaders("a"));
        assertEquals(Map.of("x", 0, "y", 1), new HeaderMap().indexHeaders(" x , y "));
    }
}
