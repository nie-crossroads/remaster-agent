package com.remasteragent.tools.ast;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用任务 52 里用户真实文件的关键片段做断言：证明扫描器能认出真正的 JDK8→21 痛点，
 * 又不会误伤仍在 JDK 里的 API。
 */
class JdkRemovalScannerTest {

    @Test
    void articleService_importsJavaxAnnotationResource_isFlagged() {
        // 来自用户的 ArticleService.java：@Resource 注入是 JDK 8 老写法，JDK 11 起 javax.annotation 被移除
        String src = """
                package com.blog.service;
                import com.blog.common.util.DateUtil;
                import javax.annotation.Resource;
                public class ArticleService {
                    @Resource
                    private Object articleMapper;
                }
                """;
        List<JdkRemovalScanner.RemovalRisk> risks = JdkRemovalScanner.analyze(src);
        assertFalse(risks.isEmpty(), "应当命中 javax.annotation.Resource 移除风险");
        assertTrue(risks.stream().anyMatch(r -> r.importFqn().equals("javax.annotation.Resource")),
                "应识别 javax.annotation.Resource");
        assertTrue(risks.stream().anyMatch(r -> r.humanFlag().contains("JDK11移除")),
                "标记应含 JDK11移除: " + risks);
    }

    @Test
    void addMessageHandler_importsJavaxXmlWs_isFlagged() {
        // 来自用户的 AddMessageHandler.java：javax.xml.ws 在 JDK 11 被彻底删除
        String src = """
                package com.blog.system.handler;
                import javax.xml.ws.RequestWrapper;
                public class AddMessageHandler {}
                """;
        List<JdkRemovalScanner.RemovalRisk> risks = JdkRemovalScanner.analyze(src);
        assertFalse(risks.isEmpty(), "应当命中 javax.xml.ws.RequestWrapper 移除风险");
        assertTrue(risks.stream().anyMatch(r -> r.importFqn().equals("javax.xml.ws.RequestWrapper")),
                "应识别 javax.xml.ws.RequestWrapper");
    }

    @Test
    void dateUtil_alreadyModern_returnsNoRisk() {
        // 来自用户的 DateUtil.java：已用 java.time，无任何移除风险
        String src = """
                package com.blog.common.util;
                import java.time.LocalDateTime;
                import java.time.format.DateTimeFormatter;
                public class DateUtil {}
                """;
        List<JdkRemovalScanner.RemovalRisk> risks = JdkRemovalScanner.analyze(src);
        assertTrue(risks.isEmpty(), "已现代化的文件不应报风险，避免白烧改写次数: " + risks);
    }

    @Test
    void falsePositives_stillInJdk_areNotFlagged() {
        // 以下 API 在 JDK 21 仍然存活，绝不能误报
        String src = """
                package demo;
                import javax.xml.parsers.DocumentBuilderFactory;
                import javax.xml.transform.Transformer;
                import javax.annotation.processing.Processor;
                import javax.script.ScriptEngine;
                import javax.sql.DataSource;
                public class Demo {}
                """;
        List<JdkRemovalScanner.RemovalRisk> risks = JdkRemovalScanner.analyze(src);
        assertTrue(risks.isEmpty(), "JAXP / 注解处理器 / ScriptEngine / javax.sql 仍在 JDK，不应误报: " + risks);
    }

    @Test
    void scanWholeProject_findsAllRiskFiles() throws Exception {
        // 临时工程：三个文件，分别命中/不命中，验证 scan 能给出整库风险地图
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("remaster-scan");
        writeFile(dir, "A.java", """
                package x;
                import javax.annotation.Resource;
                public class A { @Resource private Object o; }
                """);
        writeFile(dir, "B.java", """
                package x;
                import javax.xml.ws.RequestWrapper;
                public class B {}
                """);
        writeFile(dir, "C.java", """
                package x;
                import java.time.LocalDateTime;
                public class C {}
                """);

        JdkRemovalScanner.ProjectRemovalRisks risks = JdkRemovalScanner.create().scan(dir);
        List<String> blocking = risks.filesWithBlockingRisk();
        assertEquals(2, blocking.size(), "应找出 A.java 与 B.java 两个风险文件");
        assertTrue(risks.flagsFor("A.java").stream().anyMatch(f -> f.contains("javax.annotation.Resource")));
        assertTrue(risks.flagsFor("B.java").stream().anyMatch(f -> f.contains("javax.xml.ws.RequestWrapper")));
        assertTrue(risks.flagsFor("C.java").isEmpty(), "C.java 不应有风险");
    }

    private static void writeFile(java.nio.file.Path dir, String name, String content) throws Exception {
        java.nio.file.Files.writeString(dir.resolve(name), content, java.nio.charset.StandardCharsets.UTF_8);
    }
}
