package com.example.mm.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;

/**
 * 金额汇总 —— 遗留写法样本（legacy-core 模块，编译级别继承根 pom 的 JDK 8）。
 *
 * <p>刻意保留的老写法：{@code Vector} / {@code Hashtable}、显式装箱、手写 {@code Iterator} 循环、
 * 逐段 {@code StringBuilder} 拼接，以及 {@code Collections.unmodifiableList(Arrays.asList(...))}
 * 这种「JDK 8 时代没有集合工厂方法」的产物。
 *
 * <p>最后那一处是本工程的关键：改写器若按 JDK 21 惯用法把它换成 {@code List.of(...)}，
 * 那么<b>编译级别必须同时升到 9 以上</b>，否则 javac 直接拒绝 ——
 * 「构建描述没升」与「代码改错了」在编译器眼里长得一模一样，这正是 POM_REWRITE 存在的理由。
 */
public class PriceTally {

    private final Vector<Double> amounts = new Vector<Double>();

    private final Hashtable<String, Double> byCategory = new Hashtable<String, Double>();

    /** 记一笔。金额用 double 传进来、显式装箱存起来。 */
    public void add(String category, double amount) {
        Double boxed = new Double(amount);
        amounts.add(boxed);
        Double current = byCategory.get(category);
        if (current == null) {
            byCategory.put(category, boxed);
        } else {
            byCategory.put(category, new Double(current.doubleValue() + amount));
        }
    }

    /** 合计。手写迭代器，不用 for-each。 */
    public double total() {
        double sum = 0d;
        for (Iterator<Double> it = amounts.iterator(); it.hasNext(); ) {
            Double value = it.next();
            sum = sum + value.doubleValue();
        }
        return sum;
    }

    /** 分类小计（返回副本，调用方改不到内部状态）。 */
    public Map<String, Double> totalsByCategory() {
        return new Hashtable<String, Double>(byCategory);
    }

    /** 一句话汇总。 */
    public String summary() {
        StringBuilder builder = new StringBuilder();
        builder.append("共 ");
        builder.append(amounts.size());
        builder.append(" 笔，合计 ");
        builder.append(total());
        return builder.toString();
    }

    /**
     * 默认分类（只读、有序）。
     *
     * <p>契约：长度为 3、顺序是 food / transport / other、且<b>不可修改</b>。
     * 测试只断言这三条可观测行为，不管内部是用 {@code Collections.unmodifiableList}
     * 还是 {@code List.of} 实现的。
     */
    public static List<String> defaultCategories() {
        return Collections.unmodifiableList(Arrays.asList("food", "transport", "other"));
    }
}
