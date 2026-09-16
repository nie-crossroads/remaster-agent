package com.example.csvreport;

import java.util.List;

/**
 * 行拼接 —— 迁移目标文件之一（字符串拼接：循环内 StringBuilder → String.join）。
 *
 * <h2>这个类刻意留下的 JDK 8 写法</h2>
 * <ul>
 *   <li><b>下标 for 循环 + 手动判断「是否首个」再决定要不要加分隔符</b>：
 *       这是 {@code String.join} 出现之前的标准写法。</li>
 * </ul>
 *
 * <p>返回字符串是<b>可观测契约</b>，迁移成 {@code String.join} 后必须逐字符相同。
 */
public class RowJoiner {

    /** 用 sep 把各单元格拼成一个字符串；空列表返回空串，单元素不加分隔符。 */
    public String joinRow(List<String> cells, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(cells.get(i));
        }
        return sb.toString();
    }
}
