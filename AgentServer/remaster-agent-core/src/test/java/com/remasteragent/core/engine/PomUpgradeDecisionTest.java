package com.remasteragent.core.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编译级别判定的单测。
 *
 * <p>核心是钉住<b>判定范围与执行范围一致</b>：{@code PomRewriteNode} 改全仓 pom，
 * 所以判定也必须看全仓。两者错位时最典型的漏判是「根 pom 已达标、子模块还没达标」——
 * 任务会跳过整个升级节点，子模块永远升不上去，而失败现象出现在模型写的代码上。
 */
class PomUpgradeDecisionTest {

    @TempDir
    Path tmp;

    private Path workspace;

    @BeforeEach
    void setUp() throws IOException {
        workspace = tmp.resolve("repo");
        Files.createDirectories(workspace);
    }

    private void writePom(String relativePath, String content) throws IOException {
        Path pom = workspace.resolve(relativePath);
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, content, StandardCharsets.UTF_8);
    }

    private static String pomWith(String level) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <properties>
                    <maven.compiler.release>%s</maven.compiler.release>
                  </properties>
                </project>
                """.formatted(level);
    }

    private static String pomWithoutLevel() {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>bare</artifactId>
                </project>
                """;
    }

    @Test
    @DisplayName("单模块根 pom 低于目标：判定为需要升级，且点名是哪一份")
    void detectsSingleModuleBelowTarget() throws IOException {
        writePom("pom.xml", pomWith("8"));

        Optional<String> reason = PomUpgradeDecision.reason(workspace, 21);

        assertTrue(reason.isPresent());
        assertTrue(reason.get().contains("pom.xml"), reason.get());
        assertTrue(reason.get().contains("8"), reason.get());
    }

    @Test
    @DisplayName("全仓都达标：判定为不需要升级（不该多出一个空转节点）")
    void skipsWhenEveryPomAtTarget() throws IOException {
        writePom("pom.xml", pomWith("21"));
        writePom("mod-a/pom.xml", pomWith("21"));

        assertTrue(PomUpgradeDecision.reason(workspace, 21).isEmpty());
    }

    @Test
    @DisplayName("回归：根 pom 已达标但子模块偏低，仍须判定为需要升级")
    void detectsSubmoduleBelowTargetEvenWhenRootIsFine() throws IOException {
        writePom("pom.xml", pomWith("21"));
        writePom("legacy-report/pom.xml", pomWith("8"));

        Optional<String> reason = PomUpgradeDecision.reason(workspace, 21);

        assertTrue(reason.isPresent(),
                "只看根 pom 会让子模块永远升不上去，而失败会以「模型代码写错了」的样子出现");
        assertTrue(reason.get().contains("legacy-report"), "原因里要点名拖低级别的那一份：" + reason.get());
    }

    @Test
    @DisplayName("多份偏低时报最低的那一份（决定了能写出什么语法）")
    void reportsTheLowestOne() throws IOException {
        writePom("pom.xml", pomWith("17"));
        writePom("mod-a/pom.xml", pomWith("11"));
        writePom("mod-b/pom.xml", pomWith("8"));

        String reason = PomUpgradeDecision.reason(workspace, 21).orElseThrow();

        assertTrue(reason.contains("mod-b") && reason.contains("8"), reason);
    }

    @Test
    @DisplayName("一份都没声明级别：判定为需要升级，原因里说明将插入 release")
    void detectsMissingDeclaration() throws IOException {
        writePom("pom.xml", pomWithoutLevel());

        String reason = PomUpgradeDecision.reason(workspace, 21).orElseThrow();

        assertTrue(reason.contains("插入"), reason);
    }

    @Test
    @DisplayName("非 Maven 工程：不判定（让常规流程去给出更贴近现场的报错）")
    void ignoresNonMavenProject() {
        assertTrue(PomUpgradeDecision.reason(workspace, 21).isEmpty());
        assertTrue(PomUpgradeDecision.reason(null, 21).isEmpty());
    }

    @Test
    @DisplayName("老式写法 1.8 也认得（等价于 8）")
    void understandsLegacy18Notation() throws IOException {
        writePom("pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <properties>
                    <java.version>1.8</java.version>
                  </properties>
                </project>
                """);

        String reason = PomUpgradeDecision.reason(workspace, 21).orElseThrow();

        assertTrue(reason.contains("8"), "1.8 应归一成 8：" + reason);
    }

    @Test
    @DisplayName("目标版本本身就是 8 时不必升级")
    void noUpgradeNeededWhenTargetMatches() throws IOException {
        writePom("pom.xml", pomWith("8"));

        assertEquals(Optional.empty(), PomUpgradeDecision.reason(workspace, 8));
    }
}
