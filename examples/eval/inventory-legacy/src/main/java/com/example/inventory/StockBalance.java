package com.example.inventory;

import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

/**
 * 库存结存台账 —— JDK 1.1 时代写法的标本。
 *
 * <p>迁移目标：{@link Hashtable} → {@code HashMap}，{@link Vector} → {@code ArrayList}，
 * {@link Enumeration} 遍历 → for-each / Stream。
 *
 * <h2>为什么这些方法签名要这么定</h2>
 * <p>{@link #auditTrail()} 返回的是 {@code String[]} 而不是内部的 {@link Vector}：
 * 返回内部容器等于把可变状态送出去，调用方能绕开 {@code receive/issue} 直接改台账。
 * 这条约束被单测钉住 —— 迁移时如果把返回类型改成 {@code List<String>} 并且
 * 直接把内部 list 交出去，{@code auditTrailIsDefensiveCopy} 会立刻红。
 *
 * <p>{@link #lowStockSkus(int)} 的结果排序：先按库存升序、再按 sku 字典序，
 * 保证输出与 {@code Hashtable} / {@code HashMap} 的迭代顺序无关 ——
 * 这两种容器的迭代顺序一般不同，若放任不管，「模型改对了」也会假失败。
 */
public class StockBalance {

    private final Hashtable<String, Integer> onHand = new Hashtable<String, Integer>();
    private final Vector<String> auditTrail = new Vector<String>();

    /** 入库。{@code qty} 必须为正。 */
    public void receive(String sku, int qty) {
        requirePositive(qty, "入库数量");
        Integer current = onHand.get(sku);
        int next = (current == null ? 0 : current.intValue()) + qty;
        onHand.put(sku, Integer.valueOf(next));
        auditTrail.addElement("RECEIVE " + sku + " +" + qty);
    }

    /**
     * 出库。库存不足时抛 {@link IllegalStateException} 且<b>不改动任何状态</b> ——
     * 「先扣后查」的写法会留下负数库存，这是旧代码里最常见的错。
     */
    public void issue(String sku, int qty) {
        requirePositive(qty, "出库数量");
        Integer current = onHand.get(sku);
        int available = current == null ? 0 : current.intValue();
        if (available < qty) {
            throw new IllegalStateException(sku + " 库存不足: 现有 " + available + "，需要 " + qty);
        }
        onHand.put(sku, Integer.valueOf(available - qty));
        auditTrail.addElement("ISSUE " + sku + " -" + qty);
    }

    /** 当前库存；从未出现过的 sku 返回 0（而不是 null）。 */
    public int onHand(String sku) {
        Integer value = onHand.get(sku);
        return value == null ? 0 : value.intValue();
    }

    /** 全部 sku 的库存合计。 */
    public int totalOnHand() {
        int total = 0;
        Enumeration<Integer> values = onHand.elements();
        while (values.hasMoreElements()) {
            total += values.nextElement().intValue();
        }
        return total;
    }

    /** 出现过的 sku 个数。 */
    public int skuCount() {
        return onHand.size();
    }

    /** 操作流水（防御性拷贝）。 */
    public String[] auditTrail() {
        String[] copy = new String[auditTrail.size()];
        auditTrail.copyInto(copy);
        return copy;
    }

    /** 流水拼成一行，用 {@code " | "} 分隔 —— 循环内用 {@code +=} 拼接是这里的「坏味道」。 */
    public String auditTrailAsText() {
        String text = "";
        for (int i = 0; i < auditTrail.size(); i++) {
            if (i > 0) {
                text += " | ";
            }
            text += auditTrail.elementAt(i);
        }
        return text;
    }

    /**
     * 库存低于 {@code threshold}（不含）的 sku，按「库存升序 + sku 字典序」排列。
     */
    public String[] lowStockSkus(int threshold) {
        Vector<String> hits = new Vector<String>();
        Enumeration<String> keys = onHand.keys();
        while (keys.hasMoreElements()) {
            String sku = keys.nextElement();
            if (onHand.get(sku).intValue() < threshold) {
                hits.addElement(sku);
            }
        }
        String[] array = new String[hits.size()];
        hits.copyInto(array);
        // 插入排序：库存升序，库存相同按 sku 字典序。
        for (int i = 1; i < array.length; i++) {
            String current = array[i];
            int j = i - 1;
            while (j >= 0 && comesBefore(current, array[j])) {
                array[j + 1] = array[j];
                j--;
            }
            array[j + 1] = current;
        }
        return array;
    }

    private boolean comesBefore(String left, String right) {
        int leftQty = onHand.get(left).intValue();
        int rightQty = onHand.get(right).intValue();
        if (leftQty != rightQty) {
            return leftQty < rightQty;
        }
        return left.compareTo(right) < 0;
    }

    private static void requirePositive(int qty, String what) {
        if (qty <= 0) {
            throw new IllegalArgumentException(what + "必须为正: " + qty);
        }
    }
}
