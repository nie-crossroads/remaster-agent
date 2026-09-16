package com.example.payroll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SalaryRun} 的行为契约 —— 迁移前后必须逐条为真。
 *
 * <p>只断言可观测结果：折算比例、闰年二月天数、余数摊到最后一月。
 * 不检查内部是否用了 {@code Calendar} 还是 {@code YearMonth}。
 */
class SalaryRunTest {

    @Test
    @DisplayName("全月薪资：年薪整除 12 时每月经典型，最后一月吃掉余数")
    void fullMonthPayDistributesRemainder() {
        assertEquals(10_000L, new SalaryRun().fullMonthPay(120_000, 2024, 1));
        assertEquals(10_000L, new SalaryRun().fullMonthPay(120_000, 2024, 12));

        long sum = 0L;
        for (int m = 1; m <= 12; m++) {
            sum += new SalaryRun().fullMonthPay(120_000, 2024, m);
        }
        assertEquals(120_000L, sum, "12 个月加总必须等于年薪");
    }

    @Test
    @DisplayName("全月薪资：年薪不整除时余数计入第 12 月，加总仍等于年薪")
    void fullMonthPayRemainderLandsOnDecember() {
        assertEquals(8_333L, new SalaryRun().fullMonthPay(100_000, 2024, 1));
        assertEquals(8_337L, new SalaryRun().fullMonthPay(100_000, 2024, 12));

        long sum = 0L;
        for (int m = 1; m <= 12; m++) {
            sum += new SalaryRun().fullMonthPay(100_000, 2024, m);
        }
        assertEquals(100_000L, sum);
    }

    @Test
    @DisplayName("入职折算：闰二月按 29 天计算，15 号入职发 15/29")
    void proratedUsesLeapFebruaryLength() {
        // 2024 是闰年，二月 29 天；15 号入职，工作 15 天；全月 10000 分
        assertEquals(5_172L, new SalaryRun().proratedFirstMonth(120_000, 2024, 2, 15));
    }

    @Test
    @DisplayName("入职折算：1 号全月入职发整月，月末入职发一天")
    void proratedBoundaries() {
        assertEquals(10_000L, new SalaryRun().proratedFirstMonth(120_000, 2024, 1, 1));
        assertEquals(322L, new SalaryRun().proratedFirstMonth(120_000, 2024, 1, 31));
    }

    @Test
    @DisplayName("入职折算：年薪不整除时农历数同样向下取整到分")
    void proratedNonDivisibleAnnual() {
        // 全月 8333 分，工作 15 天，8333*15=124995，/29 = 4310（整除截断）
        assertEquals(4_310L, new SalaryRun().proratedFirstMonth(100_000, 2024, 2, 15));
    }

    @Test
    @DisplayName("非法 month / joinDay 抛 IllegalArgumentException")
    void invalidInputsRejected() {
        SalaryRun run = new SalaryRun();
        assertThrows(IllegalArgumentException.class, () -> run.fullMonthPay(100_000, 2024, 0));
        assertThrows(IllegalArgumentException.class, () -> run.fullMonthPay(100_000, 2024, 13));
        assertThrows(IllegalArgumentException.class, () -> run.proratedFirstMonth(100_000, 2024, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> run.proratedFirstMonth(100_000, 2024, 2, 30));
    }
}
