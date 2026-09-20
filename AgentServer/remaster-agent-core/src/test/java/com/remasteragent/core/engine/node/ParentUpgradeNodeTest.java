package com.remasteragent.core.engine.node;

import com.remasteragent.common.agent.ParentUpgradeResult;
import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.core.engine.NodeContext;
import com.remasteragent.core.engine.NodeOutcome;
import com.remasteragent.core.store.InMemoryTaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParentUpgradeNodeTest {

    private static final String POM_WITH_PARENT = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<project>\n"
            + "  <modelVersion>4.0.0</modelVersion>\n"
            + "  <parent>\n"
            + "    <groupId>org.springframework.boot</groupId>\n"
            + "    <artifactId>spring-boot-starter-parent</artifactId>\n"
            + "    <version>2.7.17</version>\n"
            + "    <relativePath/>\n"
            + "  </parent>\n"
            + "  <groupId>com.example</groupId>\n"
            + "  <artifactId>demo</artifactId>\n"
            + "  <dependencies></dependencies>\n"
            + "</project>\n";

    private static final String POM_WITHOUT_PARENT = "<?xml version=\"1.0\"?>\n"
            + "<project><modelVersion>4.0.0</modelVersion><dependencies></dependencies></project>\n";

    private NodeOutcome run(Path workspace, InMemoryTaskStore store) {
        long taskId = store.createTask(workspace.toString(), "src/main/java/com/example/Foo.java", 21, "test");
        long nodeId = store.insertNode(taskId, ParentUpgradeNode.NODE_KEY,
                NodeType.PARENT_UPGRADE, List.of(), 0);
        DagNode dagNode = store.findNode(taskId, ParentUpgradeNode.NODE_KEY, 0).orElseThrow();
        MigrationTask task = store.findTask(taskId).orElseThrow();
        NodeContext ctx = new NodeContext(task, dagNode, workspace, null);
        return new ParentUpgradeNode(store).execute(ctx);
    }

    @Test
    void upgradesSpringBootParentVersion(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("pom.xml"), POM_WITH_PARENT, StandardCharsets.UTF_8);

        NodeOutcome outcome = run(workspace, new InMemoryTaskStore());

        assertTrue(outcome.success());
        ParentUpgradeResult result = (ParentUpgradeResult) outcome.result();
        assertTrue(result.changed());
        String pom = Files.readString(workspace.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertTrue(pom.contains(
                "<artifactId>spring-boot-starter-parent</artifactId>\n    <version>3.5.16</version>"));
        assertFalse(result.files().isEmpty());
    }

    @Test
    void doesNotUpgradeWhenNoSpringBootParent(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("pom.xml"), POM_WITHOUT_PARENT, StandardCharsets.UTF_8);

        NodeOutcome outcome = run(workspace, new InMemoryTaskStore());

        assertTrue(outcome.success());
        ParentUpgradeResult result = (ParentUpgradeResult) outcome.result();
        assertFalse(result.changed(), "没有 spring-boot parent 的 pom 不应被改动");
    }

    @Test
    void upgradesOnlyThePomThatDeclaresSpringBootParent(@TempDir Path workspace) throws IOException {
        // 根 pom 声明了 spring-boot-starter-parent；子模块 pom 的 parent 指向根模块（非 spring-boot）
        Files.writeString(workspace.resolve("pom.xml"), POM_WITH_PARENT, StandardCharsets.UTF_8);
        Files.createDirectories(workspace.resolve("module-a"));
        String modulePom = "<?xml version=\"1.0\"?>\n<project>\n"
                + "  <parent>\n"
                + "    <groupId>com.example</groupId>\n"
                + "    <artifactId>demo</artifactId>\n"
                + "    <version>0.0.1-SNAPSHOT</version>\n"
                + "  </parent>\n"
                + "  <artifactId>module-a</artifactId>\n"
                + "</project>\n";
        Files.writeString(workspace.resolve("module-a/pom.xml"), modulePom, StandardCharsets.UTF_8);

        NodeOutcome outcome = run(workspace, new InMemoryTaskStore());
        assertTrue(outcome.success());

        String rootPom = Files.readString(workspace.resolve("pom.xml"), StandardCharsets.UTF_8);
        String modulePomAfter = Files.readString(workspace.resolve("module-a/pom.xml"), StandardCharsets.UTF_8);
        assertTrue(rootPom.contains("<version>3.5.16</version>"), "根 pom 应被升级");
        assertFalse(modulePomAfter.contains("3.5.16"), "子模块 pom 的 parent 指向根模块，不应被动");
        assertEquals(1, ((ParentUpgradeResult) outcome.result()).files().size());
    }
}
