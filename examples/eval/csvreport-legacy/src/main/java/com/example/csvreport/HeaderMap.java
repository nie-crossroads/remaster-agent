package com.example.csvreport;

import java.util.Hashtable;
import java.util.Map;

/**
 * 表头索引 —— 迁移目标文件之一（Hashtable + 手工拆分 → Map / String.split 与 Stream）。
 *
 * <h2>这个类刻意留下的 JDK 8 写法</h2>
 * <ul>
 *   <li><b>{@code new Hashtable<String, Integer>()}</b>：线程安全的旧集合，这里并不需要线程安全。</li>
 *   <li><b>手写逗号拆分</b>：{@code StringTokenizer} 或逐字符扫描，而不是 {@code split(",")}。</li>
 * </ul>
 *
 * <p>返回的「表头→下标」映射是<b>可观测契约</b>，迁移成 {@code HashMap} + {@code split} 后内容一致。
 */
public class HeaderMap {

    /** 把 "a,b,c" 形式的表头行解析成「表头名 → 下标」的映射。 */
    public Map<String, Integer> indexHeaders(String headerLine) {
        Map<String, Integer> map = new Hashtable<String, Integer>();
        int start = 0;
        int index = 0;
        for (int i = 0; i <= headerLine.length(); i++) {
            boolean end = i == headerLine.length();
            char c = end ? ',' : headerLine.charAt(i);
            if (end || c == ',') {
                String name = headerLine.substring(start, i).trim();
                if (!name.isEmpty()) {
                    map.put(name, Integer.valueOf(index));
                }
                index++;
                start = i + 1;
            }
        }
        return map;
    }
}
