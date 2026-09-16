package com.example.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link RefundLedger} 的行为契约 —— 迁移前后必须逐条为真。
 *
 * <p>这个类换掉的是 {@code Hashtable} / {@code Vector} / {@code Stack} 三种旧容器，
 * 所以断言里有两条是专门为「换容器」这件事准备的：
 * <ol>
 *   <li><b>顺序不能依赖底层 map。</b>{@code Hashtable} 与 {@code HashMap} 的迭代顺序
 *       一般不同。所以 {@link #sameAmountTiesAreOrderedById()} 用两笔<b>金额相同</b>的
 *       订单来验证输出仍然是确定的 —— 如果实现依赖了迭代顺序，这条会红，而那是实现的问题，
 *       不是模型改错了。</li>
 *   <li><b>LIFO 语义。</b>{@code Stack.pop()} 换成 {@code ArrayDeque.pop()} 是一对一替换，
 *       但换成 {@code ArrayDeque.removeFirst()} 就变成了队列。所以这里按 1-2-3 入队、
 *       按 3-2-1 出队，把顺序钉住。</li>
 * </ol>
 *
 * <p>金额一律整数分，断言用精确值而不是浮点容差。
 */
class RefundLedgerTest {

    // ------------------------------------------------------------------
    // 台账记账
    // ------------------------------------------------------------------

    @Test
    @DisplayName("空台账：订单数 0、合计 0、查不到任何订单")
    void emptyLedger() {
        RefundLedger ledger = new RefundLedger();

        assertEquals(0, ledger.orderCount());
        assertEquals(0L, ledger.totalRefundedCents());
        assertEquals(0L, ledger.refundedFor("NOPE"), "查不到的订单返 0，不抛异常");
        assertEquals(List.of(), ledger.ordersInInsertionOrder());
        assertEquals(List.of(), ledger.ordersByAmountDesc());
    }

    @Test
    @DisplayName("订单号为空、金额为负都抛 IllegalArgumentException")
    void invalidInputsAreRejected() {
        RefundLedger ledger = new RefundLedger();

        assertThrows(IllegalArgumentException.class, () -> ledger.record("", 100L));
        assertThrows(IllegalArgumentException.class, () -> ledger.record(null, 100L));
        assertThrows(IllegalArgumentException.class, () -> ledger.record("A", -1L));
        assertEquals(0, ledger.orderCount());
    }

    @Test
    @DisplayName("同一订单多次退款累加，只占一个订单数")
    void repeatedRefundsAccumulate() {
        RefundLedger ledger = new RefundLedger();
        ledger.record("A", 100L);
        ledger.record("B", 250L);
        ledger.record("A", 400L);

        assertEquals(2, ledger.orderCount());
        assertEquals(500L, ledger.refundedFor("A"));
        assertEquals(250L, ledger.refundedFor("B"));
        assertEquals(750L, ledger.totalRefundedCents());
    }

    @Test
    @DisplayName("金额为 0 的退款是合法的，且仍然登记该订单")
    void zeroAmountStillRegistersOrder() {
        RefundLedger ledger = new RefundLedger();
        ledger.record("A", 0L);

        assertEquals(1, ledger.orderCount());
        assertEquals(0L, ledger.refundedFor("A"));
    }

    @Test
    @DisplayName("订单按首次出现顺序返回（不是按金额）")
    void ordersKeepInsertionOrder() {
        RefundLedger ledger = new RefundLedger();
        ledger.record("C", 10L);
        ledger.record("A", 900L);
        ledger.record("B", 50L);

        assertEquals(List.of("C", "A", "B"), ledger.ordersInInsertionOrder());
    }

    @Test
    @DisplayName("返回值是快照：取出后再记新订单，已取出的列表不变")
    void ordersInInsertionOrderIsSnapshot() {
        RefundLedger ledger = new RefundLedger();
        ledger.record("A", 10L);

        List<String> snapshot = ledger.ordersInInsertionOrder();
        ledger.record("B", 20L);

        assertEquals(List.of("A"), snapshot);
        assertEquals(List.of("A", "B"), ledger.ordersInInsertionOrder());
    }

    // ------------------------------------------------------------------
    // 排序（必须与底层容器无关）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("按退款额降序")
    void sortedByAmountDescending() {
        RefundLedger ledger = new RefundLedger();
        ledger.record("A", 100L);
        ledger.record("B", 900L);
        ledger.record("C", 500L);

        assertEquals(List.of("B", "C", "A"), ledger.ordersByAmountDesc());
    }

    @Test
    @DisplayName("金额相同时按订单号升序 —— 输出不依赖底层 map 的迭代顺序")
    void sameAmountTiesAreOrderedById() {
        RefundLedger ledger = new RefundLedger();
        ledger.record("B", 500L);
        ledger.record("C", 900L);
        ledger.record("A", 500L);
        ledger.record("D", 500L);

        assertEquals(List.of("C", "A", "B", "D"), ledger.ordersByAmountDesc());
    }

    // ------------------------------------------------------------------
    // 复核队列（LIFO）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("复核队列后进先出")
    void reviewQueueIsLifo() {
        RefundLedger ledger = new RefundLedger();
        ledger.record("A", 10L);
        ledger.record("B", 10L);
        ledger.record("C", 10L);

        ledger.requestReview("A");
        ledger.requestReview("B");
        ledger.requestReview("C");

        assertEquals(3, ledger.pendingReviewCount());
        assertEquals("C", ledger.popNextForReview());
        assertEquals("B", ledger.popNextForReview());
        assertEquals(1, ledger.pendingReviewCount());
        assertEquals("A", ledger.popNextForReview());
        assertEquals(0, ledger.pendingReviewCount());
    }

    @Test
    @DisplayName("复核队列为空时返回 null，不抛异常")
    void poppingEmptyReviewQueueReturnsNull() {
        RefundLedger ledger = new RefundLedger();

        assertNull(ledger.popNextForReview());
        assertEquals(0, ledger.pendingReviewCount());
    }

    @Test
    @DisplayName("同一订单可重复送复核，入队次数各自计入")
    void reviewQueueCountsDuplicates() {
        RefundLedger ledger = new RefundLedger();
        ledger.record("A", 10L);
        ledger.requestReview("A");
        ledger.requestReview("A");

        assertEquals(2, ledger.pendingReviewCount());
        assertEquals("A", ledger.popNextForReview());
        assertEquals("A", ledger.popNextForReview());
        assertNull(ledger.popNextForReview());
    }

    // ------------------------------------------------------------------
    // 文本
    // ------------------------------------------------------------------

    @Test
    @DisplayName("summary() 是固定格式的多行文本")
    void summaryIsFixedText() {
        RefundLedger ledger = new RefundLedger();
        ledger.record("A", 1_000L);
        ledger.record("B", 500L);
        ledger.requestReview("A");

        assertEquals(""
                        + "Refund ledger\n"
                        + "  orders: 2\n"
                        + "  total: 1500 cents\n"
                        + "  pending review: 1",
                ledger.summary());
    }

    @Test
    @DisplayName("空台账的 summary() 也是固定格式")
    void emptySummaryIsFixedText() {
        assertEquals(""
                        + "Refund ledger\n"
                        + "  orders: 0\n"
                        + "  total: 0 cents\n"
                        + "  pending review: 0",
                new RefundLedger().summary());
    }
}
