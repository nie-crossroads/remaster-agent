package com.example.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InvoiceCalculator} 的行为契约 —— 迁移前后必须逐条为真。
 *
 * <p>这组测试是「迁移评测」的一部分，不是普通单测：RemasterAgent 的 VERIFY 节点在沙箱里跑
 * {@code mvn test}，把通过率当作模型改写质量的唯一客观依据。所以它的职责是把<b>可观测行为</b>
 * 钉死，让「模型改动了语义」一定表现为一条失败用例。
 *
 * <h2>两条自律</h2>
 * <ol>
 *   <li><b>只断言可观测结果，不断言实现细节。</b>这里不检查内部是否真的用了
 *       {@code BigDecimal} —— 那是实现选择，不是行为。金额算法换成 {@code BigDecimal}
 *       之后逐分相同，才算迁移成功。</li>
 *   <li><b>把边界值全部写出来。</b>折扣阶梯的每一档都测「刚好够」与「差一分」两种情况：
 *       {@code >=} 写成 {@code >} 是这类代码最典型的迁移事故，而普通的中间值测不出来。</li>
 * </ol>
 */
class InvoiceCalculatorTest {

    /** 只有一行、数量为 1 的账单，小计恰好等于 {@code unitPriceCents}。 */
    private static InvoiceCalculator withSubtotal(long subtotalCents) {
        InvoiceCalculator calculator = new InvoiceCalculator();
        calculator.addLine("SKU-ONE", 1, subtotalCents);
        return calculator;
    }

    // ------------------------------------------------------------------
    // 空账单与入参校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("空账单：行数 0、小计 0、折扣 0、总额 0，且不抛异常")
    void emptyInvoice() {
        InvoiceCalculator calculator = new InvoiceCalculator();

        assertTrue(calculator.isEmpty());
        assertEquals(0, calculator.lineCount());
        assertEquals(0L, calculator.subtotalCents());
        assertEquals(0, calculator.discountBasisPoints());
        assertEquals(0L, calculator.discountCents());
        assertEquals(0L, calculator.totalCents());
        assertEquals(List.of(), calculator.skus());
    }

    @Test
    @DisplayName("数量非正、单价为负、SKU 为空都抛 IllegalArgumentException")
    void invalidInputsAreRejected() {
        InvoiceCalculator calculator = new InvoiceCalculator();

        assertThrows(IllegalArgumentException.class, () -> calculator.addLine("A", 0, 100L));
        assertThrows(IllegalArgumentException.class, () -> calculator.addLine("A", -1, 100L));
        assertThrows(IllegalArgumentException.class, () -> calculator.addLine("A", 1, -1L));
        assertThrows(IllegalArgumentException.class, () -> calculator.addLine("", 1, 100L));
        assertThrows(IllegalArgumentException.class, () -> calculator.addLine(null, 1, 100L));
        assertEquals(0, calculator.lineCount(), "被拒的入参不该留下痕迹");
    }

    // ------------------------------------------------------------------
    // 小计
    // ------------------------------------------------------------------

    @Test
    @DisplayName("小计 = Σ 数量 × 单价，单位为分")
    void subtotalSumsLines() {
        InvoiceCalculator calculator = new InvoiceCalculator();
        calculator.addLine("A", 3, 1_000L);
        calculator.addLine("B", 2, 250L);

        assertEquals(2, calculator.lineCount());
        assertFalse(calculator.isEmpty());
        assertEquals(3_500L, calculator.subtotalCents());
    }

    @Test
    @DisplayName("同一 SKU 分多行开票时全部计入，且 SKU 列表保留重复与顺序")
    void duplicateSkusAreKept() {
        InvoiceCalculator calculator = new InvoiceCalculator();
        calculator.addLine("A", 1, 100L);
        calculator.addLine("B", 1, 200L);
        calculator.addLine("A", 1, 300L);

        assertEquals(List.of("A", "B", "A"), calculator.skus());
        assertEquals(600L, calculator.subtotalCents());
    }

    @Test
    @DisplayName("大额小计仍精确到分（累加不应引入浮点误差）")
    void largeSubtotalStaysExact() {
        InvoiceCalculator calculator = new InvoiceCalculator();
        calculator.addLine("BIG", 3, 99_999_999L);

        assertEquals(299_999_997L, calculator.subtotalCents());
    }

    // ------------------------------------------------------------------
    // 折扣阶梯 —— 每档都测「刚好够」与「差一分」
    // ------------------------------------------------------------------

    @Test
    @DisplayName("折扣阶梯：差一分拿不到下一档")
    void discountTierLowerBoundaries() {
        assertEquals(0, withSubtotal(99_999L).discountBasisPoints());
        assertEquals(0L, withSubtotal(99_999L).discountCents());

        assertEquals(50, withSubtotal(100_000L).discountBasisPoints());
        assertEquals(500L, withSubtotal(100_000L).discountCents());

        assertEquals(50, withSubtotal(499_999L).discountBasisPoints());
        assertEquals(200, withSubtotal(500_000L).discountBasisPoints());
        assertEquals(10_000L, withSubtotal(500_000L).discountCents());

        assertEquals(200, withSubtotal(999_999L).discountBasisPoints());
        assertEquals(500, withSubtotal(1_000_000L).discountBasisPoints());
        assertEquals(50_000L, withSubtotal(1_000_000L).discountCents());
    }

    @Test
    @DisplayName("折扣向上取整到分：499999 × 50bp = 2499.995 → 2500")
    void discountRoundsHalfUp() {
        assertEquals(2_500L, withSubtotal(499_999L).discountCents());
        assertEquals(20_000L, withSubtotal(999_999L).discountCents());
    }

    @Test
    @DisplayName("折扣恰好落在半分上时进位：100100 × 50bp = 500.5 → 501")
    void discountRoundsExactHalfUp() {
        assertEquals(501L, withSubtotal(100_100L).discountCents());
    }

    @Test
    @DisplayName("总额 = 小计 − 折扣，且恒不大于小计")
    void totalIsSubtotalMinusDiscount() {
        long[] subtotals = {0L, 1L, 99_999L, 100_000L, 499_999L, 500_000L, 1_000_000L, 12_345_678L};
        for (long subtotal : subtotals) {
            InvoiceCalculator calculator = withSubtotal(subtotal);
            assertEquals(subtotal - calculator.discountCents(), calculator.totalCents(),
                    "小计 " + subtotal + " 的总额算错");
            assertTrue(calculator.totalCents() <= subtotal, "总额不该超过小计");
        }
    }

    // ------------------------------------------------------------------
    // 文本输出
    // ------------------------------------------------------------------

    @Test
    @DisplayName("summary() 是固定格式的多行文本")
    void summaryIsFixedText() {
        InvoiceCalculator calculator = new InvoiceCalculator();
        calculator.addLine("A", 2, 60_000L);
        calculator.addLine("B", 1, 5_000L);

        assertEquals(""
                        + "Invoice (2 lines)\n"
                        + "  subtotal: 125000 cents\n"
                        + "  discount: 625 cents (50 bp)\n"
                        + "  total: 124375 cents",
                calculator.summary());
    }

    @Test
    @DisplayName("空账单的 summary() 也是固定格式，不出现空行")
    void emptySummaryIsFixedText() {
        assertEquals(""
                        + "Invoice (0 lines)\n"
                        + "  subtotal: 0 cents\n"
                        + "  discount: 0 cents (0 bp)\n"
                        + "  total: 0 cents",
                new InvoiceCalculator().summary());
    }

    @Test
    @DisplayName("compact() 形如「3 lines, 12345 cents」")
    void compactIsOneLine() {
        InvoiceCalculator calculator = new InvoiceCalculator();
        calculator.addLine("A", 3, 4_115L);

        assertEquals("1 lines, 12345 cents", calculator.compact());
    }
}
