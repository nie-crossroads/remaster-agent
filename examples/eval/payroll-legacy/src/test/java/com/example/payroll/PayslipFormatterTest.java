package com.example.payroll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PayslipFormatter} 的行为契约 —— 迁移前后必须逐字相同。
 *
 * <p>返回文本是固定格式，迁移成 text block 只是换写法，内容不能变。
 */
class PayslipFormatterTest {

    @Test
    @DisplayName("工资条是固定格式的多行文本")
    void formatIsFixedText() {
        String expected = ""
                + "PAYSLIP\n"
                + "Employee: Alice\n"
                + "Period:   2024-2\n"
                + "Gross:    50000 cents\n"
                + "Net:      42000 cents\n";
        assertEquals(expected, new PayslipFormatter().format("Alice", 2024, 2, 50_000L, 42_000L));
    }

    @Test
    @DisplayName("空值姓名与零金额也保持格式")
    void formatHandlesZeroAndEmpty() {
        String expected = ""
                + "PAYSLIP\n"
                + "Employee: \n"
                + "Period:   2025-12\n"
                + "Gross:    0 cents\n"
                + "Net:      0 cents\n";
        assertEquals(expected, new PayslipFormatter().format("", 2025, 12, 0L, 0L));
    }
}
