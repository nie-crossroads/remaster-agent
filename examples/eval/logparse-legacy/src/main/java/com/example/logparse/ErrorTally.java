package com.example.logparse;

import java.util.ArrayList;
import java.util.List;

/**
 * 错误计数 —— 迁移目标文件之一（异常处理：catch(Exception) 吞掉 + printStackTrace → 具体异常与结构化）。
 *
 * <h2>这个类刻意留下的 JDK 8 写法</h2>
 * <ul>
 *   <li><b>catch 到异常只 {@code e.printStackTrace()}</b> 然后当没发生：
 *       解析重试次数时格式错乱（{@code retries=abc}）会被默默当成 0，
 *       运维看不到任何告警。</li>
 *   <li>用下标 for 循环遍历 {@code List}。</li>
 * </ul>
 *
 * <p>{@code errorCount} 的返回值与「把坏格式当成 0 还是抛错」无关——契约只看 ERROR 行数，
 * 迁移时把吞异常改成有结构地处理即可，不必改变公开语义。
 */
public class ErrorTally {

    /** 统计以 "ERROR" 开头的行数；同时顺带解析每行的 retries=N，错乱时记 0。 */
    public int errorCount(List<String> lines) {
        int errors = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("ERROR")) {
                errors++;
            }
            parseRetry(line); // 演示用：解析失败仅打印栈帧，不影响计数
        }
        return errors;
    }

    /** 解析行尾的 retries=N；格式错乱时吞掉异常返回 0。 */
    private int parseRetry(String line) {
        try {
            int idx = line.indexOf("retries=");
            if (idx < 0) {
                return 0;
            }
            String token = line.substring(idx + "retries=".length()).trim();
            return Integer.parseInt(token);
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }
}
