package com.example.mm.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link StatementWriter} 的行为契约。
 *
 * <p>断言用的是 {@code String.format("%-12s%8s", ...)} 现算出来的期望行 ——
 * 而不是手写一串空格。好处有两层：① 空白数量不会因为编辑器/换行符设置被悄悄改掉；
 * ② 它表达的是「列宽 12 与 8」这个契约本身，而不是某一次渲染的字面产物。
 *
 * <p>刻意<b>不</b>断言实现方式：用 StringBuilder 补空格、用 {@code String.repeat}、
 * 或者一整段 text block，只要渲染出的文本一致就算通过。
 */
class StatementWriterTest {

    private final StatementWriter writer = new StatementWriter();

    @Test
    @DisplayName("表头 + 明细 + 合计，末尾不带换行")
    void rendersStatement() {
        String text = writer.write(new String[]{"coffee", "book"}, new double[]{12.5d, 30.0d});

        String[] lines = text.split("\n", -1);
        assertEquals(4, lines.length, "表头 + 2 行明细 + 合计，且末尾不能多出一个空行");
        assertEquals(row("ITEM", "AMOUNT"), lines[0]);
        assertEquals(row("coffee", "12.50"), lines[1]);
        assertEquals(row("book", "30.00"), lines[2]);
        assertEquals(row("TOTAL", "42.50"), lines[3]);
    }

    @Test
    @DisplayName("没有明细时只渲染表头与合计")
    void rendersEmptyStatement() {
        String[] lines = writer.write(new String[0], new double[0]).split("\n", -1);

        assertEquals(2, lines.length);
        assertEquals(row("ITEM", "AMOUNT"), lines[0]);
        assertEquals(row("TOTAL", "0.00"), lines[1]);
    }

    @Test
    @DisplayName("名称超过列宽时原样输出，不截断")
    void longNameIsNotTruncated() {
        String[] lines = writer.write(new String[]{"a-very-long-item-name"}, new double[]{1.0d})
                .split("\n", -1);

        assertEquals(row("a-very-long-item-name", "1.00"), lines[1]);
    }

    /** 期望行：名称左对齐补到 12 列，金额右对齐占 8 列。 */
    private static String row(String name, String amount) {
        return String.format("%-12s%8s", name, amount);
    }
}
