package com.remasteragent.core.engine;

import com.remasteragent.core.codec.JsonCodec;
import com.remasteragent.common.agent.AnalyzeResult;
import com.remasteragent.common.domain.TaskMetrics;
import com.remasteragent.common.agent.RewriteResult;
import com.remasteragent.common.agent.VerifyResult;
import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.NodeStatus;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.core.config.CoreProperties;
import com.remasteragent.core.engine.node.AnalyzeNode;
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
 * 批语义（阶段 5 多文件迁移）的确定性单测 —— 钉死「整仓 VERIFY + 精准重跑」这条最贵也最易错的逻辑。
 *
 * <h2>这个测试存在的原因</h2>
 * <p>旧拓扑是「每文件 rewrite → verify」各铺一条链，但 VERIFY 跑的是整仓 {@code mvn test}。
 * 一旦有一个文件还 javax、其它已改完，VERIFY 会因整仓编不过而失败，于是反复重跑已改完的文件 ——
 * 永远修不到那个还 javax 的文件，形成不收敛死循环，且白白烧掉整轮 VERIFY 配额。这正是博客工程
 * 端到端卡死的根因。修正后的拓扑（{@code PlanNode}）：先铺所有 REWRITE，再铺<b>一条</b>依赖全部
 * REWRITE 的整仓 VERIFY；回退时按「工作目录里仍含旧 import 的文件」<b>精准</b>重跑，干净文件不重跑。</p>
 *
 * <p>用桩件精确控制成败、内存 store、临时目录，与 {@code DagSchedulerRollbackTest} 同一手法，
 * 但专门复现<b>多文件 + 整仓 VERIFY</b> 这一原本没有覆盖到的路径。</p>
 */
class DagSchedulerBatchVerifyTest {

    private static final String F1 = "src/main/java/com/example/F1.java";
    private static final String F2 = "src/main/java/com/example/F2.java";

    private static final String F1_SOURCE = """
            package com.example;

            public class F1 {
                public String hi() { return "hi"; }
            }
            """;

    // F2 第一轮改写「漏改」（仍含 javax），用来复现「模型只改了部分文件」的真实情形
    private static final String F2_WITH_JAVAX = """
            package com.example;

            import javax.persistence.Entity;

            public class F2 {
                @Entity
                private String name;
            }
            """;

    private static final String F2_WITH_JAKARTA = """
            package com.example;

            import jakarta.persistence.Entity;

            public class F2 {
                @Entity
                private String name;
            }
            """;

    private static final String F3 = "src/main/java/com/example/F3.java";

    // F3 第一轮改写「漏改」（仍含 javax.annotation.Resource）—— 复现博客工程曾被漏改的真实失误
    private static final String F3_WITH_JAVAX = """
            package com.example;

            import javax.annotation.Resource;

            public class F3 {
                @Resource
                private Object dep;
            }
            """;

    private static final String F3_WITH_JAKARTA = """
            package com.example;

            import jakarta.annotation.Resource;

            public class F3 {
                @Resource
                private Object dep;
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
        Path f1 = projectRoot.resolve(F1);
        Path f2 = projectRoot.resolve(F2);
        Files.createDirectories(f1.getParent());
        Files.createDirectories(f2.getParent());
        Files.writeString(f1, F1_SOURCE, StandardCharsets.UTF_8);
        Files.writeString(f2, F2_WITH_JAVAX, StandardCharsets.UTF_8);
    }

    // ==================================================================
    // 1. 整仓 VERIFY 失败 → 只精准重跑仍含旧 import 的文件，干净文件不重跑，最终 SUCCEEDED
    // ==================================================================

    @Test
    @DisplayName("多文件：VERIFY 因 F2 仍含 javax 失败，精准重跑 F2（不重跑已干净的 F1），收敛为 SUCCEEDED")
    void verifyFailureRepairsOnlyUnmigratedFile() throws IOException {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = store.createTask(projectRoot.toString(), F1, 21, null);

        // 预置 PLAN 拓扑：ANALYZE → 两条 REWRITE（F1/F2）→ 一条整仓 VERIFY（依赖两条 REWRITE）。
        // 与生产 PlanNode 的批语义拓扑一致：VERIFY 是唯一的、依赖全部改写的那一条。
        long analyzeId = store.insertNode(taskId, AnalyzeNode.NODE_KEY, NodeType.ANALYZE, List.of(), 0);
        store.markNodeSucceeded(analyzeId, analyzeJson(F1));
        store.insertNode(taskId, RewriteNode.nodeKey(F1), NodeType.REWRITE, List.of(analyzeId), 0);
        store.insertNode(taskId, RewriteNode.nodeKey(F2), NodeType.REWRITE, List.of(analyzeId), 0);
        store.insertNode(taskId, VerifyNode.NODE_KEY, NodeType.VERIFY,
                List.of(store.findNode(taskId, RewriteNode.nodeKey(F1), 0).orElseThrow().id(),
                        store.findNode(taskId, RewriteNode.nodeKey(F2), 0).orElseThrow().id()), 0);

        // 桩件：REWRITE 把内容写到工作目录；F2 第一轮「漏改」（仍 javax），第二轮才迁移。
        // VERIFY 读工作目录：F2 仍含 javax 就失败，迁移完才通过 —— 把验证结果钉在真实的文件状态上。
        StubExecutor rewrite = new StubExecutor(NodeType.REWRITE, ctx -> {
            String file = keySuffix(ctx.node().nodeKey());
            String content;
            if (file.endsWith("F2.java")) {
                content = ctx.attempt() == 0 ? F2_WITH_JAVAX : F2_WITH_JAKARTA;
            } else {
                content = F1_SOURCE;
            }
            writeWorkspace(ctx.workspace(), file, content);
            return NodeOutcome.ok(new RewriteResult(file, content, "@@", "迁移", "stub", ctx.attempt()));
        });
        StubExecutor verify = new StubExecutor(NodeType.VERIFY, ctx -> {
            String f2 = readWorkspace(ctx.workspace(), F2);
            if (f2 != null && f2.contains("javax")) {
                return NodeOutcome.fail("编译失败: F2.java 仍含 javax.persistence，整仓编译不通过");
            }
            return NodeOutcome.ok(greenVerify());
        });

        scheduler(store, rewrite, verify).runTask(taskId);

        assertEquals(TaskStatus.SUCCEEDED, store.findTask(taskId).orElseThrow().status());

        // 关键断言：F1 已干净，从未被重跑（只有 attempt 0）；F2 被精准重跑（attempt 0 → 1）。
        assertEquals(List.of(0), attemptsOfFile(store, taskId, RewriteNode.nodeKey(F1)),
                "已干净的 F1 不应被无谓重跑（否则会触发 +0/-0 死循环）");
        assertEquals(List.of(0, 1), attemptsOfFile(store, taskId, RewriteNode.nodeKey(F2)),
                "仍含旧 import 的 F2 应被精准重跑一轮");

        // 整仓 VERIFY：第 0 轮失败（F2 未迁移），第 1 轮通过；不再派生第 2 轮
        assertEquals(NodeStatus.FAILED, store.statusOf(taskId, VerifyNode.NODE_KEY, 0));
        assertEquals(NodeStatus.SUCCEEDED, store.statusOf(taskId, VerifyNode.NODE_KEY, 1));
        assertFalse(store.hasNode(taskId, VerifyNode.NODE_KEY, 2), "已收敛，不应再派生 VERIFY");

        // 工作目录最终态：F2 真的被迁移成 jakarta
        assertTrue(readWorkspace(workspaceRoot.resolve("task-" + taskId), F2).contains("jakarta.persistence"),
                "收敛后 F2 应已迁移为 jakarta");
    }

    // ==================================================================
    // 2. 单文件改写失败 → 只重跑该文件，整仓 VERIFY 被跳过一次，收敛为 SUCCEEDED
    // ==================================================================

    @Test
    @DisplayName("多文件：F2 首轮改写失败，只重跑 F2，其余保持，最终 SUCCEEDED")
    void rewriteFailureRetriesOnlyThatFile() throws IOException {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = store.createTask(projectRoot.toString(), F1, 21, null);

        long analyzeId = store.insertNode(taskId, AnalyzeNode.NODE_KEY, NodeType.ANALYZE, List.of(), 0);
        store.markNodeSucceeded(analyzeId, analyzeJson(F1));
        store.insertNode(taskId, RewriteNode.nodeKey(F1), NodeType.REWRITE, List.of(analyzeId), 0);
        store.insertNode(taskId, RewriteNode.nodeKey(F2), NodeType.REWRITE, List.of(analyzeId), 0);
        store.insertNode(taskId, VerifyNode.NODE_KEY, NodeType.VERIFY,
                List.of(store.findNode(taskId, RewriteNode.nodeKey(F1), 0).orElseThrow().id(),
                        store.findNode(taskId, RewriteNode.nodeKey(F2), 0).orElseThrow().id()), 0);

        // F2 第一轮改写失败（模型产出非法），第二轮才成功并迁移；F1 始终干净。
        StubExecutor rewrite = new StubExecutor(NodeType.REWRITE, ctx -> {
            String file = keySuffix(ctx.node().nodeKey());
            if (file.endsWith("F2.java") && ctx.attempt() == 0) {
                return NodeOutcome.fail("产出无法通过护栏：不是合法 Java 源文件");
            }
            String content = file.endsWith("F2.java") ? F2_WITH_JAKARTA : F1_SOURCE;
            writeWorkspace(ctx.workspace(), file, content);
            return NodeOutcome.ok(new RewriteResult(file, content, "@@", "迁移", "stub", ctx.attempt()));
        });
        StubExecutor verify = new StubExecutor(NodeType.VERIFY, ctx -> {
            String f2 = readWorkspace(ctx.workspace(), F2);
            if (f2 != null && f2.contains("javax")) {
                return NodeOutcome.fail("编译失败: F2.java 仍含 javax.persistence");
            }
            return NodeOutcome.ok(greenVerify());
        });

        scheduler(store, rewrite, verify).runTask(taskId);

        assertEquals(TaskStatus.SUCCEEDED, store.findTask(taskId).orElseThrow().status());
        assertEquals(List.of(0), attemptsOfFile(store, taskId, RewriteNode.nodeKey(F1)), "F1 不应被牵连重跑");
        assertEquals(List.of(0, 1), attemptsOfFile(store, taskId, RewriteNode.nodeKey(F2)), "F2 应被重跑一轮");
        assertEquals(NodeStatus.SKIPPED, store.statusOf(taskId, VerifyNode.NODE_KEY, 0),
                "首轮整仓 VERIFY 在 F2 改写失败后被跳过，不会浪费一次沙箱构建");
        assertEquals(NodeStatus.SUCCEEDED, store.statusOf(taskId, VerifyNode.NODE_KEY, 1),
                "重跑 F2 后整仓 VERIFY 应通过");
    }

    // ==================================================================
    // 3. 修复#1：REWRITE 失败把整仓 VERIFY 的 attempt 胀穿后，VERIFY 失败仍能精准重跑
    //    仍含旧 import 的文件（不被次数上限截断）—— 否则博客工程这种「很多文件首轮改写失败」
    //    的任务会在首次整仓 VERIFY 一失败就被 maxRewriteAttempts 截断、精准重跑根本不触发。
    // ==================================================================

    @Test
    @DisplayName("修复#1：3 个文件各首轮改写失败（VERIFY attempt 被胀到 3）后，VERIFY 失败仍能精准重跑漏改的 F3")
    void verifyRetryNotBlockedByRewriteFailures() throws IOException {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = store.createTask(projectRoot.toString(), F1, 21, null);

        long analyzeId = store.insertNode(taskId, AnalyzeNode.NODE_KEY, NodeType.ANALYZE, List.of(), 0);
        store.markNodeSucceeded(analyzeId, analyzeJson(F1));
        store.insertNode(taskId, RewriteNode.nodeKey(F1), NodeType.REWRITE, List.of(analyzeId), 0);
        store.insertNode(taskId, RewriteNode.nodeKey(F2), NodeType.REWRITE, List.of(analyzeId), 0);
        store.insertNode(taskId, RewriteNode.nodeKey(F3), NodeType.REWRITE, List.of(analyzeId), 0);
        store.insertNode(taskId, VerifyNode.NODE_KEY, NodeType.VERIFY,
                List.of(store.findNode(taskId, RewriteNode.nodeKey(F1), 0).orElseThrow().id(),
                        store.findNode(taskId, RewriteNode.nodeKey(F2), 0).orElseThrow().id(),
                        store.findNode(taskId, RewriteNode.nodeKey(F3), 0).orElseThrow().id()), 0);

        // 三个文件各「首轮改写失败一次」—— 每次 REWRITE 失败都会让 reissueBatchVerify 把整仓 VERIFY
        // 的 attempt 自增一次，把真正要跑的那条 VERIFY 的 attempt 胀到 3。随后三个文件都在 attempt=1
        // 成功，但 F3 在 attempt=1 时「漏改」（仍 javax）—— 模拟模型漏改 import 的真实失误。
        StubExecutor rewrite = new StubExecutor(NodeType.REWRITE, ctx -> {
            String file = keySuffix(ctx.node().nodeKey());
            int a = ctx.attempt();
            if (a == 0) {
                return NodeOutcome.fail("产出无法通过护栏：不是合法 Java 源文件");
            }
            String content;
            if (file.endsWith("F3.java") && a == 1) {
                content = F3_WITH_JAVAX; // 漏改，仍含 javax.annotation.Resource
            } else if (file.endsWith("F3.java")) {
                content = F3_WITH_JAKARTA;
            } else if (file.endsWith("F2.java")) {
                content = F2_WITH_JAKARTA;
            } else {
                content = F1_SOURCE;
            }
            writeWorkspace(ctx.workspace(), file, content);
            return NodeOutcome.ok(new RewriteResult(file, content, "@@", "迁移", "stub", a));
        });
        StubExecutor verify = new StubExecutor(NodeType.VERIFY, ctx -> {
            String f3 = readWorkspace(ctx.workspace(), F3);
            if (f3 != null && f3.contains("javax")) {
                return NodeOutcome.fail("编译失败: F3.java 仍含 javax.annotation，整仓编译不通过");
            }
            return NodeOutcome.ok(greenVerify());
        });

        scheduler(store, rewrite, verify).runTask(taskId);

        assertEquals(TaskStatus.SUCCEEDED, store.findTask(taskId).orElseThrow().status(),
                "REWRITE 失败把 VERIFY attempt 胀穿后，VERIFY 失败仍应触发精准重跑并收敛");

        // 关键：F3 被精准重跑（attempt 0 失败 → 1 漏改 → 2 干净），而非被次数上限卡死
        assertEquals(List.of(0, 1, 2), attemptsOfFile(store, taskId, RewriteNode.nodeKey(F3)),
                "仍含旧 import 的 F3 应被精准重跑两轮（首轮失败 + 漏改重跑）");
        // 干净文件 F1/F2 不应被牵连重跑（只有 attempt 0 失败 → 1 成功）
        assertEquals(List.of(0, 1), attemptsOfFile(store, taskId, RewriteNode.nodeKey(F1)));
        assertEquals(List.of(0, 1), attemptsOfFile(store, taskId, RewriteNode.nodeKey(F2)));

        // 收敛后 F3 真的被迁移成 jakarta
        assertTrue(readWorkspace(workspaceRoot.resolve("task-" + taskId), F3).contains("jakarta.annotation"),
                "收敛后 F3 应已迁移为 jakarta");

        // 回退轮次指标不应被「重铺产生的 SKIPPED VERIFY」胀穿 —— 真正跑过的 VERIFY 轮数应为 2
        // （一次失败 + 一次成功），而不是被 reissueBatchVerify 的 attempt 自增顶到很大
        TaskMetrics metrics = JsonCodec.defaultMapper()
                .readValue(store.metricsOf(taskId), TaskMetrics.class);
        assertEquals(2, metrics.verifyAttempts(),
                "verifyAttempts 应为真正跑过的 VERIFY 轮数，而非被 SKIPPED 节点胀穿");
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    /** 供断言读取：某个节点键的全部尝试轮次（升序）。按节点键区分单文件，而非按类型，
     *  否则多文件场景下同一类型不同文件会混在一起，分不清 F1/F2 各自被重跑了几轮。 */
    private static List<Integer> attemptsOfFile(InMemoryTaskStore store, long taskId, String nodeKey) {
        return store.findNodes(taskId).stream()
                .filter(node -> node.nodeKey().equals(nodeKey))
                .map(DagNode::attempt)
                .distinct()
                .sorted()
                .toList();
    }

    private DagScheduler scheduler(InMemoryTaskStore store, NodeExecutor... executors) {
        CoreProperties properties = CoreProperties.withoutGateTimeout(
                2, List.of("test"), workspaceRoot.toString(), false, false, false,
                true, Duration.ofHours(24));
        return new DagScheduler(store, properties, new JsonCodec(),
                List.of(executors), publishers(ProgressPublisher.NOOP));
    }

    private static ObjectProvider<ProgressPublisher> publishers(ProgressPublisher publisher) {
        return new ObjectProvider<>() {
            @Override
            public ProgressPublisher getObject() {
                return publisher;
            }

            @Override
            public ProgressPublisher getObject(Object... args) {
                return publisher;
            }

            @Override
            public ProgressPublisher getIfAvailable() {
                return publisher;
            }

            @Override
            public ProgressPublisher getIfUnique() {
                return publisher;
            }
        };
    }

    private static String keySuffix(String nodeKey) {
        if (nodeKey == null) {
            return null;
        }
        int colon = nodeKey.indexOf(':');
        return (colon < 0 || colon == nodeKey.length() - 1) ? null : nodeKey.substring(colon + 1);
    }

    private static void writeWorkspace(Path workspace, String relative, String content) {
        try {
            Path target = workspace.resolve(relative).normalize();
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String readWorkspace(Path workspace, String relative) {
        try {
            Path target = workspace.resolve(relative).normalize();
            if (!Files.isRegularFile(target)) {
                return null;
            }
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static String analyzeJson(String entry) {
        return """
                {"filePath":"%s","packageName":"com.example","primaryType":"X","symbols":[],"source":"","summary":""}
                """.formatted(entry);
    }

    private static VerifyResult greenVerify() {
        return new VerifyResult(true, 0, 4, 4, 0, 0, 0.62d, "", 1500L, false);
    }

    /** 可编程节点执行器：记录每次调用，按给定行为返回结果。 */
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
