package com.example.billing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * 税率规则引擎 —— 迁移目标文件之一（匿名内部类 / 字符串拼接 / 显式泛型）。
 *
 * <h2>这个类刻意留下的 JDK 8 写法</h2>
 * <ul>
 *   <li>{@link Comparator} <b>匿名内部类</b> → lambda / {@code Comparator.comparingLong} 方法引用</li>
 *   <li>{@code Collections.sort(list, cmp)} → {@code list.sort(cmp)}</li>
 *   <li>循环内 {@code String} {@code +} 拼接多行文本 → text block / {@code String.join}</li>
 *   <li>{@link Iterator} + {@code while} 遍历 → for-each / Stream</li>
 *   <li>显式类型实参与显式装箱（{@code Long.valueOf}）</li>
 * </ul>
 *
 * <h2>一条设计上的自律：排序必须是全序</h2>
 * <p>{@link #bracketsAscending()} 只按 {@code fromCents} 排，若两个档位起点相同，
 * 排出来的相对顺序就取决于 {@link Collections#sort} 的稳定性（它稳定，所以按插入序）。
 * 这是<b>可观测</b>的，只是没人会去依赖它。为了让迁移后的写法（换成 Stream 或换一种比较器）
 * 不会因为「排序是否稳定」而假失败，这里的比较器被写成<b>全序</b>：
 * 先比 {@code fromCents}，再比 {@code rateBasisPoints}，最后比插入序号。
 * 于是无论底层排序稳定与否，输出都唯一 —— 这是样本设计要负的责任，不该让模型去猜。
 *
 * <p>公开方法签名与 {@link #describe()} 的输出文本是可观测契约，迁移后必须逐字保持不变。
 */
public class TaxRuleEngine {

    private final List<Bracket> brackets = new ArrayList<Bracket>();

    /**
     * 追加一个税率档位，起点为闭区间下界。
     *
     * @param fromCents        档位起点（分），不可为负
     * @param rateBasisPoints  税率，1 bp = 0.01%，取值 {@code [0, 10000]}
     */
    public void addBracket(long fromCents, int rateBasisPoints) {
        if (fromCents < 0) {
            throw new IllegalArgumentException("fromCents 不可为负: " + fromCents);
        }
        if (rateBasisPoints < 0 || rateBasisPoints > 10000) {
            throw new IllegalArgumentException("rateBasisPoints 越界: " + rateBasisPoints);
        }
        brackets.add(new Bracket(fromCents, rateBasisPoints, brackets.size()));
    }

    public int bracketCount() {
        return brackets.size();
    }

    /** 全部档位按起点升序，每项形如 {@code from=0 bps=500}。 */
    public List<String> bracketsAscending() {
        List<Bracket> sorted = sortedBrackets();
        List<String> rendered = new ArrayList<String>();
        for (int i = 0; i < sorted.size(); i++) {
            Bracket bracket = sorted.get(i);
            rendered.add("from=" + bracket.fromCents() + " bps=" + bracket.rateBasisPoints());
        }
        return rendered;
    }

    /**
     * 按适用档位计算税额（分），小数部分<b>向下取整</b>。
     *
     * <p>适用档位 = 起点不超过应税金额的<b>最高</b>那档。若所有档位起点都高于应税金额，
     * 说明没有规则命中，税额为 0（而不是抛异常 —— 零申报是正常业务状态）。
     *
     * @throws IllegalArgumentException taxableCents 为负
     */
    public long taxFor(long taxableCents) {
        if (taxableCents < 0) {
            throw new IllegalArgumentException("taxableCents 不可为负: " + taxableCents);
        }
        Bracket applicable = null;
        Iterator<Bracket> iterator = sortedBrackets().iterator();
        while (iterator.hasNext()) {
            Bracket bracket = iterator.next();
            if (bracket.fromCents() <= taxableCents) {
                applicable = bracket;
            } else {
                break;
            }
        }
        if (applicable == null) {
            return 0L;
        }
        return Long.valueOf(taxableCents * (long) applicable.rateBasisPoints() / 10000L).longValue();
    }

    /** 规则文本。多行固定格式。 */
    public String describe() {
        String text = "Tax rules (" + bracketCount() + " brackets)";
        List<String> rows = bracketsAscending();
        for (int i = 0; i < rows.size(); i++) {
            text = text + "\n  " + rows.get(i);
        }
        return text;
    }

    /** 排好序的档位副本。 */
    private List<Bracket> sortedBrackets() {
        List<Bracket> copy = new ArrayList<Bracket>(brackets);
        Collections.sort(copy, new Comparator<Bracket>() {
            @Override
            public int compare(Bracket left, Bracket right) {
                int byFrom = Long.compare(left.fromCents(), right.fromCents());
                if (byFrom != 0) {
                    return byFrom;
                }
                int byRate = Integer.compare(left.rateBasisPoints(), right.rateBasisPoints());
                if (byRate != 0) {
                    return byRate;
                }
                return Integer.compare(left.sequence(), right.sequence());
            }
        });
        return copy;
    }

    /** 一个税率档位。 */
    private static final class Bracket {

        private final long fromCents;
        private final int rateBasisPoints;
        private final int sequence;

        private Bracket(long fromCents, int rateBasisPoints, int sequence) {
            this.fromCents = fromCents;
            this.rateBasisPoints = rateBasisPoints;
            this.sequence = sequence;
        }

        private long fromCents() {
            return fromCents;
        }

        private int rateBasisPoints() {
            return rateBasisPoints;
        }

        private int sequence() {
            return sequence;
        }
    }
}
