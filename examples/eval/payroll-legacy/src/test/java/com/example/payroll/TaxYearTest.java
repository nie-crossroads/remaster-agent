package com.example.payroll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TaxYear} 的行为契约 —— 迁移前后必须逐条为真。
 *
 * <p>只断言财政年度边界与闰年判定结果，不管内部用 {@code GregorianCalendar}
 * 还是 {@code Year.isLeap}。
 */
class TaxYearTest {

    @Test
    @DisplayName("财政年度从 4 月起：4-12 月归当年，1-3 月归上一年")
    void fiscalYearBoundary() {
        TaxYear year = new TaxYear();
        assertEquals(2024, year.fiscalYear("2024-04-01"));
        assertEquals(2024, year.fiscalYear("2024-12-31"));
        assertEquals(2023, year.fiscalYear("2024-03-31"));
        assertEquals(2023, year.fiscalYear("2024-01-15"));
        assertEquals(2023, year.fiscalYear("2023-12-31"));
    }

    @Test
    @DisplayName("闰年判定：2000 闰年、1900 平年、2024 闰年、2023 平年")
    void leapYearDetection() {
        TaxYear year = new TaxYear();
        assertEquals(true, year.isLeap(2000));
        assertEquals(false, year.isLeap(1900));
        assertEquals(true, year.isLeap(2024));
        assertEquals(false, year.isLeap(2023));
    }
}
