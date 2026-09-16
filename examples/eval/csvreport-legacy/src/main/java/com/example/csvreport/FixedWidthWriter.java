package com.example.csvreport;

/**
 * 定宽对齐 —— 迁移目标文件之一（手工补空格对齐 → String.repeat / formatted 与 text block）。
 *
 * <h2>这个类刻意留下的 JDK 8 写法</h2>
 * <ul>
 *   <li><b>两层下标 for 循环手工补空格</b>到指定宽度，左对齐。</li>
 * </ul>
 *
 * <p>返回的对齐文本是<b>可观测契约</b>，迁移成 {@code String.repeat} 后必须逐字符相同。
 */
public class FixedWidthWriter {

    /** 把每个字段左对齐补空格到对应宽度，拼接成一行定宽文本（字段间无分隔符）。 */
    public String formatRow(String[] fields, int[] widths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            String field = fields[i];
            int width = widths[i];
            if (field.length() >= width) {
                sb.append(field.substring(0, width));
            } else {
                sb.append(field);
                for (int k = field.length(); k < width; k++) {
                    sb.append(' ');
                }
            }
        }
        return sb.toString();
    }
}
