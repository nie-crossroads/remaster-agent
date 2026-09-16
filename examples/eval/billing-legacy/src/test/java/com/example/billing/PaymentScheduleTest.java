package com.example.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PaymentSchedule} 的行为契约 —— 迁移前后必须逐条为真。
 *
 * <h2>这里钉的是「语义」不是「API」</h2>
 * <p>日期时间的迁移有两处极易悄悄改变行为，普通单测看不出来，所以本类专门为它们建了用例：
 * <ol>
 *   <li><b>月末加月的钳位</b>：{@code 2024-01-31} 加一个月。{@code Calendar.add(MONTH, 1)}
 *       与 {@code LocalDate.plusMonths(1)} 都给 {@code 2024-02-29}；
 *       但 {@code plusDays(30)} / {@code Duration.ofDays(30)} 给的是 {@code 2024-03-01}。
 *       闰年与非闰年各测一次，把「加月」这个语义钉死。</li>
 *   <li><b>星期口径</b>：{@link java.util.Calendar#DAY_OF_WEEK} 是 1 = 周日，
 *       而 ISO 是 1 = 周一。转换少写一位就会整整错一天，且只在跨周日的用例上暴露。</li>
 * </ol>
 *
 * <p>所有时刻都用固定 epoch 毫秒注入，日期断言可以直接写死字符串 —— 不依赖当前时间、
 * 不依赖机器默认时区（本类全部按 UTC），报告才可复现。
 */
class PaymentScheduleTest {

    /** 2023-11-14T22:13:20Z —— 星期二。 */
    private static final long EPOCH_2023_11_14 = 1_700_000_000_000L;

    /** 2024-01-31T00:00:00Z —— 星期三。 */
    private static final long EPOCH_2024_01_31 = 1_706_659_200_000L;

    /** 2023-01-31T00:00:00Z —— 星期二。 */
    private static final long EPOCH_2023_01_31 = 1_675_123_200_000L;

    // ------------------------------------------------------------------
    // 入参校验与合计
    // ------------------------------------------------------------------

    @Test
    @DisplayName("期数非正、每期金额为负都抛 IllegalArgumentException")
    void invalidInputsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentSchedule(EPOCH_2024_01_31, 0, 100L));
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentSchedule(EPOCH_2024_01_31, -3, 100L));
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentSchedule(EPOCH_2024_01_31, 3, -1L));
    }

    @Test
    @DisplayName("总金额 = 期数 × 每期金额")
    void totalIsInstallmentsTimesAmount() {
        PaymentSchedule schedule = new PaymentSchedule(EPOCH_2024_01_31, 3, 33_333L);

        assertEquals(3, schedule.installments());
        assertEquals(33_333L, schedule.installmentCents());
        assertEquals(99_999L, schedule.totalCents());
        assertEquals(EPOCH_2024_01_31, schedule.startEpochMillis());
    }

    @Test
    @DisplayName("每期金额为 0 是合法的（免息分期），总额为 0")
    void zeroInstallmentAmountIsAllowed() {
        PaymentSchedule schedule = new PaymentSchedule(EPOCH_2024_01_31, 2, 0L);

        assertEquals(0L, schedule.totalCents());
        assertEquals(2, schedule.allDueDates().size());
    }

    // ------------------------------------------------------------------
    // 起始日与自然月判定
    // ------------------------------------------------------------------

    @Test
    @DisplayName("起始日按 UTC 呈现")
    void startDateIsUtc() {
        assertEquals("2023-11-14",
                new PaymentSchedule(EPOCH_2023_11_14, 1, 1L).startDateUtc());
        assertEquals("2024-01-31",
                new PaymentSchedule(EPOCH_2024_01_31, 1, 1L).startDateUtc());
        assertEquals("1970-01-01", new PaymentSchedule(0L, 1, 1L).startDateUtc());
    }

    @Test
    @DisplayName("按自然月判定起始时刻，月份从 1 开始")
    void startsInMonthUsesOneBasedMonth() {
        PaymentSchedule schedule = new PaymentSchedule(EPOCH_2023_11_14, 1, 1L);

        assertTrue(schedule.startsInMonth(2023, 11));
        assertFalse(schedule.startsInMonth(2023, 10), "月份从 1 开始，11 月不是 10 月");
        assertFalse(schedule.startsInMonth(2023, 12));
        assertFalse(schedule.startsInMonth(2024, 11));
    }

    // ------------------------------------------------------------------
    // 月末钳位 —— 本类最核心的一组断言
    // ------------------------------------------------------------------

    @Test
    @DisplayName("闰年：2024-01-31 逐期加月并钳到月末")
    void monthEndClampsInLeapYear() {
        PaymentSchedule schedule = new PaymentSchedule(EPOCH_2024_01_31, 4, 100L);

        assertEquals(List.of("2024-01-31", "2024-02-29", "2024-03-31", "2024-04-30"),
                schedule.allDueDates());
    }

    @Test
    @DisplayName("非闰年：2023-01-31 逐期加月并钳到月末")
    void monthEndClampsInCommonYear() {
        PaymentSchedule schedule = new PaymentSchedule(EPOCH_2023_01_31, 3, 100L);

        assertEquals(List.of("2023-01-31", "2023-02-28", "2023-03-31"), schedule.allDueDates());
    }

    @Test
    @DisplayName("钳位只影响不存在的日子，31 天的月份不提前")
    void clampingDoesNotShortenLongMonths() {
        PaymentSchedule schedule = new PaymentSchedule(EPOCH_2024_01_31, 6, 100L);

        assertEquals("2024-05-31", schedule.dueDateUtc(4));
        assertEquals("2024-06-30", schedule.dueDateUtc(5), "6 月只有 30 天");
    }

    @Test
    @DisplayName("非月末的起始日不会漂移")
    void nonMonthEndDatesDoNotDrift() {
        PaymentSchedule schedule = new PaymentSchedule(EPOCH_2023_11_14, 3, 100L);

        assertEquals(List.of("2023-11-14", "2023-12-14", "2024-01-14"), schedule.allDueDates());
    }

    // ------------------------------------------------------------------
    // 星期口径（Calendar 的 1=周日 vs ISO 的 1=周一）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("星期按 ISO 口径：1 = 周一 … 7 = 周日")
    void dayOfWeekIsIso() {
        PaymentSchedule tuesday = new PaymentSchedule(EPOCH_2023_11_14, 1, 1L);
        PaymentSchedule wednesday = new PaymentSchedule(EPOCH_2024_01_31, 1, 1L);

        assertEquals(2, tuesday.dueDayOfWeekIso(0), "2023-11-14 是周二");
        assertEquals(3, wednesday.dueDayOfWeekIso(0), "2024-01-31 是周三");
    }

    @Test
    @DisplayName("跨期后星期跟着日期走：2023-11-14 起三期分别是周二、周四、周日")
    void dayOfWeekFollowsDate() {
        PaymentSchedule schedule = new PaymentSchedule(EPOCH_2023_11_14, 3, 1L);

        assertEquals(2, schedule.dueDayOfWeekIso(0));
        assertEquals(4, schedule.dueDayOfWeekIso(1));
        assertEquals(7, schedule.dueDayOfWeekIso(2));
    }

    @Test
    @DisplayName("星期名是全称，语言标签是约定的一部分（不能从 ENGLISH 改成 ROOT）")
    void dayNameIsEnglishFullName() {
        assertEquals("Tuesday", new PaymentSchedule(EPOCH_2023_11_14, 1, 1L).dueDayName(0));
        assertEquals("Wednesday", new PaymentSchedule(EPOCH_2024_01_31, 2, 1L).dueDayName(0));
        assertEquals("Thursday", new PaymentSchedule(EPOCH_2024_01_31, 2, 1L).dueDayName(1));
    }

    // ------------------------------------------------------------------
    // 越界与文本
    // ------------------------------------------------------------------

    @Test
    @DisplayName("期号越界抛 IndexOutOfBoundsException")
    void indexOutOfRange() {
        PaymentSchedule schedule = new PaymentSchedule(EPOCH_2024_01_31, 2, 100L);

        assertThrows(IndexOutOfBoundsException.class, () -> schedule.dueDateUtc(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> schedule.dueDateUtc(2));
        assertThrows(IndexOutOfBoundsException.class, () -> schedule.dueDayOfWeekIso(2));
        assertThrows(IndexOutOfBoundsException.class, () -> schedule.dueDayName(2));
    }

    @Test
    @DisplayName("describe() 是固定格式的多行文本")
    void describeIsFixedText() {
        PaymentSchedule schedule = new PaymentSchedule(EPOCH_2024_01_31, 2, 12_345L);

        assertEquals(""
                        + "Payment schedule\n"
                        + "  start: 2024-01-31 (UTC)\n"
                        + "  installments: 2\n"
                        + "  #1 due 2024-01-31 (Wednesday)\n"
                        + "  #2 due 2024-02-29 (Thursday)\n"
                        + "  total: 24690 cents",
                schedule.describe());
    }
}
