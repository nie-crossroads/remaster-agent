package com.example.csvreport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link FixedWidthWriter} 的行为契约 —— 迁移前后必须逐字符相同。
 *
 * <p>返回的对齐文本是固定格式；手工补空格还是 {@code String.repeat} 只是换写法，内容不能变。
 */
class FixedWidthWriterTest {

    @Test
    @DisplayName("短于宽度的字段左对齐补空格；字段间无分隔符")
    void padsRightToWidth() {
        assertEquals("ab  c  ", new FixedWidthWriter().formatRow(
                new String[]{"ab", "c"}, new int[]{4, 3}));
        assertEquals("x    ", new FixedWidthWriter().formatRow(
                new String[]{"x"}, new int[]{5}));
    }

    @Test
    @DisplayName("长于宽度的字段按宽度截断")
    void truncatesOversizedField() {
        assertEquals("hela ", new FixedWidthWriter().formatRow(
                new String[]{"hello", "a"}, new int[]{3, 2}));
    }
}
