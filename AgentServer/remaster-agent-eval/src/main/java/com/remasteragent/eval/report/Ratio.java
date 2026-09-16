package com.remasteragent.eval.report;

import java.util.OptionalDouble;

/**
 * 一个比率 —— 分子、分母，以及「分母是不是 0」这件事本身。
 *
 * <h2>为什么不能直接返回 double</h2>
 * <p>这个项目在前端已经为同一件事付出过代价：分母为 0 时显示 {@code 0%}，
 * 看的人会理解成「一个都没通过」，而真实情况是「一个样本都没有」。
 * 两者是完全相反的结论，却长得一模一样。
 *
 * <p>所以这里把分母留在类型里：{@link #rate()} 返回 {@link OptionalDouble}，
 * 没有样本时是空的；{@link #text()} 直接给「无样本」。
 * 想算出「通过率 0%」必须显式地走过 {@link #hasSamples()} 这一关 ——
 * 让撒谎变成一个需要额外动作的选择，而不是默认值。
 */
public record Ratio(int numerator, int denominator) {

    public static final Ratio EMPTY = new Ratio(0, 0);

    public boolean hasSamples() {
        return denominator > 0;
    }

    /** 有样本才有比率。 */
    public OptionalDouble rate() {
        return hasSamples() ? OptionalDouble.of((double) numerator / denominator) : OptionalDouble.empty();
    }

    /** {@code 51/51 (100.0%)}；无样本时是 {@code 无样本}。 */
    public String text() {
        if (!hasSamples()) {
            return "无样本";
        }
        return numerator + "/" + denominator + " (" + percent() + "%)";
    }

    /** 百分数，保留一位小数。仅在 {@link #hasSamples()} 为真时有意义。 */
    public String percent() {
        return String.format(java.util.Locale.ROOT, "%.1f", 100.0d * numerator / denominator);
    }
}
