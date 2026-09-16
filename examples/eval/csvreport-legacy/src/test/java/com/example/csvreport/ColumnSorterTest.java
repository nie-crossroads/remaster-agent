package com.example.csvreport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ColumnSorter} 的行为契约 —— 迁移前后必须逐条为真。
 *
 * <p>只断言排序结果与「不改原列表」的副作用；匿名 Comparator 还是
 * {@code Comparator.comparing().thenComparing()} 是实现细节。
 */
class ColumnSorterTest {

    private static List<String> row(String a, String b, String c) {
        return List.of(a, b, c);
    }

    @Test
    @DisplayName("按主列升序，并列按第 0 列、再并列按第 1 列")
    void sortsByColumnWithTieBreak() {
        List<List<String>> rows = List.of(
                row("3", "x", "p"),
                row("1", "y", "q"),
                row("1", "z", "r"),
                row("2", "w", "s"));

        List<List<String>> byCol0 = new ColumnSorter().sortRows(rows, 0);
        assertEquals(List.of(
                row("1", "y", "q"),
                row("1", "z", "r"),
                row("2", "w", "s"),
                row("3", "x", "p")), byCol0);
    }

    @Test
    @DisplayName("按第 1 列升序时主列不参与，顺序由第 1 列决定")
    void sortsBySecondColumn() {
        List<List<String>> rows = List.of(
                row("3", "x", "p"),
                row("1", "y", "q"),
                row("1", "z", "r"),
                row("2", "w", "s"));

        List<List<String>> byCol1 = new ColumnSorter().sortRows(rows, 1);
        assertEquals(List.of(
                row("2", "w", "s"),
                row("3", "x", "p"),
                row("1", "y", "q"),
                row("1", "z", "r")), byCol1);
    }

    @Test
    @DisplayName("排序返回副本，不改原列表")
    void returnsCopy() {
        List<List<String>> rows = List.of(row("3", "x", "p"), row("1", "y", "q"));
        new ColumnSorter().sortRows(rows, 0);
        assertEquals(List.of(row("3", "x", "p"), row("1", "y", "q")), rows,
                "原列表顺序不应被改变");
    }
}
