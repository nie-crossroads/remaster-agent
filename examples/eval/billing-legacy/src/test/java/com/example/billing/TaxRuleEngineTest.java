package com.example.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link TaxRuleEngine} 的行为契约 —— 迁移前后必须逐条为真。
 *
 * <p>这里重点钉的是<b>档位选择</b>与<b>排序结果</b>：
 * <ul>
 *   <li>档位用的是闭区间下界（{@code from <= taxable}），不是开区间 —— 把 {@code <=}
 *       写成 {@code <} 是这类代码最常见的迁移事故，所以每个档位起点都单独测一次。</li>
 *   <li>排序结果必须与「底层排序稳不稳定」无关：类里的比较器是全序，本类里也特意
 *       用<b>起点相同</b>的两个档位来验证这一点。</li>
 * </ul>
 *
 * <p>税额一律对结果做整数断言 —— 向下取整是契约的一部分，不是实现细节。
 */
class TaxRuleEngineTest {

    /** 三档常用引擎：0 → 500bp、10000 → 1000bp、50000 → 2000bp。 */
    private static TaxRuleEngine standardEngine() {
        TaxRuleEngine engine = new TaxRuleEngine();
        engine.addBracket(0L, 500);
        engine.addBracket(10_000L, 1000);
        engine.addBracket(50_000L, 2000);
        return engine;
    }

    // ------------------------------------------------------------------
    // 空引擎与入参校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("没有档位时：税额 0、数量 0、列表为空")
    void emptyEngine() {
        TaxRuleEngine engine = new TaxRuleEngine();

        assertEquals(0, engine.bracketCount());
        assertEquals(List.of(), engine.bracketsAscending());
        assertEquals("Tax rules (0 brackets)", engine.describe());
        assertEquals(0L, engine.taxFor(1_000_000L), "没有规则命中时税额为 0，不是异常");
    }

    @Test
    @DisplayName("起点为负、税率越界都抛 IllegalArgumentException")
    void invalidInputsAreRejected() {
        TaxRuleEngine engine = new TaxRuleEngine();

        assertThrows(IllegalArgumentException.class, () -> engine.addBracket(-1L, 500));
        assertThrows(IllegalArgumentException.class, () -> engine.addBracket(0L, -1));
        assertThrows(IllegalArgumentException.class, () -> engine.addBracket(0L, 10_001));
        assertThrows(IllegalArgumentException.class, () -> engine.taxFor(-1L));
        assertEquals(0, engine.bracketCount(), "被拒的档位不该留下痕迹");
    }

    @Test
    @DisplayName("税率可取到 10000bp（100%）这一端")
    void fullRateIsAllowed() {
        TaxRuleEngine engine = new TaxRuleEngine();
        engine.addBracket(0L, 10_000);

        assertEquals(12_345L, engine.taxFor(12_345L));
    }

    // ------------------------------------------------------------------
    // 排序
    // ------------------------------------------------------------------

    @Test
    @DisplayName("乱序加入后按起点升序输出")
    void bracketsAreSortedByStart() {
        TaxRuleEngine engine = new TaxRuleEngine();
        engine.addBracket(50_000L, 2000);
        engine.addBracket(0L, 500);
        engine.addBracket(10_000L, 1000);

        assertEquals(List.of("from=0 bps=500", "from=10000 bps=1000", "from=50000 bps=2000"),
                engine.bracketsAscending());
    }

    @Test
    @DisplayName("起点相同时按税率升序 —— 结果与底层排序是否稳定无关")
    void tiesAreBrokenByRate() {
        TaxRuleEngine engine = new TaxRuleEngine();
        engine.addBracket(100L, 900);
        engine.addBracket(100L, 100);
        engine.addBracket(100L, 500);

        assertEquals(List.of("from=100 bps=100", "from=100 bps=500", "from=100 bps=900"),
                engine.bracketsAscending());
    }

    @Test
    @DisplayName("bracketsAscending() 返回快照：再追加档位不影响已取出的列表")
    void bracketsAscendingIsSnapshot() {
        TaxRuleEngine engine = standardEngine();

        List<String> snapshot = engine.bracketsAscending();
        engine.addBracket(90_000L, 3000);

        assertEquals(3, snapshot.size());
        assertEquals(4, engine.bracketsAscending().size());
    }

    // ------------------------------------------------------------------
    // 档位选择：每个起点都测「刚好够」与「差一分」
    // ------------------------------------------------------------------

    @Test
    @DisplayName("档位是闭区间下界：起点值本身命中该档")
    void bracketLowerBoundIsInclusive() {
        TaxRuleEngine engine = standardEngine();

        assertEquals(0L, engine.taxFor(0L));
        assertEquals(50L, engine.taxFor(1_000L), "1000 × 500bp = 50");
        assertEquals(1_000L, engine.taxFor(10_000L), "10000 命中 1000bp 档");
        assertEquals(10_000L, engine.taxFor(50_000L), "50000 命中 2000bp 档");
    }

    @Test
    @DisplayName("差一分不越档")
    void oneCentBelowLowerBoundStaysInPreviousTier() {
        TaxRuleEngine engine = standardEngine();

        assertEquals(499L, engine.taxFor(9_999L), "9999 仍在 500bp 档：9999×5% = 499.95 → 499");
        assertEquals(4_999L, engine.taxFor(49_999L), "49999 仍在 1000bp 档：499.9 → 4999");
    }

    @Test
    @DisplayName("税额向下取整到分")
    void taxTruncatesToCent() {
        TaxRuleEngine engine = standardEngine();

        assertEquals(0L, engine.taxFor(1L), "1 分 × 5% = 0.05 分 → 0");
        assertEquals(49L, engine.taxFor(999L), "999 × 5% = 49.95 → 49");
    }

    @Test
    @DisplayName("所有档位起点都高于应税金额时税额为 0")
    void noBracketBelowTaxable() {
        TaxRuleEngine engine = new TaxRuleEngine();
        engine.addBracket(100L, 500);
        engine.addBracket(1_000L, 900);

        assertEquals(0L, engine.taxFor(99L));
        assertEquals(5L, engine.taxFor(100L), "100 分 × 5% = 5 分");
    }

    @Test
    @DisplayName("跨多个档位时取最高的那一档，而不是最低或最后加入的")
    void picksHighestApplicableBracket() {
        TaxRuleEngine engine = standardEngine();

        assertEquals(20_000L, engine.taxFor(100_000L), "100000 命中 2000bp 档，不是 500bp 档");
        assertEquals(1_000L, engine.taxFor(10_000L), "10000 命中 1000bp 档");
        assertEquals(0L, engine.taxFor(0L), "只有 0 起点这一档命中");
    }

    // ------------------------------------------------------------------
    // 文本
    // ------------------------------------------------------------------

    @Test
    @DisplayName("describe() 是固定格式的多行文本")
    void describeIsFixedText() {
        assertEquals(""
                        + "Tax rules (3 brackets)\n"
                        + "  from=0 bps=500\n"
                        + "  from=10000 bps=1000\n"
                        + "  from=50000 bps=2000",
                standardEngine().describe());
    }
}
