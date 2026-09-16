package com.example.billing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 账单金额计算器 —— 迁移目标文件之一（金额精度 / 装箱 / 显式泛型）。
 *
 * <h2>这个类刻意留下的 JDK 8 写法</h2>
 * <ul>
 *   <li><b>金额用 {@code double} 累加</b>：这是这一批里最值得讲的一处。真实系统里
 *       「金额用 double」是教科书级的错误，但它在小数据量下看不出来 —— 所以这里
 *       把入参定成整数分（{@code long}），<b>只在内部</b>用 double 走一遍。
 *       目标是让迁移后的 {@code BigDecimal} 版本产出<b>逐分相同</b>的结果，
 *       而不是「大概差不多」。</li>
 *   <li>显式装箱 {@code Double.valueOf(...)} 与显式拆箱 {@code .doubleValue()}</li>
 *   <li>显式类型实参 {@code new ArrayList<Line>()}（菱形运算符之前的老写法）</li>
 *   <li>下标 for 循环 + {@code get(i)}（for-each 之前的老写法）</li>
 *   <li>手工 {@code StringBuilder} 拼多行文本（text block 之前的老写法）</li>
 * </ul>
 *
 * <h2>公开 API 为什么是「整数分」</h2>
 * <p>迁移护栏禁止改动公开方法签名 —— 所以「把 double 换成 BigDecimal」这件事
 * 若发生在 API 边界上，模型<b>必然</b>被拦住，那不是能力测试而是送死题。
 * 把金额的对外表示定成 {@code long} 分，精度问题就完全落在实现内部：
 * 模型换不换 BigDecimal 都不会破坏契约，但换了才是这个类的正确解法。
 *
 * <p>公开方法签名与 {@link #summary()} 的输出文本是本类的<b>可观测契约</b>，
 * 迁移后必须逐字保持不变。
 */
public class InvoiceCalculator {

    /** 累计金额，单位：分。 */
    private final List<Line> lines = new ArrayList<Line>();

    /**
     * 追加一行账单。
     *
     * @param quantity       数量，必须为正
     * @param unitPriceCents 单价，单位分，不可为负
     * @throws IllegalArgumentException 数量非正或单价为负
     */
    public void addLine(String sku, int quantity, long unitPriceCents) {
        if (sku == null || sku.isEmpty()) {
            throw new IllegalArgumentException("sku 不能为空");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity 必须为正: " + quantity);
        }
        if (unitPriceCents < 0) {
            throw new IllegalArgumentException("unitPriceCents 不可为负: " + unitPriceCents);
        }
        lines.add(new Line(sku, quantity, unitPriceCents));
    }

    public int lineCount() {
        return lines.size();
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /** 按加入顺序返回 SKU，重复的 SKU 会被保留（同一商品可以分多行开票）。 */
    public List<String> skus() {
        List<String> result = new ArrayList<String>();
        for (int i = 0; i < lines.size(); i++) {
            result.add(lines.get(i).sku());
        }
        return result;
    }

    /** 小计（分），不含折扣。 */
    public long subtotalCents() {
        double sum = 0d;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            sum += Double.valueOf(line.quantity() * line.unitPriceCents()).doubleValue();
        }
        return Double.valueOf(sum).longValue();
    }

    /**
     * 阶梯折扣（基点，1 bp = 0.01%）。
     *
     * <p>阈值按<b>小计</b>判定，取最高满足的一档：
     * <pre>
     *   小计 &gt;= 1000000 分 → 500 bp（5%）
     *   小计 &gt;=  500000 分 → 200 bp（2%）
     *   小计 &gt;=  100000 分 →  50 bp（0.5%）
     *   否则                →   0 bp
     * </pre>
     */
    public int discountBasisPoints() {
        long subtotal = subtotalCents();
        if (subtotal >= 1_000_000L) {
            return 500;
        }
        if (subtotal >= 500_000L) {
            return 200;
        }
        if (subtotal >= 100_000L) {
            return 50;
        }
        return 0;
    }

    /** 折扣金额（分），小数部分四舍五入。 */
    public long discountCents() {
        double subtotal = subtotalCents();
        int bps = discountBasisPoints();
        return Double.valueOf(Math.round(subtotal * bps / 10000d)).longValue();
    }

    /** 应付总额（分）= 小计 − 折扣。 */
    public long totalCents() {
        return subtotalCents() - discountCents();
    }

    /** 账单文本。多行固定格式，是 text block 的典型适用场景。 */
    public String summary() {
        StringBuilder builder = new StringBuilder();
        builder.append("Invoice (").append(lineCount()).append(" lines)\n");
        builder.append("  subtotal: ").append(subtotalCents()).append(" cents\n");
        builder.append("  discount: ").append(discountCents())
                .append(" cents (").append(discountBasisPoints()).append(" bp)\n");
        builder.append("  total: ").append(totalCents()).append(" cents");
        return builder.toString();
    }

    /** 便于日志排查的一行摘要，形如 {@code 3 lines, 12345 cents}。 */
    public String compact() {
        return String.format(Locale.ROOT, "%d lines, %d cents", lineCount(), totalCents());
    }

    /** 账单行。 */
    private static final class Line {

        private final String sku;
        private final int quantity;
        private final long unitPriceCents;

        private Line(String sku, int quantity, long unitPriceCents) {
            this.sku = sku;
            this.quantity = quantity;
            this.unitPriceCents = unitPriceCents;
        }

        private String sku() {
            return sku;
        }

        private int quantity() {
            return quantity;
        }

        private long unitPriceCents() {
            return unitPriceCents;
        }
    }
}
