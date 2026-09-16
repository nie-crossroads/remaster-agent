package com.example.logparse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link LineScanner} 的行为契约 —— 迁移前后必须逐条为真。
 *
 * <p>只断言行数；try/finally 还是 try-with-resources 是实现细节。
 */
class LineScannerTest {

    @Test
    @DisplayName("多行文本按 readLine 计数")
    void countsLines() {
        assertEquals(3, new LineScanner().countLines(new StringReader("a\nb\nc\n")));
        assertEquals(1, new LineScanner().countLines(new StringReader("hello")));
        assertEquals(0, new LineScanner().countLines(new StringReader("")));
    }

    @Test
    @DisplayName("首尾带空行也按 readLine 的语义计数")
    void countsBlankLinesAsLines() {
        // 前三行非空、第四行空、第五行非空：readLine 返回 5 次（含中间的空串）
        assertEquals(5, new LineScanner().countLines(new StringReader("x\ny\nz\n\nw")));
    }
}
