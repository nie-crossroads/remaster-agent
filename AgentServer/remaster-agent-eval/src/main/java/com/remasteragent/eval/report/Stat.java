package com.remasteragent.eval.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一组数值的描述统计 —— 均值、中位数、P90。
 *
 * <p>刻意同时给均值与中位数：均值会被一两个「卡住 20 分钟」的任务拉偏，
 * 而中位数会掩盖长尾。两个一起给，看报告的人才能判断「是普遍慢还是个别慢」。
 * P90 则是给「最坏情况」一个不靠极值的估计 —— 极值往往是环境抖动，不是能力问题。
 *
 * <p>{@link #hasSamples()} 是必须的：没有样本时任何统计量都无从谈起，
 * 返回 0 会让人以为「耗时 0 毫秒，快得离谱」。
 */
public record Stat(int samples, double mean, double median, double p90, double min, double max) {

    public static final Stat EMPTY = new Stat(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);

    public boolean hasSamples() {
        return samples > 0;
    }

    /** 数值序列 → 统计。忽略 {@code null}（表示「不知道」，不能当 0）。 */
    public static Stat of(List<Double> values) {
        List<Double> present = new ArrayList<>();
        for (Double value : values) {
            if (value != null) {
                present.add(value);
            }
        }
        if (present.isEmpty()) {
            return EMPTY;
        }
        Collections.sort(present);
        int n = present.size();
        double sum = 0d;
        for (Double value : present) {
            sum += value;
        }
        return new Stat(n, sum / n, percentile(present, 0.50d), percentile(present, 0.90d),
                present.get(0), present.get(n - 1));
    }

    /**
     * 最近秩百分位：{@code index = ceil(p × n) - 1}，下界 0。
     *
     * <p>用最近秩而不是线性插值：报告里的 P90 应当是一个<b>真实发生过的样本</b>，
     * 这样它可以被指着说「这条任务就跑了这么久」，插值出来的数没有对应的任务。
     */
    static double percentile(List<Double> sorted, double p) {
        int n = sorted.size();
        int index = (int) Math.ceil(p * n) - 1;
        return sorted.get(Math.max(0, Math.min(n - 1, index)));
    }

    /** 便于报告里直接显示的一句话，如 {@code 均值 62.4s / 中位 48.0s / P90 141.0s}。 */
    public String text(java.util.function.DoubleFunction<String> formatter) {
        if (!hasSamples()) {
            return "无样本";
        }
        return "均值 " + formatter.apply(mean)
                + " / 中位 " + formatter.apply(median)
                + " / P90 " + formatter.apply(p90);
    }
}
