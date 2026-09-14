package com.remasteragent.tools.ast;

import com.remasteragent.common.rag.CodeChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CodeChunker} 的确定性单测 —— 不依赖 LLM / DB / Maven，毫秒级。
 *
 * <p>用内联源码而不是读示例工程：块的符号与行号是本文档要钉死的契约，
 * 内联源码让断言完全自洽，不会因为示例工程被改动而无声失效。
 */
class CodeChunkerTest {

    private static final String SOURCE = """
            package com.example.legacy;

            public class OrderService {
                private final String name;

                public OrderService(String name) {
                    this.name = name;
                }

                public String greet(int times) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < times; i++) {
                        sb.append(name).append('!');
                    }
                    return sb.toString();
                }

                static class Nested {
                    int value() {
                        return 42;
                    }
                }
            }
            """;

    @Test
    void 切出类骨架与每个方法各一块() {
        List<CodeChunk> chunks = CodeChunker.chunk("src/main/java/com/example/legacy/OrderService.java", SOURCE);

        // 1 个外层类骨架 + 2 个方法（构造器 + greet）+ 1 个内部类骨架 + 1 个内部类方法
        assertEquals(5, chunks.size(), "块数应为 类骨架 + 方法，各占一块");

        assertEquals(CodeChunk.KIND_CLASS, chunks.get(0).kind());
        assertEquals("com.example.legacy.OrderService", chunks.get(0).symbol());

        assertEquals(CodeChunk.KIND_METHOD, chunks.get(1).kind());
        assertTrue(chunks.get(1).symbol().endsWith("OrderService#<init>"), "构造器符号应为 #<init>");

        assertEquals("com.example.legacy.OrderService#greet", chunks.get(2).symbol());

        assertEquals("com.example.legacy.OrderService$Nested", chunks.get(3).symbol());
        assertEquals("com.example.legacy.OrderService$Nested#value", chunks.get(4).symbol());
    }

    @Test
    void 类骨架含字段但不含方法体_避免与方法块重复() {
        List<CodeChunk> chunks = CodeChunker.chunk("OrderService.java", SOURCE);
        CodeChunk classChunk = chunks.get(0);

        assertTrue(classChunk.content().contains("class OrderService"), "骨架应含类型声明");
        assertTrue(classChunk.content().contains("private final String name;"), "骨架应含字段");

        // 这是本设计的核心：类骨架必须排除方法体，否则与 greet 方法块内容重复
        assertFalse(classChunk.content().contains("StringBuilder sb"),
                "类骨架不应包含任何方法体，否则与方法块内容重复");
    }

    @Test
    void 方法块内容是该方法源码本身() {
        List<CodeChunk> chunks = CodeChunker.chunk("OrderService.java", SOURCE);
        CodeChunk greet = chunks.get(2);

        assertTrue(greet.content().contains("StringBuilder sb"), "方法块应含方法体");
        assertTrue(greet.content().contains("public String greet(int times)"), "方法块应含签名");
        assertFalse(greet.content().contains("private final String name;"),
                "方法块不应把类字段也带上 —— 那是类骨架的职责");
    }

    @Test
    void 行号范围落在源码内且方法在类范围内() {
        List<CodeChunk> chunks = CodeChunker.chunk("OrderService.java", SOURCE);
        CodeChunk classChunk = chunks.get(0);
        CodeChunk greet = chunks.get(2);

        assertEquals(3, classChunk.startLine(), "class 声明在第 3 行");
        assertTrue(greet.startLine() > classChunk.startLine(), "方法起始行应晚于类起始行");
        assertTrue(greet.endLine() >= greet.startLine(), "结束行不应早于起始行");
        assertTrue(greet.endLine() <= classChunk.endLine(), "方法行号应落在类范围内");
    }

    @Test
    void 源码无法解析时抛出而非返回半截块() {
        String broken = "package p; public class A { void m( { } }";
        assertThrows(IllegalStateException.class, () -> CodeChunker.chunk("A.java", broken));
    }
}
