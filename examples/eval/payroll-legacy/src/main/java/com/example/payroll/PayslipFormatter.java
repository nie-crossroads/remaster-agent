package com.example.payroll;

/**
 * 工资条格式化 —— 迁移目标文件之一（字符串拼接：StringBuilder 逐行拼 → text block）。
 *
 * <h2>这个类刻意留下的 JDK 8 写法</h2>
 * <ul>
 *   <li><b>手工 {@code StringBuilder} 逐行 append</b>，每段自己补 {@code "\n"}。
 *       迁移后应当是一段 text block，既可读又不会因为少写一个换行而错位。</li>
 * </ul>
 *
 * <p>返回文本是<b>可观测契约</b>，逐字符必须保持不变（text block 只是换写法，不动内容）。
 */
public class PayslipFormatter {

    /** 生成一段固定格式的工资条文本。 */
    public String format(String name, int year, int month, long grossCents, long netCents) {
        StringBuilder sb = new StringBuilder();
        sb.append("PAYSLIP\n");
        sb.append("Employee: ").append(name).append("\n");
        sb.append("Period:   ").append(year).append("-").append(month).append("\n");
        sb.append("Gross:    ").append(grossCents).append(" cents\n");
        sb.append("Net:      ").append(netCents).append(" cents\n");
        return sb.toString();
    }
}
