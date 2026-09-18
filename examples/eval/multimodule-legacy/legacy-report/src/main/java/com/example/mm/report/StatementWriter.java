package com.example.mm.report;

import java.util.Locale;

/**
 * 对账单文本渲染 —— 遗留写法样本（legacy-report 模块，编译级别由本模块的
 * {@code maven.compiler.release} 单独声明为 8）。
 *
 * <p>老写法集中在手工对齐：两个 {@code while}/{@code for} 循环往字符串里补空格，
 * 逐行 {@code append("\n")}。这类代码最自然的现代化结果是一段 <b>text block</b>（JDK 15+）——
 * 而它恰恰是「编译级别没升就编不过」的典型：text block 在 {@code --release 8} 下是语法错误，
 * 报错信息看起来像模型乱写，实际是 pom 没升。
 *
 * <p>输出格式（宽度与小数位都是对外契约，测试按这个形状断言）：
 * <pre>
 * ITEM              AMOUNT
 * coffee             12.50
 * book               30.00
 * TOTAL              42.50
 * </pre>
 */
public class StatementWriter {

    /** 名称列宽。 */
    private static final int NAME_WIDTH = 12;

    /** 金额列宽。 */
    private static final int AMOUNT_WIDTH = 8;

    /**
     * 渲染对账单：表头 + 逐行明细 + 合计行，行与行之间用 {@code \n} 分隔，
     * <b>末尾不带换行</b>（契约的一部分，调用方直接写文件不必再裁一次）。
     */
    public String write(String[] names, double[] amounts) {
        StringBuilder out = new StringBuilder();
        out.append(padRight("ITEM", NAME_WIDTH));
        out.append(padLeft("AMOUNT", AMOUNT_WIDTH));

        double total = 0d;
        for (int i = 0; i < names.length; i++) {
            out.append("\n");
            out.append(padRight(names[i], NAME_WIDTH));
            out.append(padLeft(money(amounts[i]), AMOUNT_WIDTH));
            total = total + amounts[i];
        }

        out.append("\n");
        out.append(padRight("TOTAL", NAME_WIDTH));
        out.append(padLeft(money(total), AMOUNT_WIDTH));
        return out.toString();
    }

    /** 右侧补空格到指定宽度（超出则原样返回，不截断）。 */
    private static String padRight(String text, int width) {
        StringBuilder padded = new StringBuilder(text);
        while (padded.length() < width) {
            padded.append(' ');
        }
        return padded.toString();
    }

    /** 左侧补空格到指定宽度。 */
    private static String padLeft(String text, int width) {
        StringBuilder padded = new StringBuilder();
        for (int i = text.length(); i < width; i++) {
            padded.append(' ');
        }
        padded.append(text);
        return padded.toString();
    }

    /**
     * 金额两位小数。
     *
     * <p>显式指定 {@code Locale.ROOT}：不指定的话小数分隔符跟着运行环境走 ——
     * 德语环境会输出 {@code 12,50}，于是同一份代码在不同机器上渲染出不同文本，
     * 而测试只在其中一种环境下绿。这类「环境相关的确定性」是评测样本必须消灭的。
     */
    private static String money(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
