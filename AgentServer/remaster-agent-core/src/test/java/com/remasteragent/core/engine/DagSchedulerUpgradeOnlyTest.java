package com.remasteragent.core.engine;

import com.remasteragent.common.agent.AnalyzeResult;
import com.remasteragent.common.agent.RewriteResult;
import com.remasteragent.common.agent.VerifyResult;
import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.core.codec.JsonCodec;
import com.remasteragent.core.config.CoreProperties;
import com.remasteragent.core.engine.node.AnalyzeNode;
import com.remasteragent.core.engine.node.GateNode;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「整仓升级」模式（没有入口文件）在编排层的单测 —— 不连库、不调模型、不跑 Maven。
 *
 * <p>这个模式与常规迁移的差别全在拓扑上，所以这里钉的也是拓扑：
 * <ol>
 *   <li><b>不铺 ANALYZE</b>：它读的就是入口文件（这里没有），而它顺带做的工程索引
 *       对这个模式毫无用处 —— 跑一遍只是白烧 embedding 调用。</li>
 *   <li><b>不铺 REWRITE</b>：没有改写目标，也没有模型参与。</li>
 *   <li><b>有 PLAN 执行器也不走 PLAN</b>：整仓升级没有「要改哪个文件」可规划。
 *       这是最容易写漏的一条 —— 常规路径里 PLAN 分支排在前面，不加判断就会被它抢先返回。</li>
 *   <li><b>缺 POM_REWRITE 执行器必须显式失败</b>：这个模式的全部内容就是那一个节点，
 *       静默退化会得到一个「什么也没做却显示成功」的任务。</li>
 * </ol>
 */
class DagSchedulerUpgradeOnlyTest {

    private static final String POM_RELEASE_8 = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <artifactId>%s</artifactId>
              <properties>
                <maven.compiler.release>8</maven.compiler.release>
              </properties>
            </project>
            """;

    /** 一份什么都不声明的 pom —— 覆盖「靠插入属性完成升级」那条路径。 */
    private static final String POM_BARE = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <artifactId>legacy-core</artifactId>
            </project>
            """;

    @TempDir
    Path tmp;

    private Path projectRoot;
    private Path workspaceRoot;

    @BeforeEach
    void setUp() throws IOException {
        workspaceRoot = tmp.resolve("ws");
        projectRoot = tmp.resolve("legacy-repo");

        // 三份 pom 三种写法，正好覆盖「整仓」的含义：根 pom 老式三元组、子模块各自为政
        writePom("pom.xml", POM_RELEASE_8.formatted("legacy-parent"));
        writePom("legacy-core/pom.xml", POM_BARE);
        writePom("legacy-report/pom.xml", POM_RELEASE_8.formatted("legacy-report"));
    }

    private void writePom(String relativePath, String content) throws IOException {
        Path pom = projectRoot.resolve(relativePath);
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, content, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("整仓升级拓扑：只有 POM_REWRITE → VERIFY，三份 pom 在沙箱里全部升到 21")
    void upgradeOnlyTopology() throws IOException {
        InMemoryTaskStore store = new InMemoryTaskStore();
        // entryFile 传 null —— 这就是「整仓升级」模式的全部信号
        long taskId = store.createTask(projectRoot.toString(), null, 21, "整仓升级");

        scheduler(store, false, false).runTask(taskId);

        assertEquals(TaskStatus.SUCCEEDED, store.findTask(taskId).orElseThrow().status());

        List<NodeType> types = store.findNodes(taskId).stream().map(DagNode::nodeType).toList();
        assertEquals(List.of(NodeType.POM_REWRITE, NodeType.VERIFY), types,
                "整仓升级只该有这两个节点；实际=" + types);

        DagNode pomNode = nodeOfType(store, taskId, NodeType.POM_REWRITE);
        DagNode verifyNode = nodeOfType(store, taskId, NodeType.VERIFY);
        assertTrue(pomNode.dependsOn().isEmpty(), "POM_REWRITE 是本模式的起点，不该有前置");
        assertEquals(List.of(pomNode.id()), verifyNode.dependsOn());

        // 「整仓」的物证：三份 pom 在沙箱里都到了 21，包括那份什么都没声明的
        for (String relative : List.of("pom.xml", "legacy-core/pom.xml", "legacy-report/pom.xml")) {
            String content = Files.readString(
                    workspaceRoot.resolve("task-" + taskId).resolve(relative), StandardCharsets.UTF_8);
            assertTrue(content.contains("<maven.compiler.release>21</maven.compiler.release>"),
                    relative + " 应已升到 21，实际=\n" + content);
        }

        // 源工程全程只读 —— 升级发生在沙箱副本里，回写是另一个显式动作
        assertEquals(POM_BARE, Files.readString(projectRoot.resolve("legacy-core/pom.xml")),
                "任务跑完不得直接动源工程");
    }

    @Test
    @DisplayName("装配了 PLAN 执行器也不走 PLAN —— 整仓升级没有「要改哪个文件」可规划")
    void planExecutorIsNotUsedInUpgradeOnlyMode() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = store.createTask(projectRoot.toString(), null, 21, null);

        scheduler(store, true, false).runTask(taskId);

        List<NodeType> types = store.findNodes(taskId).stream().map(DagNode::nodeType).toList();
        assertFalse(types.contains(NodeType.PLAN),
                "PLAN 分支在常规路径里排在前面，这里不加判断就会被它抢先返回；实际=" + types);
        assertFalse(types.contains(NodeType.ANALYZE), "实际=" + types);
        assertFalse(types.contains(NodeType.REWRITE), "实际=" + types);
    }

    @Test
    @DisplayName("缺 POM_REWRITE 执行器：显式失败并说清原因，不静默退化成一个空任务")
    void missingPomRewriteExecutorFailsLoudly() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = store.createTask(projectRoot.toString(), null, 21, null);

        // 异常会继续抛给调用方（Worker 的消费循环负责兜住它、接着消费下一个任务），
        // 但任务状态必须在抛之前就已经落成 FAILED —— 否则重启后这条记录会以
        // 「排队中」的样子永远留着，而队列里根本没有它的消息
        assertThrows(IllegalStateException.class,
                () -> schedulerWithoutPomRewrite(store).runTask(taskId));

        var task = store.findTask(taskId).orElseThrow();
        assertEquals(TaskStatus.FAILED, task.status());
        assertTrue(task.failReason() != null && task.failReason().contains("POM_REWRITE"),
                "失败原因要能直接指出缺什么；实际=" + task.failReason());
    }

    @Test
    @DisplayName("开了改写门禁：GATE 插在 POM_REWRITE 与 VERIFY 之间，任务挂起等人")
    void gateSitsBetweenPomAndVerify() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = store.createTask(projectRoot.toString(), null, 21, null);

        scheduler(store, false, true).runTask(taskId);

        assertEquals(TaskStatus.WAITING_HUMAN, store.findTask(taskId).orElseThrow().status(),
                "门禁挂起不是完成，也不占 Worker");

        List<NodeType> types = store.findNodes(taskId).stream().map(DagNode::nodeType).toList();
        assertEquals(List.of(NodeType.POM_REWRITE, NodeType.GATE, NodeType.VERIFY), types,
                "整仓升级也走同一套门禁语义，但门禁键不带文件：实际=" + types);

        DagNode gateNode = nodeOfType(store, taskId, NodeType.GATE);
        assertEquals("gate", gateNode.nodeKey(),
                "这个模式改的是全仓多份 pom，没有单个文件可点名，门禁键该是裸键");
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    private DagScheduler scheduler(InMemoryTaskStore store, boolean withPlan, boolean requireGateApproval) {
        List<NodeExecutor> executors = new ArrayList<>();
        executors.add(new StubExecutor(NodeType.ANALYZE, ctx -> NodeOutcome.ok(new AnalyzeResult(
                "unused", "com.example", "Demo", List.of(), "", "本模式的桩件，正常不会被调度"))));
        executors.add(new PomRewriteNode(store));
        executors.add(rewriteStub());
        executors.add(verifyStub());
        if (withPlan) {
            executors.add(new StubExecutor(NodeType.PLAN, ctx -> NodeOutcome.ok("plan-stub")));
        }
        if (requireGateApproval) {
            executors.add(new GateNode(store));
        }
        return build(store, executors, requireGateApproval);
    }

    /** 刻意不装 {@link PomRewriteNode}，用于验证「缺执行器不静默退化」。 */
    private DagScheduler schedulerWithoutPomRewrite(InMemoryTaskStore store) {
        List<NodeExecutor> executors = new ArrayList<>();
        executors.add(new StubExecutor(NodeType.ANALYZE, ctx -> NodeOutcome.ok(new AnalyzeResult(
                "unused", "com.example", "Demo", List.of(), "", "桩件"))));
        executors.add(rewriteStub());
        executors.add(verifyStub());
        return build(store, executors, false);
    }

    private DagScheduler build(InMemoryTaskStore store, List<NodeExecutor> executors,
                               boolean requireGateApproval) {
        CoreProperties properties = CoreProperties.withoutGateTimeout(
                2, List.of("test"), workspaceRoot.toString(), false, false,
                requireGateApproval, true, Duration.ofHours(24));
        return new DagScheduler(store, properties, new JsonCodec(), executors, publishers());
    }

    private static StubExecutor rewriteStub() {
        return new StubExecutor(NodeType.REWRITE, ctx -> NodeOutcome.ok(new RewriteResult(
                "src/main/java/com/example/Demo.java", "class Demo {}",
                "@@ -1 +1 @@", "改写", "stub", ctx.attempt())));
    }

    private static StubExecutor verifyStub() {
        return new StubExecutor(NodeType.VERIFY, ctx -> NodeOutcome.ok(
                new VerifyResult(true, 0, 1, 1, 0, 0, 0.5d, "", 1200L, false)));
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
