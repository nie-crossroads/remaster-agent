package com.example.logparse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ReportMain} 的行为契约 —— 迁移前后必须逐字相同。
 *
 * <p>返回文本是固定格式；把逻辑从 static main 抽成实例方法，内容不能变。
 */
class ReportMainTest {

    @Test
    @DisplayName("汇总文本形如 total=N error=E")
    void buildsSummary() {
        assertEquals("total=3 error=2",
                new ReportMain().buildSummary(List.of("ERROR a", "INFO b", "ERROR c")));
    }

    @Test
    @DisplayName("空输入汇总为零")
    void emptySummary() {
        assertEquals("total=0 error=0", new ReportMain().buildSummary(List.of()));
    }
}
