package com.example.csvreport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 列排序 —— 迁移目标文件之一（匿名 Comparator 多字段 → Comparator.comparing/thenComparing）。
 *
 * <h2>这个类刻意留下的 JDK 8 写法</h2>
 * <ul>
 *   <li><b>匿名内部类 {@code Comparator}</b>，手写三级比较（主列 → 第 0 列 → 第 1 列）。</li>
 *   <li><b>复制后再排序</b>，不改原列表。</li>
 * </ul>
 *
 * <p>排序语义（主列升序、并列时按第 0 列、再并列按第 1 列）是<b>可观测契约</b>，迁移后必须一致。
 */
public class ColumnSorter {

    /** 按第 col 列升序排序（字符串序），并列按第 0 列、再并列按第 1 列；返回新列表。 */
    public List<List<String>> sortRows(List<List<String>> rows, int col) {
        List<List<String>> copy = new ArrayList<List<String>>(rows);
        Collections.sort(copy, new Comparator<List<String>>() {
            @Override
            public int compare(List<String> a, List<String> b) {
                int primary = a.get(col).compareTo(b.get(col));
                if (primary != 0) {
                    return primary;
                }
                int secondary = a.get(0).compareTo(b.get(0));
                if (secondary != 0) {
                    return secondary;
                }
                return a.get(1).compareTo(b.get(1));
            }
        });
        return copy;
    }
}
