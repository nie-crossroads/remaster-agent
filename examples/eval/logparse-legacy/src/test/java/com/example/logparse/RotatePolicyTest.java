package com.example.logparse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link RotatePolicy} 的行为契约 —— 迁移前后必须逐条为真。
 *
 * <p>只断言文件名格式；字符串拼接还是 {@code Path.resolve} 是实现细节。
 * 注意：{@code daysAgo} 依赖当前日期，不进契约测试（避免结果随时间漂移）。
 */
class RotatePolicyTest {

    @Test
    @DisplayName("文件名形如 base_yyyyMMdd.log")
    void nextFileNameFormat() {
        Date when = new GregorianCalendar(2024, Calendar.MARCH, 15, 0, 0, 0).getTime();
        assertEquals("app_20240315.log", new RotatePolicy().nextFileName("app", when));
    }

    @Test
    @DisplayName("不同基准名与日期都保持格式")
    void nextFileNameAnother() {
        Date when = new GregorianCalendar(2025, Calendar.DECEMBER, 31, 0, 0, 0).getTime();
        assertEquals("server_20251231.log", new RotatePolicy().nextFileName("server", when));
    }
}
