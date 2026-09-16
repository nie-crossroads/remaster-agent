package com.example.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StockBalance} 的行为契约。
 *
 * <p>这些断言不是为了「测通」，而是为了钉住迁移时最容易被动摇的那几条：
 * 防御性拷贝、库存不足时的原子性、以及输出顺序与容器实现无关。
 */
class StockBalanceTest {

    private static StockBalance stocked() {
        StockBalance balance = new StockBalance();
        balance.receive("SKU-A", 10);
        balance.receive("SKU-B", 4);
        balance.receive("SKU-C", 7);
        return balance;
    }

    @Test
    @DisplayName("入出库改变库存，从未出现过的 sku 为 0")
    void receiveAndIssue() {
        StockBalance balance = stocked();
        assertEquals(10, balance.onHand("SKU-A"));
        assertEquals(0, balance.onHand("SKU-Z"), "没出现过的 sku 是 0，不是 null 也不是异常");

        balance.issue("SKU-A", 3);
        assertEquals(7, balance.onHand("SKU-A"));
        assertEquals(18, balance.totalOnHand());
        assertEquals(3, balance.skuCount());
    }

    @Test
    @DisplayName("库存不足要抛，且不能留下负数库存（先扣后查的写法会栽在这里）")
    void insufficientStockLeavesStateUntouched() {
        StockBalance balance = stocked();

        assertThrows(IllegalStateException.class, () -> balance.issue("SKU-B", 5));
        assertEquals(4, balance.onHand("SKU-B"), "失败的出库不能改动库存");
        assertEquals(21, balance.totalOnHand());
    }

    @Test
    @DisplayName("数量必须为正")
    void quantitiesMustBePositive() {
        StockBalance balance = new StockBalance();
        assertThrows(IllegalArgumentException.class, () -> balance.receive("SKU-A", 0));
        assertThrows(IllegalArgumentException.class, () -> balance.issue("SKU-A", -1));
    }

    @Test
    @DisplayName("流水是防御性拷贝：改返回值不影响台账")
    void auditTrailIsDefensiveCopy() {
        StockBalance balance = stocked();
        String[] trail = balance.auditTrail();
        assertEquals(3, trail.length);

        trail[0] = "TAMPERED";
        assertEquals("RECEIVE SKU-A +10", balance.auditTrail()[0], "改返回的数组不该动到内部状态");
    }

    @Test
    @DisplayName("流水文本用 ' | ' 分隔，顺序即操作顺序")
    void auditTrailText() {
        StockBalance balance = stocked();
        balance.issue("SKU-A", 2);

        assertEquals("RECEIVE SKU-A +10 | RECEIVE SKU-B +4 | RECEIVE SKU-C +7 | ISSUE SKU-A -2",
                balance.auditTrailAsText());
    }

    @Test
    @DisplayName("低库存清单按「库存升序 + sku 字典序」，与容器迭代顺序无关")
    void lowStockSkusAreSortedDeterministically() {
        StockBalance balance = new StockBalance();
        balance.receive("SKU-C", 2);
        balance.receive("SKU-A", 2);
        balance.receive("SKU-B", 9);
        balance.receive("SKU-D", 1);

        assertArrayEquals(new String[]{"SKU-D", "SKU-A", "SKU-C"}, balance.lowStockSkus(5),
                "库存 1 的 D 在最前；A 与 C 同为 2 时按 sku 字典序");
    }

    @Test
    @DisplayName("阈值恰好等于库存时不算低库存（边界是严格小于）")
    void thresholdIsExclusive() {
        StockBalance balance = stocked();
        assertFalse(contains(balance.lowStockSkus(4), "SKU-B"), "库存 4 对阈值 4 不算低于");
        assertTrue(contains(balance.lowStockSkus(5), "SKU-B"));
    }

    private static boolean contains(String[] values, String target) {
        for (String value : values) {
            if (value.equals(target)) {
                return true;
            }
        }
        return false;
    }
}
