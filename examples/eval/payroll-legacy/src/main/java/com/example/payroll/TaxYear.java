package com.example.payroll;

import java.util.Calendar;
import java.util.GregorianCalendar;

/**
 * 税务年度判定 —— 迁移目标文件之一（日期时间：手工年份边界 → Year / YearMonth）。
 *
 * <h2>这个类刻意留下的 JDK 8 写法</h2>
 * <ul>
 *   <li><b>手写闰年判断</b>：用 {@code GregorianCalendar} 把二月设成 1 号再取当月天数，
 *       等于 29 即闰年。java.time 里 {@code Year.isLeap(long)} 一句话。</li>
 *   <li>日期解析用 {@code substring} 截字符串，而不是 {@code LocalDate.parse}。</li>
 * </ul>
 *
 * <p>公开方法签名与返回值是<b>可观测契约</b>，迁移后必须逐字保持不变。
 */
public class TaxYear {

    /**
     * 财政年度从 4 月 1 日起：给定 {@code YYYY-MM-DD} 落在 4–12 月 → 当年；
     * 落在 1–3 月 → 上一年。
     */
    public int fiscalYear(String isoDate) {
        int year = Integer.parseInt(isoDate.substring(0, 4));
        int month = Integer.parseInt(isoDate.substring(5, 7));
        if (month >= 4) {
            return year;
        }
        return year - 1;
    }

    /** 某年是否为闰年（2 月 29 天）。例外年份（如 1900 / 2100）按 Gregorian 规则判定。 */
    public boolean isLeap(int year) {
        Calendar cal = new GregorianCalendar();
        cal.set(Calendar.YEAR, year);
        cal.set(Calendar.MONTH, Calendar.FEBRUARY);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        int days = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        return days == 29;
    }
}
