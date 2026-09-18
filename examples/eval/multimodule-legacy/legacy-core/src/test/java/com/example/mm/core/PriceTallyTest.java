package com.example.mm.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PriceTally} 的行为契约。
 *
 * <p>只断言<b>可观测结果</b>：返回值、文案、集合的只读语义。
 * 不断言实现细节（是否还留着 {@code Vector}、是否换成了 {@code BigDecimal}）——
 * 那样会把「实现自由」变成「必须照抄旧写法」，与现代化的目的相反。
 */
class PriceTallyTest {

    @Test
    @DisplayName("合计与分类小计")
    void totals() {
        PriceTally tally = new PriceTally();
        tally.add("food", 10.5d);
        tally.add("transport", 19.5d);
        tally.add("food", 4.0d);

        assertEquals(34.0d, tally.total(), 1e-9d);

        Map<String, Double> byCategory = tally.totalsByCategory();
        assertEquals(2, byCategory.size());
        assertEquals(14.5d, byCategory.get("food").doubleValue(), 1e-9d);
        assertEquals(19.5d, byCategory.get("transport").doubleValue(), 1e-9d);
    }

    @Test
    @DisplayName("分类小计返回的是副本，改它不影响内部状态")
    void totalsByCategoryIsACopy() {
        PriceTally tally = new PriceTally();
        tally.add("food", 10.5d);

        tally.totalsByCategory().clear();

        assertEquals(10.5d, tally.total(), 1e-9d);
        assertEquals(1, tally.totalsByCategory().size());
    }

    @Test
    @DisplayName("汇总文案")
    void summaryText() {
        PriceTally tally = new PriceTally();
        tally.add("food", 10.5d);
        tally.add("transport", 19.5d);

        assertEquals("共 2 笔，合计 30.0", tally.summary());
    }

    @Test
    @DisplayName("默认分类：长度、顺序、只读")
    void defaultCategories() {
        List<String> categories = PriceTally.defaultCategories();

        assertEquals(3, categories.size());
        assertEquals("food", categories.get(0));
        assertEquals("transport", categories.get(1));
        assertEquals("other", categories.get(2));
        // 只读是契约的一部分：调用方拿到的必须是「不能改」的那一份
        assertThrows(UnsupportedOperationException.class, () -> categories.add("extra"));
    }
}
