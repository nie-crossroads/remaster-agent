package com.remasteragent.tools.maven;

import com.remasteragent.tools.sandbox.SandboxResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CompileErrorClassifier} 的确定性单测 —— 不依赖 LLM / DB / Maven。
 *
 * <p>分类是纯规则，最该被钉死：它是「回退重写能不能改对」的定向输入。规则一旦回归
 * （比如把 javax.crypto 误判成 jakarta 漏改），模型会被提示去改一个根本不该改的包，
 * 反而引入真实编译错误。所以这里把每类判定都单列一条，且特意覆盖「白名单排除项」的反例。
 */
class CompileErrorClassifierTest {

    private CompileErrorClassifier.ClassifiedError classify(String block) {
        return CompileErrorClassifier.classify(List.of(block)).errors().get(0);
    }

    // ---- JAKARTA_LEFTOVER（javax→jakarta 漏改）----

    @Test
    void 中文_jakarta_漏改_被识别() {
        String block = "/work/task-3/src/main/java/com/example/Foo.java:[12,10] 程序包javax.persistence不存在";
        CompileErrorClassifier.ClassifiedError e = classify(block);
        assertEquals(CompileErrorClassifier.Category.JAKARTA_LEFTOVER, e.category());
        assertTrue(e.hint().contains("jakarta"), "提示应指向 jakarta 命名空间，实际: " + e.hint());
    }

    @Test
    void 英文_jakarta_漏改_被识别() {
        String block = "/work/src/main/java/Foo.java:[12,10] package javax.validation does not exist";
        CompileErrorClassifier.ClassifiedError e = classify(block);
        assertEquals(CompileErrorClassifier.Category.JAKARTA_LEFTOVER, e.category());
    }

    @Test
    void jakarta_漏改_提示_带_具体包示例() {
        String block = "/work/src/main/java/Foo.java:[1,1] package javax.annotation does not exist";
        CompileErrorClassifier.ClassifiedError e = classify(block);
        assertTrue(e.hint().contains("javax.annotation.Resource"),
                "提示里应给具体改法示例，实际: " + e.hint());
    }

    // ---- 白名单排除项：仍在 JDK 内的 javax.* 不能误判成 jakarta ----

    @Test
    void javax_crypto_仍在JDK_不误判为jakarta() {
        String block = "/work/src/main/java/Foo.java:[5,1] 程序包javax.crypto不存在";
        CompileErrorClassifier.ClassifiedError e = classify(block);
        assertFalse(e.category() == CompileErrorClassifier.Category.JAKARTA_LEFTOVER,
                "javax.crypto 仍在 JDK，不应被当成 jakarta 漏改，实际: " + e.category());
    }

    // ---- API_SIGNATURE（API 签名/已移除）----

    @Test
    void 找不到符号_归为API签名变更() {
        String block = "/work/src/main/java/Foo.java:[12,34] cannot find symbol\n  符号: 方法 bar()\n  位置: 类 Foo";
        CompileErrorClassifier.ClassifiedError e = classify(block);
        assertEquals(CompileErrorClassifier.Category.API_SIGNATURE, e.category());
    }

    @Test
    void 中文_找不到符号_归为API签名变更() {
        String block = "/work/src/main/java/Foo.java:[12,34] 找不到符号\n  符号:   方法 bar()";
        CompileErrorClassifier.ClassifiedError e = classify(block);
        assertEquals(CompileErrorClassifier.Category.API_SIGNATURE, e.category());
    }

    // ---- DEPENDENCY_UNRESOLVED（依赖未解析）----

    @Test
    void 外部包不存在_归为依赖未解析() {
        String block = "/work/src/main/java/Foo.java:[5,1] 程序包com.example.oracle不存在";
        CompileErrorClassifier.ClassifiedError e = classify(block);
        assertEquals(CompileErrorClassifier.Category.DEPENDENCY_UNRESOLVED, e.category());
        assertTrue(e.hint().contains("pom"), "依赖类提示应引导查 pom，实际: " + e.hint());
    }

    // ---- TEST_SOURCE（测试源集 + 反作弊）----

    @Test
    void 测试源集_无法归类时_归为TEST_SOURCE() {
        String block = "/work/src/test/java/com/example/FooTest.java:[10,5] illegal start of type";
        CompileErrorClassifier.ClassifiedError e = classify(block);
        assertEquals(CompileErrorClassifier.Category.TEST_SOURCE, e.category());
    }

    @Test
    void 测试源集_无论内容类别_都附加反作弊提示() {
        // 测试文件里也漏改了 jakarta：内容类别是 JAKARTA，但仍要附「不要删测试」的反作弊提示
        String block = "/work/src/test/java/com/example/FooTest.java:[12,10] package javax.persistence does not exist";
        CompileErrorClassifier.ClassifiedError e = classify(block);
        assertEquals(CompileErrorClassifier.Category.JAKARTA_LEFTOVER, e.category());
        assertTrue(e.hint().contains("@Disabled"),
                "测试源集的错误必须附反作弊提示，实际: " + e.hint());
    }

    // ---- UNCLASSIFIED（兜底）----

    @Test
    void 主源码_无法归类_归为UNCLASSIFIED() {
        String block = "/work/src/main/java/Foo.java:[10,5] reached end of file while parsing";
        CompileErrorClassifier.ClassifiedError e = classify(block);
        assertEquals(CompileErrorClassifier.Category.UNCLASSIFIED, e.category());
    }

    // ---- 计数（可观测）----

    @Test
    void 多块_计数准确() {
        List<String> blocks = List.of(
                "/work/src/main/A.java:[1,1] package javax.persistence does not exist",
                "/work/src/main/B.java:[2,2] cannot find symbol",
                "/work/src/main/C.java:[3,3] 程序包com.example.oracle不存在");
        CompileErrorClassifier.CompileErrorReport report = CompileErrorClassifier.classify(blocks);
        assertEquals(3, report.total());
        assertEquals(1, report.jakartaLeftover());
        assertEquals(1, report.apiSignature());
        assertEquals(1, report.dependencyUnresolved());
        assertEquals(0, report.testSource());
        assertEquals(0, report.unclassified());
    }

    @Test
    void 空输入_返回空报告() {
        CompileErrorClassifier.CompileErrorReport report =
                CompileErrorClassifier.classify(List.of());
        assertEquals(0, report.total());
        assertTrue(report.errors().isEmpty());
    }

    // ---- 集成：toVerifyResult 的 excerpt 应含类别标签 ----

    @Test
    void toVerifyResult_摘录_含类别标签与提示() {
        // 构造一段编译失败的控制台：带 COMPILATION ERROR 标记 + 一条 jakarta 漏改 + 一条 API 签名
        String console = """
                [INFO] --- compiler:3.14.1:compile (default-compile) @ demo ---
                [ERROR] COMPILATION ERROR :
                [ERROR] /work/task-x/src/main/java/com/example/A.java:[12,10] package javax.persistence does not exist
                [ERROR] /work/task-x/src/main/java/com/example/B.java:[5,5] cannot find symbol
                [INFO] BUILD FAILURE
                """;
        SandboxResult result = new SandboxResult(1, console, "", 1000, false);
        Path projectDir = Path.of("no-such-dir-should-be-safe");

        String excerpt = MavenResultParser.toVerifyResult(result, projectDir).failureExcerpt();

        assertTrue(excerpt.contains("[jakarta漏改]"), "摘录应带 jakarta 类别标签，实际: " + excerpt);
        assertTrue(excerpt.contains("[API签名变更]"), "摘录应带 API 签名类别标签，实际: " + excerpt);
        assertTrue(excerpt.contains("提示:"), "每条错误应附定向提示，实际: " + excerpt);
        // 块本身的定位/符号行不能被标签破坏（模型仍然需要它们）
        assertTrue(excerpt.contains("A.java:[12,10]"), "错误定位行应保留，实际: " + excerpt);
        assertTrue(excerpt.contains("cannot find symbol"), "错误诊断正文应保留，实际: " + excerpt);
    }
}
