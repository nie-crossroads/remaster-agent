package com.remasteragent.core.engine.node;

import com.remasteragent.common.agent.DependencyUpgradeResult;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyUpgradeNodeTest {

    private static final String POM = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<project>\n"
            + "  <modelVersion>4.0.0</modelVersion>\n"
            + "  <dependencies>\n"
            + "  </dependencies>\n"
            + "</project>\n";

    private static void writeJava(Path dir, String content) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Foo.java"), content, StandardCharsets.UTF_8);
    }

    private NodeOutcome run(Path workspace, InMemoryTaskStore store) {
        long taskId = store.createTask(workspace.toString(), "src/main/java/com/example/Foo.java", 21, "test");
        long nodeId = store.insertNode(taskId, DependencyUpgradeNode.NODE_KEY,
                NodeType.DEPENDENCY_UPGRADE, List.of(), 0);
        DagNode dagNode = store.findNode(taskId, DependencyUpgradeNode.NODE_KEY, 0).orElseThrow();
        MigrationTask task = store.findTask(taskId).orElseThrow();
        NodeContext ctx = new NodeContext(task, dagNode, workspace, null);
        return new DependencyUpgradeNode(store).execute(ctx);
    }

    @Test
    void injectsJakartaDependencyForRemovedJavaxImport(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("pom.xml"), POM, StandardCharsets.UTF_8);
        writeJava(workspace.resolve("src/main/java/com/example"),
                "package com.example;\nimport javax.annotation.Resource;\npublic class Foo {}\n");

        NodeOutcome outcome = run(workspace, new InMemoryTaskStore());

        assertTrue(outcome.success());
        DependencyUpgradeResult result = (DependencyUpgradeResult) outcome.result();
        assertTrue(result.changed());
        String pom = Files.readString(workspace.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertTrue(pom.contains("jakarta.annotation-api"), "pom 应注入 jakarta.annotation-api");
        assertTrue(pom.contains("<scope>provided</scope>"));
        // 必须落补丁
        assertFalse(result.files().isEmpty());
    }

    @Test
    void injectsServletAndAnnotationForMixedImports(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("pom.xml"), POM, StandardCharsets.UTF_8);
        writeJava(workspace.resolve("src/main/java/com/example"),
                "package com.example;\n"
                        + "import javax.annotation.Resource;\n"
                        + "import javax.servlet.http.HttpServletRequest;\n"
                        + "public class Foo {}\n");

        NodeOutcome outcome = run(workspace, new InMemoryTaskStore());
        assertTrue(outcome.success());
        String pom = Files.readString(workspace.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertTrue(pom.contains("jakarta.annotation-api"));
        assertTrue(pom.contains("jakarta.servlet-api"));
    }

    @Test
    void injectsHttpClient5ForSpring6RequestFactory(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("pom.xml"), POM, StandardCharsets.UTF_8);
        // 这个文件一个 javax import 都没有 —— 命名空间那把尺子完全看不见它，
        // 但 Spring 6 把 HttpComponentsClientHttpRequestFactory 的底层从 HttpClient 4 换成了 5，
        // classpath 里没有 httpclient5 时，源码改得再对也编不过（博客工程就卡在这一条）。
        writeJava(workspace.resolve("src/main/java/com/example"),
                "package com.example;\n"
                        + "import org.apache.http.impl.client.HttpClients;\n"
                        + "import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;\n"
                        + "import org.springframework.web.client.RestTemplate;\n"
                        + "public class Foo {\n"
                        + "  public RestTemplate restTemplate() {\n"
                        + "    return new RestTemplate(new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));\n"
                        + "  }\n"
                        + "}\n");

        NodeOutcome outcome = run(workspace, new InMemoryTaskStore());

        assertTrue(outcome.success());
        String pom = Files.readString(workspace.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertTrue(pom.contains("httpclient5"),
                "框架破坏性 API 换掉的底层库属于依赖缺口，必须补进 pom: " + pom);
        assertTrue(pom.contains("org.apache.httpcomponents.client5"));
    }

    @Test
    void doesNotInjectWhenNoRemovedImport(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("pom.xml"), POM, StandardCharsets.UTF_8);
        writeJava(workspace.resolve("src/main/java/com/example"),
                "package com.example;\nimport java.util.List;\npublic class Foo {}\n");

        NodeOutcome outcome = run(workspace, new InMemoryTaskStore());
        assertTrue(outcome.success());
        DependencyUpgradeResult result = (DependencyUpgradeResult) outcome.result();
        assertFalse(result.changed(), "没有移除风险时不应改动 pom");
    }

    @Test
    void injectsOnlyIntoTheModuleThatUsesTheImport(@TempDir Path workspace) throws IOException {
        // 根 pom 所在模块没有任何被移除 import 的源码；module-a 才有
        Files.writeString(workspace.resolve("pom.xml"), POM, StandardCharsets.UTF_8);
        Files.createDirectories(workspace.resolve("module-a"));
        Files.writeString(workspace.resolve("module-a/pom.xml"), POM, StandardCharsets.UTF_8);
        writeJava(workspace.resolve("module-a/src/main/java/com/example"),
                "package com.example;\nimport javax.annotation.Resource;\npublic class Bar {}\n");

        NodeOutcome outcome = run(workspace, new InMemoryTaskStore());
        assertTrue(outcome.success());

        String rootPom = Files.readString(workspace.resolve("pom.xml"), StandardCharsets.UTF_8);
        String modulePom = Files.readString(workspace.resolve("module-a/pom.xml"), StandardCharsets.UTF_8);
        assertFalse(rootPom.contains("jakarta.annotation-api"), "根 pom 不应被注入（其模块未使用该包）");
        assertTrue(modulePom.contains("jakarta.annotation-api"), "module-a 的 pom 应被注入");
        assertEquals(1, ((DependencyUpgradeResult) outcome.result()).files().size());
    }

    @Test
    void appliesCoordinateAndVersionUpgradesWithoutJavaxImports(@TempDir Path workspace) throws IOException {
        // 工程只含有需坐标/版本规范化的旧依赖，没有任何 javax.* 移除风险 import
        String pom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project>\n  <modelVersion>4.0.0</modelVersion>\n  <dependencies>\n"
                + "    <dependency>\n      <groupId>mysql</groupId>\n      <artifactId>mysql-connector-java</artifactId>\n      <version>8.0.33</version>\n      <scope>runtime</scope>\n    </dependency>\n"
                + "    <dependency>\n      <groupId>org.mybatis.spring.boot</groupId>\n      <artifactId>mybatis-spring-boot-starter</artifactId>\n      <version>2.3.1</version>\n    </dependency>\n"
                + "  </dependencies>\n</project>\n";
        Files.writeString(workspace.resolve("pom.xml"), pom, StandardCharsets.UTF_8);
        writeJava(workspace.resolve("src/main/java/com/example"),
                "package com.example;\nimport java.util.List;\npublic class Foo {}\n");

        NodeOutcome outcome = run(workspace, new InMemoryTaskStore());
        assertTrue(outcome.success());

        String resultPom = Files.readString(workspace.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertTrue(resultPom.contains("<groupId>com.mysql</groupId>"), "mysql 坐标应改为 com.mysql");
        assertTrue(resultPom.contains("<artifactId>mysql-connector-j</artifactId>"), "mysql artifact 应改为 mysql-connector-j");
        assertTrue(resultPom.contains("<version>3.0.3</version>"), "mybatis 应升到 3.0.3");
        assertFalse(resultPom.contains("mysql-connector-java"), "旧 mysql 坐标不应残留");
        DependencyUpgradeResult result = (DependencyUpgradeResult) outcome.result();
        assertTrue(result.changed());
    }
}
