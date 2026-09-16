package com.example.logparse;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

/**
 * 日志滚动文件名 —— 迁移目标文件之一（路径与日期：File 拼接 → Path/Paths，SimpleDateFormat → DateTimeFormatter）。
 *
 * <h2>这个类刻意留下的 JDK 8 写法</h2>
 * <ul>
 *   <li><b>字符串拼接出文件名</b>：{@code base + "_" + yyyyMMdd + ".log"}，
 *       而不是 {@code Path.resolve(...)}。</li>
 *   <li><b>{@code SimpleDateFormat("yyyyMMdd")}</b>：非线程安全、且混在业务逻辑里。</li>
 *   <li>用 {@code Date} 而非 {@code LocalDate} 表示「哪一天」。</li>
 * </ul>
 *
 * <p>返回的文件名是<b>可观测契约</b>，迁移成 {@code Path} + {@code DateTimeFormatter} 后逐字符不变。
 */
public class RotatePolicy {

    /** 给定基准名与日期，返回形如 {@code base_20240315.log} 的滚动文件名。 */
    public String nextFileName(String base, Date when) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd");
        return base + "_" + fmt.format(when) + ".log";
    }

    /** 把「N 天前的那一天」格式化成 yyyyMMdd（演示 Date 算术）。 */
    public String daysAgo(int days) {
        Calendar cal = new GregorianCalendar();
        cal.add(Calendar.DAY_OF_MONTH, -days);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd");
        return fmt.format(cal.getTime());
    }
}
