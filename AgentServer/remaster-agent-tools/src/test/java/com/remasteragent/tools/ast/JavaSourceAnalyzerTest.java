package com.remasteragent.tools.ast;

import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.StaticJavaParser;
import com.remasteragent.common.agent.AnalyzeResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 源码分析器的单测。
 *
 * <h2>为什么这个测试类是「事故复盘」的产物</h2>
 * <p>JavaParser 的默认语言级别是 {@code JAVA_11}。这不是一个无关紧要的默认值 ——
 * 它会让 {@code record}、<b>文本块</b>、{@code switch} 表达式全部被判成语法错误。
 * 而本项目的改写 prompt 明确要求模型使用文本块等新语法，于是：
 *
 * <pre>
 *   模型按指令产出文本块 → 护栏 parseHeader 抛错 → 判定「产出非法」→ 重试
 *   → 模型再按指令产出文本块 → 再判非法 → 三轮耗尽 → 任务失败
 * </pre>
 *
 * <p>最终表现是「这个项目的迁移成功率是 0」，而日志指着模型说「你的产出不是合法 Java」。
 * 这是最难归因的一类 bug：每一环看起来都在正常工作。
 *
 * <p>所以下面这几条「现代语法必须能解析」的用例是<b>回归测试</b>，不是锦上添花。
 * 其中文本块那条最关键 —— 它是 prompt 明文要求的产出形态。
 */
class JavaSourceAnalyzerTest {

    @Test
    @DisplayName("回归：文本块必须能解析（改写 prompt 明文要求的产出形态）")
    void textBlocksMustParse() {
        String source = "package com.example;\n"
                + "\n"
                + "public class Query {\n"
                + "    private static final String SQL = \"\"\"\n"
                + "            select id, name\n"
                + "            from users\n"
                + "            \"\"\";\n"
                + "}\n";

        JavaSourceAnalyzer.ParsedHeader header = JavaSourceAnalyzer.parseHeader(source);

        assertEquals("com.example", header.packageName());
        assertEquals("Query", header.primaryType());
    }

    @Test
    @DisplayName("回归：record 必须能解析")
    void recordsMustParse() {
        JavaSourceAnalyzer.ParsedHeader header = JavaSourceAnalyzer.parseHeader(
                "package com.example;\n\npublic record Point(int x, int y) {\n}\n");

        assertEquals("com.example", header.packageName());
        assertEquals("Point", header.primaryType());
    }

    @Test
    @DisplayName("回归：switch 表达式、instanceof 模式匹配、var 必须能解析")
    void otherModernSyntaxMustParse() {
        String source = """
                package com.example;

                public class Modern {
                    int classify(Object o) {
                        if (o instanceof String s) {
                            return switch (s.length()) {
                                case 0 -> 0;
                                case 1, 2 -> 1;
                                default -> 2;
                            };
                        }
                        var fallback = -1;
                        return fallback;
                    }
                }
                """;

        assertEquals("Modern", JavaSourceAnalyzer.parseHeader(source).primaryType());
    }

    @Test
    @DisplayName("不污染全局：StaticJavaParser 的语言级别保持原样")
    void globalStaticParserConfigurationIsNotMutated() {
        LanguageLevel before = StaticJavaParser.getParserConfiguration().getLanguageLevel();

        JavaSourceAnalyzer.parseHeader("package com.example;\npublic record R(int a) {}\n");

        assertEquals(before, StaticJavaParser.getParserConfiguration().getLanguageLevel(),
                "分析器应使用独立 JavaParser 实例，不得改动共享的静态配置");
    }

    // ------------------------------------------------------------------
    // 常规行为
    // ------------------------------------------------------------------

    @Test
    @DisplayName("遗留 JDK8 源码（ANALYZE 的输入）照常解析")
    void legacySourceStillParses() {
        String legacy = """
                package com.example;

                import java.util.Date;

                public class Demo {
                    private Date created;

                    public String stamp() {
                        return new Date().toString();
                    }
                }
                """;

        JavaSourceAnalyzer.ParsedHeader header = JavaSourceAnalyzer.parseHeader(legacy);

        assertEquals("com.example", header.packageName());
        assertEquals("Demo", header.primaryType());
        assertEquals(1, header.typeCount());
    }

    @Test
    @DisplayName("符号清单包含类型、方法、字段，以及嵌套类（用 $ 连接）")
    void symbolsCoverTypesMethodsFieldsAndNestedTypes() {
        String source = """
                package com.example;

                public class Outer {
                    private int counter;

                    public int bump() {
                        return ++counter;
                    }

                    static class Inner {
                    }
                }
                """;

        List<String> symbols = JavaSourceAnalyzer.parseHeader(source).symbols();

        assertTrue(symbols.contains("com.example.Outer"), symbols.toString());
        assertTrue(symbols.contains("com.example.Outer#bump()"), symbols.toString());
        assertTrue(symbols.contains("com.example.Outer#counter"), symbols.toString());
        assertTrue(symbols.contains("com.example.Outer$Inner"), symbols.toString());
    }

    @Test
    @DisplayName("注释里的 class 字样与字符串里的花括号不影响解析结果")
    void commentsAndStringsDoNotConfuseParsing() {
        String source = """
                package com.example;

                // 这里提到 class FakeName 只是为了说明
                public class Demo {
                    String template = "class NotReal { }";
                }
                """;

        assertEquals("Demo", JavaSourceAnalyzer.parseHeader(source).primaryType(),
                "这正是不能用正则匹配 class 关键字的原因");
    }

    @Test
    @DisplayName("语法错误：抛 IllegalStateException，消息里带出问题描述")
    void invalidSourceThrows() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> JavaSourceAnalyzer.parseHeader("package com.example;\npublic class Broken {\n  void m() {\n"));

        assertTrue(error.getMessage().startsWith("源码无法解析"), error.getMessage());
        assertNotNull(error.getMessage());
    }

    @Test
    @DisplayName("默认包：packageName 为空字符串而不是 null")
    void defaultPackageYieldsEmptyString() {
        assertEquals("", JavaSourceAnalyzer.parseHeader("public class Demo {\n}\n").packageName());
    }

    @Test
    @DisplayName("辅助方法：isParseable / packageName / primaryType 对非法输入不抛异常")
    void helperMethodsDegradeGracefully() {
        String broken = "这不是 Java";

        assertFalse(JavaSourceAnalyzer.isParseable(broken));
        assertTrue(JavaSourceAnalyzer.packageName(broken).isEmpty());
        assertTrue(JavaSourceAnalyzer.primaryType(broken).isEmpty());

        assertTrue(JavaSourceAnalyzer.isParseable("package a;\npublic class B {}\n"));
        assertEquals("a", JavaSourceAnalyzer.packageName("package a;\npublic class B {}\n").orElseThrow());
        assertEquals("B", JavaSourceAnalyzer.primaryType("package a;\npublic class B {}\n").orElseThrow());
    }

    @Test
    @DisplayName("analyze：产出完整的 AnalyzeResult，且摘要可读")
    void analyzeBuildsResult() {
        String source = "package com.example;\n\npublic class Demo {\n    public void go() {\n    }\n}\n";

        AnalyzeResult result = JavaSourceAnalyzer.analyze("src/Demo.java", source);

        assertEquals("src/Demo.java", result.filePath());
        assertEquals("com.example", result.packageName());
        assertEquals("Demo", result.className());
        assertEquals(source, result.sourceContent());
        assertTrue(result.summary().contains("Demo"), result.summary());
        assertTrue(result.symbols().contains("com.example.Demo#go()"), result.symbols().toString());
    }
}
