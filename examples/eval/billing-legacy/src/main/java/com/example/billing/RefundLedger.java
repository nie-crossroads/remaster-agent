package com.example.billing;

import java.util.Comparator;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.Stack;
import java.util.Vector;

/**
 * 退款台账 —— 迁移目标文件之一（旧集合实现）。
 *
 * <h2>这个类刻意留下的 JDK 8 写法</h2>
 * <ul>
 *   <li>{@link Hashtable} → {@code HashMap}</li>
 *   <li>{@link Vector} → {@code ArrayList}</li>
 *   <li>{@link Stack} 作为待复核队列 → {@code ArrayDeque}</li>
 *   <li>{@link Enumeration} + {@code while} 遍历 → for-each / Stream</li>
 *   <li>{@link Comparator} 匿名内部类、显式类型实参、显式装箱</li>
 * </ul>
 *
 * <h2>为什么排序里有 tie-break（这一点决定了这道题公不公平）</h2>
 * <p>{@link #ordersByAmountDesc()} 若只按金额排，那么金额相同的两笔订单先后就取决于
 * <b>底层 map 的迭代顺序</b>。而 {@link Hashtable} 与 {@code HashMap} 的迭代顺序
 * 在一般情况下<b>并不相同</b> —— 于是「迁移成功但报告失败」会发生在模型做对了每一件事的时候。
 *
 * <p>所以比较器被写成全序：金额降序 → 订单号升序。这样输出与底层容器无关，
 * 「换掉 Hashtable」才不会顺带换掉输出。样本设计里这类「隐藏依赖」必须提前掐掉，
 * 否则它污染的是指标，不是模型的分数。
 *
 * <p>公开方法签名与 {@link #summary()} 的输出文本是可观测契约，迁移后必须逐字保持不变。
 */
public class RefundLedger {

    /** 各订单已退款金额（分）。 */
    private final Hashtable<String, Long> refunded = new Hashtable<String, Long>();

    /** 订单号，按首次出现顺序。 */
    private final Vector<String> orders = new Vector<String>();

    /** 待人工复核的订单号，后进先出。 */
    private final Stack<String> reviewQueue = new Stack<String>();

    /**
     * 记一笔退款。
     *
     * @param amountCents 本次退款金额（分），不可为负；同一订单可多次记录，金额累加
     * @throws IllegalArgumentException orderId 为空或 amountCents 为负
     */
    public void record(String orderId, long amountCents) {
        if (orderId == null || orderId.isEmpty()) {
            throw new IllegalArgumentException("orderId 不能为空");
        }
        if (amountCents < 0) {
            throw new IllegalArgumentException("amountCents 不可为负: " + amountCents);
        }
        Long current = refunded.get(orderId);
        long base = current == null ? 0L : current.longValue();
        if (current == null) {
            orders.add(orderId);
        }
        refunded.put(orderId, Long.valueOf(base + amountCents));
    }

    /** 指定订单的累计退款额；从未出现过的订单返回 0。 */
    public long refundedFor(String orderId) {
        Long value = refunded.get(orderId);
        return value == null ? 0L : value.longValue();
    }

    public int orderCount() {
        return refunded.size();
    }

    /** 全部订单的退款额合计。 */
    public long totalRefundedCents() {
        long sum = 0L;
        Enumeration<String> keys = refunded.keys();
        while (keys.hasMoreElements()) {
            sum += refunded.get(keys.nextElement()).longValue();
        }
        return sum;
    }

    /** 按首次出现顺序返回订单号。 */
    public List<String> ordersInInsertionOrder() {
        return new Vector<String>(orders);
    }

    /** 送入人工复核队列。 */
    public void requestReview(String orderId) {
        if (orderId == null || orderId.isEmpty()) {
            throw new IllegalArgumentException("orderId 不能为空");
        }
        reviewQueue.push(orderId);
    }

    /** 取出下一个待复核订单（后进先出）；队列为空返回 {@code null}。 */
    public String popNextForReview() {
        if (reviewQueue.isEmpty()) {
            return null;
        }
        return reviewQueue.pop();
    }

    public int pendingReviewCount() {
        return reviewQueue.size();
    }

    /** 订单号按退款额降序；金额相同时按订单号升序（见类注释：必须全序）。 */
    public List<String> ordersByAmountDesc() {
        Vector<String> copy = new Vector<String>(refunded.keySet());
        copy.sort(new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                int byAmount = Long.compare(refundedFor(right), refundedFor(left));
                if (byAmount != 0) {
                    return byAmount;
                }
                return left.compareTo(right);
            }
        });
        return copy;
    }

    /** 台账文本。多行固定格式。 */
    public String summary() {
        StringBuilder builder = new StringBuilder();
        builder.append("Refund ledger\n");
        builder.append("  orders: ").append(orderCount()).append('\n');
        builder.append("  total: ").append(totalRefundedCents()).append(" cents\n");
        builder.append("  pending review: ").append(pendingReviewCount());
        return builder.toString();
    }
}
