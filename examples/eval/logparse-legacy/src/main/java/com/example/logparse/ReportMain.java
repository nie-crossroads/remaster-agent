package com.example.logparse;

import java.util.List;

/**
 * 日志汇总 —— 迁移目标文件之一（static main 逻辑抽取：把散在 main 里的逻辑抽成可测方法）。
 *
 * <h2>这个类刻意留下的 JDK 8 写法</h2>
 * <ul>
 *   <li><b>业务逻辑全塞在 {@code public static void main}</b> 里，没有可单独调用的入口，
 *       所以无法单测。正确迁移是把核心汇总抽成 {@code buildSummary} 这类实例方法。</li>
 *   <li>{@code main} 里用 {@code System.out.println} 直接输出，没有返回值。</li>
 * </ul>
 *
 * <p>{@code buildSummary} 的返回文本是<b>可观测契约</b>；迁移动作只是把逻辑从 main 挪出来，语义不变。
 */
public class ReportMain {

    /** 汇总：返回 "total=N error=E" 形式的单行文本。 */
    public String buildSummary(List<String> lines) {
        int total = 0;
        int errors = 0;
        for (int i = 0; i < lines.size(); i++) {
            total++;
            if (lines.get(i).startsWith("ERROR")) {
                errors++;
            }
        }
        return "total=" + total + " error=" + errors;
    }

    /** 入口：实际系统里会读文件，这里只做最小演示（避免依赖文件系统影响评测）。 */
    public static void main(String[] args) {
        System.out.println("use buildSummary(List<String>) for testing");
    }
}
