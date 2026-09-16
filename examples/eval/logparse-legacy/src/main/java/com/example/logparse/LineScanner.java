package com.example.logparse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * 行扫描 —— 迁移目标文件之一（资源管理：try/finally 手写关闭 BufferedReader → try-with-resources）。
 *
 * <h2>这个类刻意留下的 JDK 8 写法</h2>
 * <ul>
 *   <li><b>{@code try { ... } finally { reader.close(); }}</b>：关闭逻辑手写，
 *       且 close 自己还要再包一层 try/catch。</li>
 *   <li>用 {@code Reader} 入参而非真实文件路径，便于在测试中注入
 *       {@code StringReader}，不依赖文件系统。</li>
 * </ul>
 *
 * <p>返回行数是<b>可观测契约</b>，迁移成 try-with-resources 后必须完全一致。
 */
public class LineScanner {

    /** 统计 reader 中的行数（以 readLine 返回 null 为结束）。 */
    public int countLines(Reader source) {
        BufferedReader reader = new BufferedReader(source);
        try {
            int n = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                n++;
            }
            return n;
        } catch (IOException e) {
            throw new RuntimeException("读取失败", e);
        } finally {
            try {
                reader.close();
            } catch (IOException ignored) {
                // 关闭失败不阻断已经得到的计数
            }
        }
    }
}
