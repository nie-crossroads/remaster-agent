package com.example.payroll;

import java.util.Calendar;
import java.util.GregorianCalendar;

/**
 * 月薪核算 —— 迁移目标文件之一（日期时间：Calendar 月份算术 → java.time）。
 *
 * <h2>这个类刻意留下的 JDK 8 写法</h2>
 * <ul>
 *   <li><b>Calendar 取月末天数</b>：{@code new GregorianCalendar(year, month-1, 1)}
 *       再 {@code getActualMaximum(Calendar.DAY_OF_MONTH)}。月份是 0 基的坑，
 *       java.time 里 {@code YearMonth.of(y, m).lengthOfMonth()} 一行就清楚。</li>
 *   <li>下标 for 循环与 {@code Integer.valueOf(...)} 显式装箱（这里为统一风格保留）。</li>
 * </ul>
 *
 * <p>公开方法签名与返回值是<b>可观测契约</b>，迁移后必须逐字保持不变。
 */
public class SalaryRun {

    /** 某月全月应发（分）= 年薪 / 12，余数摊到最后一月，保证 12 个月加总恰好等于年薪。 */
    public long fullMonthPay(int annualCents, int year, int month) {
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month 必须在 1..12: " + month);
        }
        int perMonth = annualCents / 12;
        if (month == 12) {
            return annualCents - (long) perMonth * 11L;
        }
        return perMonth;
    }

    /**
     * 入职当月按比例折算（分）：在该月第 {@code joinDay} 天入职，发
     * {@code (当月天数 - 入职日 + 1) / 当月天数} 的比例，向下取整到分（保证不超发）。
     */
    public long proratedFirstMonth(int annualCents, int year, int month, int joinDay) {
        if (joinDay < 1) {
            throw new IllegalArgumentException("joinDay 必须 >= 1: " + joinDay);
        }
        Calendar cal = new GregorianCalendar(year, month - 1, 1);
        int daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        if (joinDay > daysInMonth) {
            throw new IllegalArgumentException("joinDay 超过当月天数: " + joinDay);
        }
        int worked = daysInMonth - joinDay + 1;
        long full = fullMonthPay(annualCents, year, month);
        return full * worked / daysInMonth;
    }
}
