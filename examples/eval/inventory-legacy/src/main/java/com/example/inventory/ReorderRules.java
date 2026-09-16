package com.example.inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 补货规则 —— 匿名内部类实现的谓词过滤（JDK 8 之前没有 {@code java.util.function.Predicate}）。
 *
 * <p>迁移目标：自定义 {@link Filter} 接口 + 匿名内部类 → {@code Predicate<T>} + lambda /
 * 方法引用；{@link Collections#sort} + {@link Comparator} 匿名类 →
 * {@code Comparator.comparing(...).thenComparing(...)}；装箱 → {@code Integer} 自动装箱。
 *
 * <h2>为什么要自带一个 {@link Filter} 接口</h2>
 * <p>这是这段旧代码的「时代指纹」：JDK 8 之前想传一个条件进来，只能自己定义接口。
 * 它也顺带制造了一个真实的迁移决策 —— 直接换成 {@code Predicate} 会动到公开签名
 * （{@link #skusNeedingReorder(Filter)} 的参数类型），而评测的护栏禁止改公开签名。
 * 所以正确迁移是：<b>保留 {@link Filter} 作为对外契约，内部实现换成 lambda</b>。
 * 这条约束被 {@code filterInterfaceIsPreserved} 钉住 ——
 * 它保证「现代化」不等于「把 API 推倒重来」。
 */
public class ReorderRules {

    /** 条件接口 —— JDK 8 之前的标准做法。迁移时<b>必须保留</b>（公开签名不能动）。 */
    public interface Filter {
        boolean accept(String sku);
    }

    private final List<Rule> rules = new ArrayList<Rule>();

    /** 增加一条补货规则：{@code sku} 的库存低于 {@code minQty} 时补到 {@code targetQty}。 */
    public void addRule(String sku, int minQty, int targetQty) {
        if (minQty > targetQty) {
            throw new IllegalArgumentException(sku + " 的安全库存不能高于目标库存: "
                    + minQty + " > " + targetQty);
        }
        rules.add(new Rule(sku, Integer.valueOf(minQty), Integer.valueOf(targetQty)));
    }

    public int ruleCount() {
        return rules.size();
    }

    /**
     * 需要补货的 sku —— 先按 {@code filter} 过滤，再按 sku 字典序返回。
     *
     * <p>排序是刻意的：这样输出与 {@code List} 的插入顺序无关，
     * 「模型把内部容器换了」这件事本身不会改变结果。
     */
    public String[] skusNeedingReorder(Filter filter) {
        List<String> hits = new ArrayList<String>();
        for (int i = 0; i < rules.size(); i++) {
            Rule rule = rules.get(i);
            if (filter == null || filter.accept(rule.sku)) {
                hits.add(rule.sku);
            }
        }
        Collections.sort(hits, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                return left.compareTo(right);
            }
        });
        String[] result = new String[hits.size()];
        for (int i = 0; i < hits.size(); i++) {
            result[i] = hits.get(i);
        }
        return result;
    }

    /**
     * 需要补的数量：目标库存减现有库存，结果为负时返回 0（不倒扣）。
     *
     * <p>没有对应规则的 sku 返回 0 —— 「没配规则」不等于「需要补货」。
     */
    public int reorderQuantity(String sku, int currentOnHand) {
        Rule rule = find(sku);
        if (rule == null) {
            return 0;
        }
        int gap = rule.targetQty.intValue() - currentOnHand;
        return gap > 0 ? gap : 0;
    }

    /** 是否触发了补货线（库存低于安全库存）。 */
    public boolean belowSafetyStock(String sku, int currentOnHand) {
        Rule rule = find(sku);
        return rule != null && currentOnHand < rule.minQty.intValue();
    }

    /** 所有规则的文字清单，按 sku 字典序，形如 {@code SKU-A(min 10 → 50)}。 */
    public String describe() {
        List<Rule> sorted = new ArrayList<Rule>(rules);
        Collections.sort(sorted, new Comparator<Rule>() {
            @Override
            public int compare(Rule left, Rule right) {
                return left.sku.compareTo(right.sku);
            }
        });
        String text = "";
        for (int i = 0; i < sorted.size(); i++) {
            Rule rule = sorted.get(i);
            if (i > 0) {
                text += "; ";
            }
            text += rule.sku + "(min " + rule.minQty + " → " + rule.targetQty + ")";
        }
        return text;
    }

    private Rule find(String sku) {
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).sku.equals(sku)) {
                return rules.get(i);
            }
        }
        return null;
    }

    /** 一条规则。这里的显式 {@code Integer.valueOf} 装箱是旧写法的一部分。 */
    private static final class Rule {
        private final String sku;
        private final Integer minQty;
        private final Integer targetQty;

        private Rule(String sku, Integer minQty, Integer targetQty) {
            this.sku = sku;
            this.minQty = minQty;
            this.targetQty = targetQty;
        }
    }
}
