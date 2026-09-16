package com.example.logparse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ErrorTally} 的行为契约 —— 迁移前后必须逐条为真。
 *
 * <p>只断言 ERROR 行数；吞异常还是结构化处理是内部实现，不进契约。
 */
class ErrorTallyTest {

    @Test
    @DisplayName("统计以 ERROR 开头的行数")
    void countsErrorLines() {
        assertEquals(2, new ErrorTally().errorCount(
                List.of("ERROR foo", "INFO bar", "ERROR baz", "WARN x")));
    }

    @Test
    @DisplayName("坏格式的 retries 不导致异常，也不计入 ERROR 数")
    void tolerantOfMalformedRetryToken() {
        int count = new ErrorTally().errorCount(
                List.of("ERROR x retries=3", "INFO y retries=abc", "DEBUG z"));
        assertEquals(1, count);
    }

    @Test
    @DisplayName("没有 ERROR 行为 0")
    void noErrors() {
        assertEquals(0, new ErrorTally().errorCount(List.of("INFO a", "WARN b")));
    }
}
