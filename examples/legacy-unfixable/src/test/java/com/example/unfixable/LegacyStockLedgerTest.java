package com.example.unfixable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.oracle.SkuFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 这个测试文件是<b>故意坏掉的</b>：它 import 的 {@code com.example.oracle.SkuFixtures}
 * 在 2023 年的一次重构里被删掉了，测试没人补。
 *
 * <p>于是 {@code mvn test} 在执行到 test-compile 时就失败，永远跑不到断言。
 * 之所以要这样构造，是因为它精确地模拟了一类真实场景 ——
 * <b>失败的原因不在被改写的那个文件里</b>。RemasterAgent 阶段 1 只重写单个源文件，
 * 这类问题它修不了，正确行为是重试到上限后停手并如实报告失败，
 * 而不是把「模型不够努力」当成结论继续调模型。
 */
class LegacyStockLedgerTest {

    @Test
    @DisplayName("入库与出库之后结存正确")
    void balanceAfterMovements() {
        LegacyStockLedger ledger = new LegacyStockLedger();
        ledger.apply(SkuFixtures.seedMovements());

        assertEquals(120, ledger.balance("SKU-1"));
    }
}
