package com.remasteragent.core.engine;

import com.remasteragent.common.agent.AnalyzeResult;
import com.remasteragent.common.agent.RewriteResult;
import com.remasteragent.common.agent.VerifyResult;
import com.remasteragent.common.domain.GateStatus;
import com.remasteragent.common.domain.HumanGate;
import com.remasteragent.common.domain.NodeStatus;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.core.codec.JsonCodec;
import com.remasteragent.core.config.CoreProperties;
import com.remasteragent.core.engine.node.AnalyzeNode;
import com.remasteragent.core.engine.node.GateNode;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通用 GATE 门禁（阶段 3「人在回路」）的确定性单测。
 *
 * <p>覆盖三件在「挂起—唤醒」里最容易写错的事：
 * <ol>
 *   <li>改写完成后 <b>确实</b>停在门禁上 —— VERIFY 一次都没跑（否则门禁形同虚设）；</li>
 *   <li>批准后能从 checkpoint 续跑至完成 —— 门禁节点转成功、下游 VERIFY 才跑，
 *       且不会重复落第二道门；</li>
 *   <li>改写失败时，同轮的门禁与 VERIFY 都被跳过（不留永远 PENDING 的悬挂节点）。</li>
 * </ol>
 *
 * <p>与 {@code DagSchedulerRollbackTest} 同款手法：桩件精确控制每步成败，
 * 内存 store 替代 PostgreSQL，临时目录替代真沙箱。跑 100 次结果一致。
 */
class DagSchedulerGateTest {

    private static final String ENTRY = "src/main/java/com/example/Demo.java";

    private static final String ORIGINAL_SOURCE = """
            package com.example;

            public class Demo {
                public String hi() {
                    return "hi";
                }
            }
            """;

    private static final String REWRITTEN_SOURCE = """
            package com.example;

            public class Demo {
                public String hi() {
                    return "hi";
                }

                public String shout() {
                    return hi().toUpperCase();
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
        projectRoot = tmp.resolve("legacy-demo");
        Path entry = projectRoot.resolve(ENTRY);
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, ORIGINAL_SOURCE, StandardCharsets.UTF_8);
    }

    // ==================================================================
    // 1. 改写后停在门禁上 —— VERIFY 一次都不跑
    // ==================================================================

    @Test
    @DisplayName("改写后挂起：任务停在 WAITING_HUMAN，门禁 PENDING，VERIFY 未执行")
    void suspendsAtGateBeforeVerify() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = newTask(store);

        StubExecutor verify = okVerify();
        DagScheduler scheduler = scheduler(store, okAnalyze(), okRewrite(), new GateNode(store), verify);
        scheduler.runTask(taskId);

        assertEquals(TaskStatus.WAITING_HUMAN, store.findTask(taskId).orElseThrow().status(),
                "门禁处应挂起，而不是继续往前跑");

        assertTrue(store.findOpenGate(taskId).isPresent(), "应落下一道等待中的门禁");
        assertEquals(NodeStatus.PENDING, store.statusOf(taskId, GateNode.NODE_KEY, 0),
                "门禁节点退回 PENDING：没跑完，只是动不了");

        assertEquals(0, verify.callCount(), "门禁未批，VERIFY 绝不该执行");
        // 拓扑：ANALYZE → REWRITE → GATE → VERIFY，共 4 个节点，没有派生任何重试
        assertEquals(4, store.findNodes(taskId).size());
        assertEquals(NodeStatus.SUCCEEDED, store.statusOf(taskId, RewriteNode.NODE_KEY, 0));
        assertEquals(NodeStatus.PENDING, store.statusOf(taskId, VerifyNode.NODE_KEY, 0),
                "VERIFY 在等门禁，应停在 PENDING");
    }

    // ==================================================================
    // 2. 批准后从 checkpoint 续跑至完成
    // ==================================================================

    @Test
    @DisplayName("批准后继续：门禁节点转成功、VERIFY 才跑，任务收尾 SUCCEEDED")
    void approvalResumesAndCompletes() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = newTask(store);
        StubExecutor verify = okVerify();
        DagScheduler scheduler = scheduler(store, okAnalyze(), okRewrite(), new GateNode(store), verify);

        scheduler.runTask(taskId);
        assertEquals(TaskStatus.WAITING_HUMAN, store.findTask(taskId).orElseThrow().status());

        // ---- 模拟 API 进程的 /gate/approve：落定门禁 + 门禁节点转成功 + 重新入队 ----
        HumanGate gate = store.findOpenGate(taskId).orElseThrow();
        assertEquals(1, store.decideGate(gate.id(), GateStatus.APPROVED, "alice", "看着没问题"));
        store.markNodeSucceeded(gate.nodeId(), null);
        store.updateTaskStatus(taskId, TaskStatus.PENDING, null);

        // Worker 取走这条重投的消息，从 checkpoint 续跑
        scheduler.runTask(taskId);

        assertEquals(TaskStatus.SUCCEEDED, store.findTask(taskId).orElseThrow().status());
        assertEquals(1, verify.callCount(), "批准后 VERIFY 恰好执行一次");
        assertEquals(NodeStatus.SUCCEEDED, store.statusOf(taskId, GateNode.NODE_KEY, 0));
        assertEquals(NodeStatus.SUCCEEDED, store.statusOf(taskId, VerifyNode.NODE_KEY, 0));
        assertEquals(1, store.findGates(taskId).size(), "续跑不得重复落门禁行");
    }

    // ==================================================================
    // 3. 改写失败 —— 同轮门禁与 VERIFY 一起被跳过
    // ==================================================================

    @Test
    @DisplayName("改写失败：同轮门禁与 VERIFY 均被跳过（不留悬挂节点），重试轮不再插门禁")
    void rewriteFailureSkipsGateAndVerify() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = newTask(store);

        AtomicInteger rewriteCalls = new AtomicInteger();
        StubExecutor rewrite = new StubExecutor(NodeType.REWRITE, ctx -> {
            if (rewriteCalls.incrementAndGet() == 1) {
                return NodeOutcome.fail("产出无法通过护栏：不是合法 Java 源文件");
            }
            return NodeOutcome.ok(rewriteOk(ctx.attempt()));
        });

        scheduler(store, okAnalyze(), rewrite, new GateNode(store), okVerify()).runTask(taskId);

        assertEquals(TaskStatus.SUCCEEDED, store.findTask(taskId).orElseThrow().status());

        // 关键断言：首轮 REWRITE 失败后，门禁与 VERIFY 都不能永远停在 PENDING
        assertEquals(NodeStatus.FAILED, store.statusOf(taskId, RewriteNode.NODE_KEY, 0));
        assertEquals(NodeStatus.SKIPPED, store.statusOf(taskId, GateNode.NODE_KEY, 0),
                "上游改写失败，同轮门禁应被跳过");
        assertEquals(NodeStatus.SKIPPED, store.statusOf(taskId, VerifyNode.NODE_KEY, 0));

        // 重试轮（attempt=1）不再插入门禁 —— 回退是自动纠错，不逐轮打断人
        assertFalse(store.hasNode(taskId, GateNode.NODE_KEY, 1), "重试轮不应再插门禁");
        assertEquals(NodeStatus.SUCCEEDED, store.statusOf(taskId, RewriteNode.NODE_KEY, 1));
        assertEquals(NodeStatus.SUCCEEDED, store.statusOf(taskId, VerifyNode.NODE_KEY, 1));
        assertEquals(0, store.findGates(taskId).size(), "首轮门禁从未创建（REWRITE 先失败了）");
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    private long newTask(InMemoryTaskStore store) {
        return store.createTask(projectRoot.toString(), ENTRY, 21);
    }

    /** 开启「改写后人工门禁」的调度器；不装 PLAN 执行器 → 走 ANALYZE→REWRITE→GATE→VERIFY 退化拓扑。 */
    private DagScheduler scheduler(InMemoryTaskStore store, NodeExecutor... executors) {
        CoreProperties properties = new CoreProperties(
                2, List.of("test"), workspaceRoot.toString(), false,
                // 关掉规划评审：本用例聚焦 GATE 门禁
                false,
                // 开启改写后人工门禁 —— 被测开关
                true,
                // 沙箱回收与本用例无关：保持默认（开启 + 24h）
                true,
                Duration.ofHours(24));
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

    private static StubExecutor okAnalyze() {
        return new StubExecutor(NodeType.ANALYZE, ctx -> NodeOutcome.ok(
                new AnalyzeResult(ENTRY, "com.example", "Demo", List.of("Demo#hi"),
                        ORIGINAL_SOURCE, "单类、单方法")));
    }

    private static StubExecutor okRewrite() {
        return new StubExecutor(NodeType.REWRITE,
                ctx -> NodeOutcome.ok(rewriteOk(ctx.attempt())));
    }

    private static StubExecutor okVerify() {
        return new StubExecutor(NodeType.VERIFY, ctx -> NodeOutcome.ok(greenVerify()));
    }

    private static RewriteResult rewriteOk(int attempt) {
        return new RewriteResult(ENTRY, REWRITTEN_SOURCE, "@@ -1,5 +1,9 @@",
                "补充 shout() 方法", "stub-model", attempt);
    }

    private static VerifyResult greenVerify() {
        return new VerifyResult(true, 0, 4, 4, 0, 0, 0.62d, "", 1500L, false);
    }

    /** 可编程节点执行器：记录每次调用，按给定行为返回结果。 */
    private static final class StubExecutor implements NodeExecutor {

        private final NodeType type;
        private final Function<NodeContext, NodeOutcome> behavior;
        private final List<NodeContext> invocations = new ArrayList<>();

        StubExecutor(NodeType type, Function<NodeContext, NodeOutcome> behavior) {
            this.type = type;
            this.behavior = behavior;
        }

        @Override
        public NodeType type() {
            return type;
        }

        @Override
        public NodeOutcome execute(NodeContext context) {
            invocations.add(context);
            return behavior.apply(context);
        }

        int callCount() {
            return invocations.size();
        }
    }
}
