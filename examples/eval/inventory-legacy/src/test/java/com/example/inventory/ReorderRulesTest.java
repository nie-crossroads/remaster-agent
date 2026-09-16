package com.example.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReorderRules} 的行为契约。
 *
 * <p>{@code customFilterInterfaceStillWorks} 是这一组里最要紧的一条：
 * 它保证「现代化」没有把公开签名推倒重来 —— 换成 {@code Predicate} 会让调用方全部改一遍，
 * 那不是迁移，是重写。护栏禁止改公开签名，这条断言就是它的可执行版本。
 */
class ReorderRulesTest {

    private static ReorderRules rules() {
        ReorderRules rules = new ReorderRules();
        rules.addRule("SKU-C", 5, 30);
        rules.addRule("SKU-A", 10, 50);
        rules.addRule("SKU-B", 0, 20);
        return rules;
    }

    @Test
    @DisplayName("补货量 = 目标 - 现有，为负取 0；没配规则也是 0")
    void reorderQuantity() {
        ReorderRules rules = rules();
        assertEquals(40, rules.reorderQuantity("SKU-A", 10));
        assertEquals(0, rules.reorderQuantity("SKU-A", 80), "库存够了不补，也不倒扣");
        assertEquals(0, rules.reorderQuantity("SKU-Z", 1), "没配规则的 sku 补 0");
    }

    @Test
    @DisplayName("安全库存是严格小于")
    void belowSafetyStock() {
        ReorderRules rules = rules();
        assertTrue(rules.belowSafetyStock("SKU-A", 9));
        assertFalse(rules.belowSafetyStock("SKU-A", 10), "刚好等于安全库存不算触发");
        assertFalse(rules.belowSafetyStock("SKU-Z", 0));
    }

    @Test
    @DisplayName("过滤用自定的 Filter 接口，结果按 sku 字典序（与插入顺序无关）")
    void customFilterInterfaceStillWorks() {
        ReorderRules rules = rules();

        ReorderRules.Filter onlyA = new ReorderRules.Filter() {
            @Override
            public boolean accept(String sku) {
                return sku.startsWith("SKU-A");
            }
        };
        assertArrayEquals(new String[]{"SKU-A"}, rules.skusNeedingReorder(onlyA));

        assertArrayEquals(new String[]{"SKU-A", "SKU-B", "SKU-C"},
                rules.skusNeedingReorder(null), "filter 为 null 表示不过滤");
    }

    @Test
    @DisplayName("过滤结果顺序不依赖内部容器：换实现不会改变输出")
    void filterResultIsSortedNotInsertionOrdered() {
        ReorderRules rules = rules();
        ReorderRules.Filter all = new ReorderRules.Filter() {
            @Override
            public boolean accept(String sku) {
                return true;
            }
        };
        assertArrayEquals(new String[]{"SKU-A", "SKU-B", "SKU-C"}, rules.skusNeedingReorder(all),
                "插入顺序是 C/A/B，输出必须是字典序");
    }

    @Test
    @DisplayName("describe 按 sku 字典序，形如 SKU-A(min 10 → 50)")
    void describeIsSorted() {
        assertEquals("SKU-A(min 10 → 50); SKU-B(min 0 → 20); SKU-C(min 5 → 30)",
                rules().describe());
    }

    @Test
    @DisplayName("安全库存不能高于目标库存；重复 sku 的规则按先到先得")
    void validation() {
        ReorderRules rules = new ReorderRules();
        assertThrows(IllegalArgumentException.class, () -> rules.addRule("SKU-A", 50, 10));

        rules.addRule("SKU-A", 1, 10);
        rules.addRule("SKU-A", 5, 99);
        assertEquals(2, rules.ruleCount());
        assertEquals(9, rules.reorderQuantity("SKU-A", 1), "同 sku 取先加的那条（下标最小的）");
    }
}
