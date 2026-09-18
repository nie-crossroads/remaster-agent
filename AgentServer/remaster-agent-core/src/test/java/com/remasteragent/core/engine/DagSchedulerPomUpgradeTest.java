package com.remasteragent.core.engine;

import com.remasteragent.common.agent.AnalyzeResult;
import com.remasteragent.common.agent.PomRewriteResult;
import com.remasteragent.common.agent.RewriteResult;
import com.remasteragent.common.agent.VerifyResult;
import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.core.codec.JsonCodec;
import com.remasteragent.core.config.CoreProperties;
import com.remasteragent.core.engine.node.AnalyzeNode;
import com.remasteragent.core.engine.node.PomRewriteNode;
import com.remasteragent.core.engine.node.RewriteNode;
import com.remasteragent.core.engine.node.VerifyNode;
import com.remasteragent.core.progress.ProgressPublisher;
import com.remasteragent.core.store.InMemoryTaskStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「整仓 JDK 升级」在编排层的确定性单测 —— 不连库、不调模型、不跑 Maven。
 *
 * <p>钉三件最容易出错、且出错后现象与真因错位的事：
 * <ol>
 *   <li><b>顺序</b>：编译级别必须先于所有文件改写。反过来会让每个文件都「改对了但编译不过」，
 *       白烧整轮回退配额，失败反馈还会把模型引向「你的代码写错了」。</li>
 *   <li><b>达标不插节点</b>：已 release=21 的工程不该多出一个什么都不做的节点 ——
 *       否则每次任务都多一次「扫描 pom + 落 patch」的空转，评测报告的节点图上也会多一个噪声点。</li>
 *   <li><b>重放</b>：任务因人工门禁/评审挂起后重新入队会重建沙箱，pom 改动若不在 checkpoint 里，
 *       编译级别会悄悄退回升级前，后续 VERIFY 必然失败 —— 而报错指向代码。</li>
 * </ol>
 */
class DagSchedulerPomUpgradeTest {

    private static final String ENTRY = "src/main/java/com/example/Demo.java";

    private static final String POM_RELEASE_8 = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <artifactId>legacy-module</artifactId>
              <properties>
                <maven.compiler.release>8</maven.compiler.release>
              </properties>
            </project>
            """;

    private static final String POM_RELEASE_21 = POM_RELEASE_8.replace(">8<", ">21<");

    private static final String SOURCE = """
            package com.example;

            public class Demo {
                public String hi() {
                    return "hi";
                }
            }
            """;

    @TempDir
    Path tmp;

    private Path projectRoot;
    private Path workspaceRoot;

    @BeforeEach
    void setUp() throws IOException {
        workspaceRoot = tmp.resolve("ws");
        projectRoot = tmp.resolve("legacy-repo");
        Files.createDirectories(projectRoot);
        writePom(POM_RELEASE_8);
        Path entry = projectRoot.resolve(ENTRY);
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, SOURCE, StandardCharsets.UTF_8);
    }

    private void writePom(String content) throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"), content, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("根 pom 低于目标 JDK：先铺 POM_REWRITE，文件改写链挂在它之后，pom 真的被升到 21")
    void insertsPomNodeBeforeRewrites() throws IOException {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = store.createTask(projectRoot.toString(), ENTRY, 21, null);

        scheduler(store).runTask(taskId);

        assertEquals(TaskStatus.SUCCEEDED, store.findTask(taskId).orElseThrow().status());

        DagNode pomNode = nodeOfType(store, taskId, NodeType.POM_REWRITE);
        DagNode rewriteNode = nodeOfType(store, taskId, NodeType.REWRITE);
        DagNode analyzeNode = nodeOfType(store, taskId, NodeType.ANALYZE);

        assertEquals(List.of(analyzeNode.id()), pomNode.dependsOn(), "POM_REWRITE 应接在 ANALYZE 之后");
        assertEquals(List.of(pomNode.id()), rewriteNode.dependsOn(),
                "文件改写必须依赖 POM_REWRITE —— 顺序反了会让每个文件都在旧编译级别下失败");

        // 沙箱里的 pom 真的升级了（这是「整仓升级」这句话的物证）
        String workspacePom = Files.readString(
                workspaceRoot.resolve("task-" + taskId).resolve("pom.xml"), StandardCharsets.UTF_8);
        assertTrue(workspacePom.contains("<maven.compiler.release>21</maven.compiler.release>"),
                "沙箱内的 pom 应已升到 21，实际=\n" + workspacePom);

        // pom 的改动同样要落补丁，供人工审查
        assertTrue(store.findPatches(taskId).stream()
                        .anyMatch(p -> p.filePath().equals("pom.xml")),
                "pom 的变化必须留下补丁");

        // 源工程全程只读 —— 升级发生在沙箱副本里
        assertEquals(POM_RELEASE_8, Files.readString(projectRoot.resolve("pom.xml")),
                "回写是显式动作，任务跑完不得直接动源工程");
    }

    @Test
    @DisplayName("根 pom 已达标：不铺 POM_REWRITE 节点（否则每次任务都多一次空转）")
    void skipsPomNodeWhenAlreadyAtTarget() throws IOException {
        writePom(POM_RELEASE_21);
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = store.createTask(projectRoot.toString(), ENTRY, 21, null);

        scheduler(store).runTask(taskId);

        assertEquals(TaskStatus.SUCCEEDED, store.findTask(taskId).orElseThrow().status());
        assertFalse(store.findNodes(taskId).stream().anyMatch(n -> n.nodeType() == NodeType.POM_REWRITE),
                "编译级别已达标，不该多出一个什么都不做的节点");
        assertEquals(3, store.findNodes(taskId).size(), "应为 ANALYZE → REWRITE → VERIFY 三步（无 PLAN）");
    }

    @Test
    @DisplayName("断点续跑：pom 改动由 checkpoint 重放，重建沙箱后不会退回旧编译级别")
    void replaysPomChangeWhenWorkspaceRebuilt() throws IOException {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = store.createTask(projectRoot.toString(), ENTRY, 21, null);

        // 造一个「上一轮已经成功升级过编译级别」的现场：盘上是 release=8，checkpoint 里是 21。
        // 这正是「门禁挂起 → 沙箱被清空重建 → 批准后重新入队」时的真实状态。
        long analyzeId = store.insertNode(taskId, AnalyzeNode.NODE_KEY, NodeType.ANALYZE, List.of(), 0);
        store.markNodeSucceeded(analyzeId, new JsonCodec().write(new AnalyzeResult(
                ENTRY, "com.example", "Demo", List.of("Demo#hi"), SOURCE, "单类单方法")));
        long pomId = store.insertNode(taskId, PomRewriteNode.NODE_KEY, NodeType.POM_REWRITE,
                List.of(analyzeId), 0);
        String upgraded = POM_RELEASE_8.replace(">8<", ">21<");
        store.markNodeSucceeded(pomId, new JsonCodec().write(new PomRewriteResult(
                21, 1, List.of("pom.xml"),
                List.of(new PomRewriteResult.FileChange("pom.xml", upgraded,
                        "maven.compiler.release: 8 → 21")))));

        long rewriteId = store.insertNode(taskId, RewriteNode.NODE_KEY, NodeType.REWRITE,
                List.of(pomId), 0);
        store.markNodeSucceeded(rewriteId, new JsonCodec().write(new RewriteResult(
                ENTRY, SOURCE.replace("return \"hi\";", "return \"hi!\";"),
                "@@ -1,5 +1,5 @@", "改写", "stub", 0)));
        long verifyId = store.insertNode(taskId, VerifyNode.NODE_KEY, NodeType.VERIFY,
                List.of(rewriteId), 0);
        store.markNodeSucceeded(verifyId, new JsonCodec().write(
                new VerifyResult(true, 0, 1, 1, 0, 0, 0.5d, "", 1200L, false)));

        scheduler(store).runTask(taskId);

        String workspacePom = Files.readString(
                workspaceRoot.resolve("task-" + taskId).resolve("pom.xml"), StandardCharsets.UTF_8);
        assertTrue(workspacePom.contains("<maven.compiler.release>21</maven.compiler.release>"),
                "pom 改动没被重放 = 后续 VERIFY 会在 release=8 下编译新语法并必然失败；实际=\n" + workspacePom);
        assertEquals(TaskStatus.SUCCEEDED, store.findTask(taskId).orElseThrow().status());
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    /** 只装配「无 PLAN」的三步拓扑，外加真实的 POM_REWRITE 节点。 */
    private DagScheduler scheduler(InMemoryTaskStore store) {
        CoreProperties properties = CoreProperties.withoutGateTimeout(
                2, List.of("test"), workspaceRoot.toString(), false, false, false,
                true, Duration.ofHours(24));
        return new DagScheduler(store, properties, new JsonCodec(), List.of(
                new StubExecutor(NodeType.ANALYZE, ctx -> NodeOutcome.ok(new AnalyzeResult(
                        ENTRY, "com.example", "Demo", List.of("Demo#hi"), SOURCE, "单类单方法"))),
                new PomRewriteNode(store),
                new StubExecutor(NodeType.REWRITE, ctx -> NodeOutcome.ok(new RewriteResult(
                        ENTRY, SOURCE.replace("return \"hi\";", "return \"hi!\";"),
                        "@@ -1,5 +1,5 @@", "改写", "stub", ctx.attempt()))),
                new StubExecutor(NodeType.VERIFY, ctx -> NodeOutcome.ok(
                        new VerifyResult(true, 0, 1, 1, 0, 0, 0.5d, "", 1200L, false)))
        ), publishers());
    }

    private static DagNode nodeOfType(InMemoryTaskStore store, long taskId, NodeType type) {
        return store.findNodes(taskId).stream()
                .filter(node -> node.nodeType() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少节点类型: " + type
                        + "，实际有 " + store.findNodes(taskId).stream().map(DagNode::nodeKey).toList()));
    }

    private static ObjectProvider<ProgressPublisher> publishers() {
        return new ObjectProvider<>() {
            @Override
            public ProgressPublisher getObject() {
                return ProgressPublisher.NOOP;
            }

            @Override
            public ProgressPublisher getObject(Object... args) {
                return ProgressPublisher.NOOP;
            }

            @Override
            public ProgressPublisher getIfAvailable() {
                return ProgressPublisher.NOOP;
            }

            @Override
            public ProgressPublisher getIfUnique() {
                return ProgressPublisher.NOOP;
            }
        };
    }

    private static final class StubExecutor implements NodeExecutor {

        private final NodeType type;
        private final java.util.function.Function<NodeContext, NodeOutcome> behavior;

        StubExecutor(NodeType type, java.util.function.Function<NodeContext, NodeOutcome> behavior) {
            this.type = type;
            this.behavior = behavior;
        }

        @Override
        public NodeType type() {
            return type;
        }

        @Override
        public NodeOutcome execute(NodeContext context) {
            return behavior.apply(context);
        }
    }
}
