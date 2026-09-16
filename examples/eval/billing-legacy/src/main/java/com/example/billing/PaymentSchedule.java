package com.example.billing;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 分期付款计划 —— 迁移目标文件之一（日期时间）。
 *
 * <h2>这个类刻意留下的 JDK 8 写法</h2>
 * <ul>
 *   <li>{@link Date} 表示时刻、{@link Calendar} 做加法、{@link SimpleDateFormat} 做格式化</li>
 *   <li>{@code Calendar.DAY_OF_WEEK} 的 1=周日 约定（与 ISO 的 1=周一 不同）</li>
 *   <li>把格式化器藏在一个 {@code static} 字段里 —— 这是 JDK 8 代码里最常见的
 *       线程安全地雷（{@link SimpleDateFormat} 不是线程安全的）；
 *       同一文件里的 {@link #dueDayName(int)} 则是「每次新建」的正确写法，两者构成对照</li>
 *   <li>显式类型实参、下标循环</li>
 * </ul>
 *
 * <h2>为什么留下的两个「坑」都必须被复现，而不是顺手绕开</h2>
 * <p>日期时间迁移最容易出错的不是「换成 LocalDate」，而是这两条语义：
 * <ol>
 *   <li><b>月末加月的钳位</b>：{@code 2024-01-31} 加一个月是 {@code 2024-02-29}（闰年）
 *       还是 {@code 2024-02-28}？{@code Calendar.add(MONTH)} 与
 *       {@code LocalDate.plusMonths()} 都钳位到当月最后一天，语义一致；
 *       但若模型改用「加 30 天」或 {@code Duration.ofDays(30)}，结果就全错。
 *       所以这里保留 {@code Calendar.add} 的原始语义，让测试来钉死它。</li>
 *   <li><b>时区</b>：所有输出固定按 UTC 呈现。同一份数据在不同机器上必须给出同一结果，
 *       否则评测本身就不可复现。</li>
 * </ol>
 *
 * <p>公开 API 只收/发 {@code long} epoch 毫秒，不收 {@link Date} 也不收
 * {@code LocalDate} —— {@link Date} 一旦出现在签名里，换 {@code java.time}
 * 就会被公开 API 护栏拦住；时刻的表示方式是实现细节，不该外溢。
 */
public class PaymentSchedule {

    /** 全部日期按 UTC 呈现。 */
    private static final TimeZone SCHEDULE_ZONE = TimeZone.getTimeZone("UTC");

    private static final String DATE_PATTERN = "yyyy-MM-dd";

    private static final String DAY_NAME_PATTERN = "EEEE";

    /** 这个字段是刻意留下的隐患：{@link SimpleDateFormat} 非线程安全，共享它就是数据竞争。 */
    private static final SimpleDateFormat SHARED_FORMAT =
            new SimpleDateFormat(DATE_PATTERN, Locale.ROOT);

    static {
        // 时区必须显式钉住：不设的话取的是 JVM 默认时区，同一份数据换个机器就换一天。
        SHARED_FORMAT.setTimeZone(SCHEDULE_ZONE);
    }

    private final long startEpochMillis;
    private final int installments;
    private final long installmentCents;

    /**
     * @param startEpochMillis 首期起始时刻（epoch 毫秒，按 UTC 解读）
     * @param installments     期数，必须为正
     * @param installmentCents 每期金额（分），不可为负
     */
    public PaymentSchedule(long startEpochMillis, int installments, long installmentCents) {
        if (installments <= 0) {
            throw new IllegalArgumentException("installments 必须为正: " + installments);
        }
        if (installmentCents < 0) {
            throw new IllegalArgumentException("installmentCents 不可为负: " + installmentCents);
        }
        this.startEpochMillis = startEpochMillis;
        this.installments = installments;
        this.installmentCents = installmentCents;
    }

    public long startEpochMillis() {
        return startEpochMillis;
    }

    public int installments() {
        return installments;
    }

    public long installmentCents() {
        return installmentCents;
    }

    /** 总金额（分）= 期数 × 每期金额。 */
    public long totalCents() {
        return Double.valueOf(installmentCents * (double) installments).longValue();
    }

    /** 起始日（UTC），形如 {@code 2024-01-31}。 */
    public String startDateUtc() {
        return format(startEpochMillis);
    }

    /**
     * 起始时刻是否落在给定的自然月内。
     *
     * @param month 从 1 开始，不是 {@link Calendar#MONTH} 那种从 0 开始的偏移量
     */
    public boolean startsInMonth(int year, int month) {
        Calendar calendar = Calendar.getInstance(SCHEDULE_ZONE);
        calendar.setTime(new Date(startEpochMillis));
        return calendar.get(Calendar.YEAR) == year && calendar.get(Calendar.MONTH) + 1 == month;
    }

    /**
     * 第 {@code index} 期的到期日（UTC，0 基）。
     *
     * <p>按月逐期递增并<b>钳位到月末</b>：{@code 2024-01-31} 的第 1 期是
     * {@code 2024-02-29}，{@code 2023-01-31} 的第 1 期是 {@code 2023-02-28}。
     *
     * @throws IndexOutOfBoundsException index 不在 {@code [0, installments)} 内
     */
    public String dueDateUtc(int index) {
        checkIndex(index);
        Calendar calendar = Calendar.getInstance(SCHEDULE_ZONE);
        calendar.setTime(new Date(startEpochMillis));
        calendar.add(Calendar.MONTH, index);
        return format(calendar.getTimeInMillis());
    }

    /**
     * 第 {@code index} 期到期日是星期几，<b>ISO 口径</b>：1 = 周一 … 7 = 周日。
     *
     * <p>注意 {@link Calendar#DAY_OF_WEEK} 是从 1 = 周日 开始的，两者差一天，
     * 转换写错是这一块最常见的 bug —— 所以这里不做转换而是让测试钉死 ISO 口径。
     *
     * @throws IndexOutOfBoundsException index 不在 {@code [0, installments)} 内
     */
    public int dueDayOfWeekIso(int index) {
        checkIndex(index);
        Calendar calendar = Calendar.getInstance(SCHEDULE_ZONE);
        calendar.setTime(new Date(startEpochMillis));
        calendar.add(Calendar.MONTH, index);
        int calendarDow = calendar.get(Calendar.DAY_OF_WEEK);
        return ((calendarDow + 5) % 7) + 1;
    }

    /**
     * 第 {@code index} 期到期日的英文星期名，如 {@code Wednesday}。
     *
     * <p>这里有两个刻意的选择：
     * <ol>
     *   <li><b>用 {@code EEEE} 模式串，而不是
     *       {@code Calendar.getDisplayName(DAY_OF_WEEK, LONG, locale)}</b> ——
     *       后者即便传 {@code LONG} 也只给缩写（{@code Wed}），与名字对不上。</li>
     *   <li><b>Locale 用 {@link Locale#ENGLISH} 而不是 {@link Locale#ROOT}</b> ——
     *       在这台 JDK 上，{@code Locale.ROOT} 的 CLDR 星期名本身就是<b>缩写</b>
     *       （{@code EEEE} 也只给 {@code Tue}），只有 {@code ENGLISH} 才给全称。
     *       这一点很容易被误当成「模型改错了」：迁移到 {@code DateTimeFormatter}
     *       或 {@code DayOfWeek.getDisplayName(FULL, locale)} 时，
     *       若把 locale 从 ENGLISH 顺手改回 ROOT，输出就会从 {@code Tuesday} 变成 {@code Tue}。
     *       语言标签本身就是约定的一部分，不是可随手简化的噪音。</li>
     * </ol>
     */
    public String dueDayName(int index) {
        checkIndex(index);
        Calendar calendar = Calendar.getInstance(SCHEDULE_ZONE);
        calendar.setTime(new Date(startEpochMillis));
        calendar.add(Calendar.MONTH, index);
        // 与上面那个共享的 static 字段形成对照：格式化器本该每次新建。
        SimpleDateFormat formatter = new SimpleDateFormat(DAY_NAME_PATTERN, Locale.ENGLISH);
        formatter.setTimeZone(SCHEDULE_ZONE);
        return formatter.format(calendar.getTime());
    }

    /** 全部到期日，按顺序。 */
    public List<String> allDueDates() {
        List<String> dates = new ArrayList<String>();
        for (int i = 0; i < installments; i++) {
            dates.add(dueDateUtc(i));
        }
        return dates;
    }

    /** 计划文本。多行固定格式。 */
    public String describe() {
        StringBuilder builder = new StringBuilder();
        builder.append("Payment schedule\n");
        builder.append("  start: ").append(startDateUtc()).append(" (UTC)\n");
        builder.append("  installments: ").append(installments).append('\n');
        for (int i = 0; i < installments; i++) {
            builder.append("  #").append(i + 1).append(" due ")
                    .append(dueDateUtc(i)).append(" (").append(dueDayName(i)).append(")\n");
        }
        builder.append("  total: ").append(totalCents()).append(" cents");
        return builder.toString();
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= installments) {
            throw new IndexOutOfBoundsException("index 越界: " + index + " / " + installments);
        }
    }

    /** 共享那个 static 格式化器 —— 正是要暴露出来的写法。 */
    private static String format(long epochMillis) {
        return SHARED_FORMAT.format(new Date(epochMillis));
    }
}
