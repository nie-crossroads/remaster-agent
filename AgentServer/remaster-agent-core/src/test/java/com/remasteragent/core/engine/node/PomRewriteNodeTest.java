package com.remasteragent.core.engine.node;

import com.remasteragent.common.agent.PomRewriteResult;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.domain.PatchRecord;
import com.remasteragent.core.engine.NodeContext;
import com.remasteragent.core.engine.NodeOutcome;
import com.remasteragent.core.store.InMemoryTaskStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PomRewriteNode} 的确定性单测 —— 不连库、不调模型、不跑 Maven。
 *
 * <p>钉的是三件事：<b>多模块工程的每一份 pom 都被改到</b>（只改根 pom 是这类功能最常见的
 * 半成品形态）、<b>产出带着完整新内容</b>（沙箱重建时靠它重放，丢了会让后续 VERIFY 在旧编译
 * 级别下必然失败）、以及<b>已经达标时不产生假改动</b>（否则每轮都多出一个无意义的补丁与回退风险）。
 */
class PomRewriteNodeTest {

    private final InMemoryTaskStore store = new InMemoryTaskStore();
    private final PomRewriteNode node = new PomRewriteNode(store);

    @TempDir
    Path workspace;

    private static final String PARENT_POM = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>legacy-parent</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
              <properties>
                <maven.compiler.release>8</maven.compiler.release>
              </properties>
              <modules>
                <module>core-lib</module>
              </modules>
            </project>
            """;

    private static final String MODULE_POM = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <artifactId>core-lib</artifactId>
              <properties>
                <maven.compiler.source>1.8</maven.compiler.source>
                <maven.compiler.target>1.8</maven.compiler.target>
              </properties>
            </project>
            """;

    private NodeContext contextFor(long taskId) {
        store.insertNode(taskId, PomRewriteNode.NODE_KEY, NodeType.POM_REWRITE, List.of(), 0);
        var dagNode = store.findNode(taskId, PomRewriteNode.NODE_KEY, 0).orElseThrow();
        return new NodeContext(store.findTask(taskId).orElseThrow(), dagNode, workspace, null);
    }

    private void writePom(String relative, String content) throws Exception {
        Path target = workspace.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("多模块：根 pom 与每个子模块 pom 都要改到，产出带完整新内容（供重放）")
    void upgradesEveryPomInMultiModuleProject() throws Exception {
        writePom("pom.xml", PARENT_POM);
        writePom("core-lib/pom.xml", MODULE_POM);
        long taskId = store.createTask(workspace.toString(), "core-lib/src/main/java/A.java", 21, null);

        NodeOutcome outcome = node.execute(contextFor(taskId));

        assertTrue(outcome.success(), () -> "应成功，实际=" + outcome.error());
        PomRewriteResult result = (PomRewriteResult) outcome.result();
        assertEquals(21, result.targetJdk());
        assertEquals(2, result.scannedPoms(), "扫到根 pom 与子模块 pom 各一份");
        assertEquals(List.of("pom.xml", "core-lib/pom.xml"), result.pomFiles(), "父 pom 必须排在子 pom 之前");
        assertTrue(result.changed());

        assertTrue(Files.readString(workspace.resolve("pom.xml")).contains(
                "<maven.compiler.release>21</maven.compiler.release>"));
        assertTrue(Files.readString(workspace.resolve("core-lib/pom.xml")).contains(
                "<maven.compiler.source>21</maven.compiler.source>"));

        // 重放依赖的是产出里的内容，而不是「再读一次磁盘」—— 这里断言它确实带着内容
        PomRewriteResult.FileChange rootChange = result.files().stream()
                .filter(f -> f.filePath().equals("pom.xml"))
                .findFirst().orElseThrow();
        assertTrue(rootChange.newContent().contains("<maven.compiler.release>21</maven.compiler.release>"));
        assertTrue(rootChange.summary().contains("8 → 21"), "说明要写清从哪个级别升到哪个级别：" + rootChange.summary());
    }

    @Test
    @DisplayName("每次改写都落一条补丁：pom 的变化同样要经得起人工审查")
    void recordsPatchForEachChangedPom() throws Exception {
        writePom("pom.xml", PARENT_POM);
        long taskId = store.createTask(workspace.toString(), "A.java", 21, null);

        node.execute(contextFor(taskId));

        List<PatchRecord> patches = store.findPatches(taskId);
        assertEquals(1, patches.size());
        PatchRecord patch = patches.get(0);
        assertEquals("pom.xml", patch.filePath());
        assertTrue(patch.diff().lines().anyMatch(line -> line.startsWith("+") && line.contains(">21<")),
                "diff 里应有 + 新级别那一行，实际 diff=\n" + patch.diff());
        assertTrue(patch.diff().lines().anyMatch(line -> line.startsWith("-") && line.contains(">8<")),
                "diff 里应有 - 旧级别那一行，实际 diff=\n" + patch.diff());
        assertEquals(com.remasteragent.tools.hash.ContentHash.sha256(PARENT_POM), patch.originalHash(),
                "基线指纹必须是改写前的内容 —— 回写预检靠它判断「源工程有没有被人动过」");
    }

    @Test
    @DisplayName("编译级别已达标：不改文件、不落补丁（幂等，避免每轮多一个假改动）")
    void noChangeWhenAlreadyAtTarget() throws Exception {
        writePom("pom.xml", """
                <project>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                  </properties>
                </project>
                """);
        long taskId = store.createTask(workspace.toString(), "A.java", 21, null);

        NodeOutcome outcome = node.execute(contextFor(taskId));

        assertTrue(outcome.success());
        PomRewriteResult result = (PomRewriteResult) outcome.result();
        assertFalse(result.changed());
        assertEquals(1, result.scannedPoms(), "扫过但没改 —— 与「没找到 pom」必须能区分开");
        assertTrue(store.findPatches(taskId).isEmpty());
    }

    @Test
    @DisplayName("工程里没有 pom.xml：显式失败，而不是「无事发生地成功」")
    void failsWhenNoPomFound() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        long taskId = store.createTask(workspace.toString(), "A.java", 21, null);

        NodeOutcome outcome = node.execute(contextFor(taskId));

        assertFalse(outcome.success(), "非 Maven 工程应当失败并说清原因，否则后续每个文件都会白跑一轮验证");
        assertTrue(outcome.error().contains("pom.xml"));
    }

    @Test
    @DisplayName("pom 本身就是坏 XML：拒绝改写并失败，不做「尽力而为」的文本手术")
    void failsOnMalformedPom() throws Exception {
        writePom("pom.xml", """
                <project>
                  <properties>
                    <maven.compiler.release>8</maven.compiler.release>
                </project>
                """);
        long taskId = store.createTask(workspace.toString(), "A.java", 21, null);

        NodeOutcome outcome = node.execute(contextFor(taskId));

        assertFalse(outcome.success());
        assertTrue(outcome.error().contains("无法改写"), outcome.error());
        assertTrue(Files.readString(workspace.resolve("pom.xml")).contains(">8<"),
                "失败时不得留下半截改动");
    }
}
