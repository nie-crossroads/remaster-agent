package com.example.legacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Calendar;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LegacyCustomerOrders} 的行为契约 —— 迁移前后必须逐条为真。
 *
 * <p>这组测试是「迁移评测」的一部分。RemasterAgent 的 VERIFY 节点在沙箱里执行 {@code mvn test}，
 * 把通过率作为模型改写质量的唯一客观依据。所以断言的作用是<b>把可观测行为钉死</b>：
 * 模型一旦改动了语义，就必然表现为一条失败用例，而不是「看起来改得挺好」。
 *
 * <p>自律（与 legacy-demo 一致）：
 * <ol>
 *   <li><b>不依赖不确定性输入。</b>凡涉及时间一律注入固定 epoch（{@link #FIXED_EPOCH_MILLIS}），
 *   断言直接写死字符串。</li>
 *   <li><b>只断言可观测结果，不断言实现细节。</b>{@code Order} 是内部类，测试从不构造它；
 *   模型把它改成记录 / 不可变都无所谓，只要 {@code LegacyCustomerOrders} 的公开行为不变。</li>
 * </ol>
 */
class LegacyCustomerOrdersTest {

    /** 2023-11-14T22:13:20Z。写死它，让所有时间断言变成纯字符串比较。 */
    private static final long FIXED_EPOCH_MILLIS = 1_700_000_000_000L;

    /** 期望的时间戳文本，与 {@link #FIXED_EPOCH_MILLIS} 一一对应。 */
    private static final String EXPECTED_TIMESTAMP = "2023-11-14 22:13:20";

    private static LegacyCustomerOrders with(String id, double price, int qty, String status) {
        LegacyCustomerOrders orders = new LegacyCustomerOrders(FIXED_EPOCH_MILLIS);
        orders.addOrder(id, price, qty, status);
        return orders;
    }

    /** o1=NEW(20) / o2=SHIPPED(15) / o3=CANCELLED(排除) —— 后续多数用例的基准。 */
    private static LegacyCustomerOrders mixedOrders() {
        LegacyCustomerOrders orders = new LegacyCustomerOrders(FIXED_EPOCH_MILLIS);
        orders.addOrder("o1", 10d, 2, "NEW");
        orders.addOrder("o2", 5d, 3, "SHIPPED");
        orders.addOrder("o3", 100d, 1, "CANCELLED");
        return orders;
    }

    // ------------------------------------------------------------------
    // 聚合
    // ------------------------------------------------------------------

    @Test
    @DisplayName("空订单：数量 0、营收 0、均价 0、折扣营收 0，且不抛异常")
    void emptyOrders() {
        LegacyCustomerOrders orders = new LegacyCustomerOrders(FIXED_EPOCH_MILLIS);

        assertEquals(0, orders.orderCount());
        assertEquals(0d, orders.totalRevenue(), 1e-9);
        assertEquals(0d, orders.averageOrderValue(), 1e-9);
        assertEquals(0d, orders.discountedRevenue(), 1e-9);
        assertNull(orders.oldestOrderId());
    }

    @Test
    @DisplayName("取消订单不计入营收与均价")
    void cancelledOrdersExcluded() {
        LegacyCustomerOrders orders = mixedOrders();

        assertEquals(3, orders.orderCount());
        assertEquals(35d, orders.totalRevenue(), 1e-9);
        assertEquals(17.5d, orders.averageOrderValue(), 1e-9);
    }

    @Test
    @DisplayName("营收超过 100 整体打 9 折")
    void discountAppliesAboveThreshold() {
        LegacyCustomerOrders orders = new LegacyCustomerOrders(FIXED_EPOCH_MILLIS);
        orders.addOrder("b1", 200d, 1, "NEW");

        assertEquals(200d, orders.totalRevenue(), 1e-9);
        assertEquals(180d, orders.discountedRevenue(), 1e-9);
    }

    // ------------------------------------------------------------------
    // 排序 / 查找
    // ------------------------------------------------------------------

    @Test
    @DisplayName("已发货 id 按字典序升序")
    void shippedIdsSorted() {
        assertEquals(java.util.List.of("o2"), mixedOrders().shippedOrderIds());
    }

    @Test
    @DisplayName("最早订单 id 返回首单；无订单返回 null")
    void oldestOrderId() {
        assertEquals("o1", mixedOrders().oldestOrderId());
        assertNull(new LegacyCustomerOrders(FIXED_EPOCH_MILLIS).oldestOrderId());
    }

    @Test
    @DisplayName("按自然年判断订单存在性")
    void hasOrdersPlacedIn() {
        LegacyCustomerOrders orders = mixedOrders();

        assertTrue(orders.hasOrdersPlacedIn(2023));
        assertFalse(orders.hasOrdersPlacedIn(2024));
    }

    // ------------------------------------------------------------------
    // 计数 / 容错
    // ------------------------------------------------------------------

    @Test
    @DisplayName("各状态订单数统计正确")
    void statusCounts() {
        assertEquals(Map.of("NEW", 1, "SHIPPED", 1, "CANCELLED", 1), mixedOrders().statusCounts());
    }

    @Test
    @DisplayName("空 / null id 被忽略；非法 status 按 NEW 处理")
    void invalidInputsAreTolerated() {
        LegacyCustomerOrders orders = new LegacyCustomerOrders(FIXED_EPOCH_MILLIS);
        orders.addOrder("", 10d, 1, "NEW");
        orders.addOrder(null, 10d, 1, "NEW");
        orders.addOrder("ok", 10d, 1, "WEIRD_STATUS");

        assertEquals(1, orders.orderCount());
        assertEquals(Map.of("NEW", 1), orders.statusCounts());
    }

    // ------------------------------------------------------------------
    // 文本契约
    // ------------------------------------------------------------------

    @Test
    @DisplayName("timestamp 是固定 UTC 文本")
    void timestampUtc() {
        assertEquals(EXPECTED_TIMESTAMP, with("x", 1d, 1, "NEW").timestampUtc());
    }

    @Test
    @DisplayName("summary 文本逐字固定")
    void summaryText() {
        String expected = "Customer Orders Summary\n"
                + "Generated: " + EXPECTED_TIMESTAMP + " (UTC)\n"
                + "Orders: 3\n"
                + "Revenue: $35.00\n";
        assertEquals(expected, mixedOrders().summary());
    }

    @Test
    @DisplayName("report = summary + 已发货清单")
    void reportText() {
        String expected = "Customer Orders Summary\n"
                + "Generated: " + EXPECTED_TIMESTAMP + " (UTC)\n"
                + "Orders: 3\n"
                + "Revenue: $35.00\n"
                + "----\n"
                + "Shipped:\n"
                + "  o2\n";
        assertEquals(expected, mixedOrders().report());
    }

    @Test
    @DisplayName("无参构造器取当前时刻且能正常格式化时间戳（不抛异常）")
    void defaultConstructorUsesNow() {
        LegacyCustomerOrders orders = new LegacyCustomerOrders();

        assertEquals(0, orders.orderCount());
        // 形如 "yyyy-MM-dd HH:mm:ss"，长度 19 且含空格 —— 仅验证无参路径能跑通格式化
        String ts = orders.timestampUtc();
        assertEquals(19, ts.length());
        assertTrue(ts.contains(" "));
    }
}
