package com.example.csvreport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link RowJoiner} 的行为契约 —— 迁移前后必须逐条为真。
 *
 * <p>只断言拼接结果；循环拼接还是 {@code String.join} 是实现细节。
 */
class RowJoinerTest {

    @Test
    @DisplayName("多字段用分隔符拼接")
    void joinsWithSeparator() {
        assertEquals("a,b,c", new RowJoiner().joinRow(List.of("a", "b", "c"), ","));
    }

    @Test
    @DisplayName("空列表返回空串、单元素不加分隔符")
    void emptyAndSingle() {
        assertEquals("", new RowJoiner().joinRow(List.of(), ","));
        assertEquals("x", new RowJoiner().joinRow(List.of("x"), ";"));
    }

    @Test
    @DisplayName("空分隔符直接首尾相连")
    void emptySeparator() {
        assertEquals("ab", new RowJoiner().joinRow(List.of("a", "b"), ""));
    }
}
