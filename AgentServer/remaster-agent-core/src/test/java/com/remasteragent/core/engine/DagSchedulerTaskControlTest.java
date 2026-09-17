package com.remasteragent.core.engine;

import com.remasteragent.common.agent.AnalyzeResult;
import com.remasteragent.common.agent.RewriteResult;
import com.remasteragent.common.agent.VerifyResult;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务控制（阶段 3 收尾）的确定性单测 —— 协作式取消与「按任务」重置残留节点。
 *
 * <h2>为什么这两件事必须一起测</h2>
 * <p>它们共同定义了「多 Worker 并存时，谁的活归谁」这条边界：
 * <ul>
 *   <li><b>按任务重置</b>保证进程 B 不会把进程 A 正在跑的节点清成 PENDING（否则会被跑两遍）；</li>
 *   <li><b>协作式取消</b>保证停止只发生在<b>节点边界</b>（工作目录由 checkpoint 重放生成，
 *       不存在「杀到一半」的半截状态）。</li>
 * </ul>
 * 两者写错的后果都极其安静：前者让同一个节点被两个进程同时执行（写同一份沙箱目录、
 * 互相覆盖且不报错），后者留下一个半截的工作目录。都不会有任何报错。
 */
class DagSchedulerTaskControlTest {

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
    // 1. 协作式取消
    // ==================================================================

    @Test
    @DisplayName("开局前已被请求取消：一个节点都不铺、工作目录都不复制，直接落 CANCELLED")
    void cancelBeforeStartShortCircuits() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = newTask(store);
        store.requestCancel(taskId);

        StubExecutor analyze = okAnalyze();
        scheduler(store, analyze, okRewrite(), okVerify()).runTask(taskId);

        assertEquals(TaskStatus.CANCELLED, store.findTask(taskId).orElseThrow().status());
        assertEquals(0, analyze.callCount(), "已取消的任务不该跑任何节点");
        assertTrue(store.findNodes(taskId).isEmpty(),
                "连 DAG 都不该铺开 —— 铺图与复制工程在大工程上要好几秒，纯属白干");
    }

    @Test
    @DisplayName("执行中被请求取消：停在【节点边界】，当前节点跑完、后续节点不再跑")
    void cancelStopsAtNodeBoundary() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = newTask(store);

        // 模拟「人在 ANALYZE 跑的过程中点了取消」：节点内部无法安全中断，
        // 所以取消只能等它结束、回到调度循环开头时才生效
        StubExecutor analyze = new StubExecutor(NodeType.ANALYZE, ctx -> {
            store.requestCancel(taskId);
            return NodeOutcome.ok(analyzeResult());
        });
        StubExecutor rewrite = okRewrite();
        StubExecutor verify = okVerify();

        scheduler(store, analyze, rewrite, verify).runTask(taskId);

        assertEquals(TaskStatus.CANCELLED, store.findTask(taskId).orElseThrow().status());
        assertEquals(1, analyze.callCount(), "正在跑的节点要跑完 —— 硬中断会留下半截状态");
        assertEquals(0, rewrite.callCount(), "取消生效后不该再往下走");
        assertEquals(0, verify.callCount());
        assertEquals(NodeStatus.PENDING, store.statusOf(taskId, VerifyNode.NODE_KEY, 0),
                "没跑的节点老实停在 PENDING：它是断点，重跑时从这里续上");
    }

    @Test
    @DisplayName("取消【不写指标】：中途算出的通过率只反映半程，写进去会污染报表")
    void cancelledTaskDoesNotWriteMetrics() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = newTask(store);
        store.requestCancel(taskId);

        scheduler(store, okAnalyze(), okRewrite(), okVerify()).runTask(taskId);

        assertEquals(TaskStatus.CANCELLED, store.findTask(taskId).orElseThrow().status());
        assertNull(store.metricsOf(taskId),
                "报表按 metrics 聚合，把半程结果混进去等于用一个看起来正常的数字污染统计");
    }

    @Test
    @DisplayName("取消标志在任务开始前就被清掉（重跑场景）：正常跑完，不该被自己停掉")
    void clearedCancelFlagLetsTaskRun() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = newTask(store);
        store.requestCancel(taskId);
        store.clearCancelRequest(taskId);

        scheduler(store, okAnalyze(), okRewrite(), okVerify()).runTask(taskId);

        assertEquals(TaskStatus.SUCCEEDED, store.findTask(taskId).orElseThrow().status(),
                "重跑必须先清标志 —— 不清的话 Worker 取到任务的第一轮就会看到『已请求取消』并立刻停下");
    }

    // ==================================================================
    // 2. 按任务重置残留 RUNNING 节点
    // ==================================================================

    @Test
    @DisplayName("按任务重置：只清本任务残留的 RUNNING，绝不动别的任务正在跑的节点")
    void staleResetIsScopedToTheTask() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskA = newTask(store);
        long taskB = newTask(store);

        // A 的节点是「上一次进程被杀留下的残骸」，B 的节点是「另一个 Worker 此刻正在跑的活」
        long staleNode = store.insertNode(taskA, AnalyzeNode.NODE_KEY, NodeType.ANALYZE, List.of(), 0);
        store.markNodeRunning(staleNode);
        long liveNode = store.insertNode(taskB, AnalyzeNode.NODE_KEY, NodeType.ANALYZE, List.of(), 0);
        store.markNodeRunning(liveNode);

        StubExecutor analyze = okAnalyze();
        scheduler(store, analyze, okRewrite(), okVerify()).runTask(taskA);

        // A 的残骸被重置成 PENDING 之后才可能被 findRunnable 选中、真正跑起来 ——
        // 所以「它变成了 SUCCEEDED」就是「重置确实发生了」的证据（不重置的话它会一直是 RUNNING）
        assertEquals(1, analyze.callCount(), "残骸被重置后必须真的重跑一次");
        assertEquals(NodeStatus.SUCCEEDED, store.statusOf(taskA, AnalyzeNode.NODE_KEY, 0));
        assertEquals(NodeStatus.RUNNING, store.statusOf(taskB, AnalyzeNode.NODE_KEY, 0),
                "别的任务正在跑的节点绝不能被重置：那会让同一个节点被两个进程同时执行，"
                        + "而它们写的是同一份沙箱目录，互相覆盖且不报错");
    }

    @Test
    @DisplayName("残留节点被重置后能续跑到完成（功能不能因为『按任务』而丢）")
    void staleResetStillLetsWorkResume() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = newTask(store);

        // 真实的重启现场：整个拓扑已经铺开，ANALYZE 正在跑时进程被杀
        long analyzeId = store.insertNode(taskId, AnalyzeNode.NODE_KEY, NodeType.ANALYZE,
                List.of(), 0);
        store.markNodeRunning(analyzeId);
        long rewriteId = store.insertNode(taskId, RewriteNode.NODE_KEY, NodeType.REWRITE,
                List.of(analyzeId), 0);
        store.insertNode(taskId, VerifyNode.NODE_KEY, NodeType.VERIFY, List.of(rewriteId), 0);

        StubExecutor analyze = okAnalyze();
        StubExecutor rewrite = okRewrite();
        StubExecutor verify = okVerify();
        scheduler(store, analyze, rewrite, verify).runTask(taskId);

        assertEquals(1, analyze.callCount(), "被重置的节点应该真的重跑一次");
        assertEquals(1, rewrite.callCount(), "重置之后下游节点也要能被调度起来");
        assertEquals(1, verify.callCount());
        assertEquals(TaskStatus.SUCCEEDED, store.findTask(taskId).orElseThrow().status(),
                "断点续跑要能走到完成 —— 重置是为了让它能动，不是为了好看");
    }

    // ==================================================================
    // 3. 门禁挂起时不该被取消逻辑误伤
    // ==================================================================

    @Test
    @DisplayName("门禁挂起优先于取消检查：挂起中的任务状态是 WAITING_HUMAN，而不是被顺手判成取消")
    void gateSuspensionIsNotConfusedWithCancellation() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = newTask(store);

        scheduler(store, true, okAnalyze(), okRewrite(), new GateNode(store), okVerify())
                .runTask(taskId);

        assertEquals(TaskStatus.WAITING_HUMAN, store.findTask(taskId).orElseThrow().status());
        assertFalse(store.isCancelRequested(taskId), "挂起不是取消：不置标志位");
        assertTrue(store.findOpenGate(taskId).isPresent());
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    private long newTask(InMemoryTaskStore store) {
        return store.createTask(projectRoot.toString(), ENTRY, 21, null);
    }

    private DagScheduler scheduler(InMemoryTaskStore store, NodeExecutor... executors) {
        return scheduler(store, false, executors);
    }

    /** {@code requireRewriteApproval=true} 时走 ANALYZE→REWRITE→GATE→VERIFY 的退化拓扑。 */
    private DagScheduler scheduler(InMemoryTaskStore store, boolean gateEnabled,
                                   NodeExecutor... executors) {
        CoreProperties properties = CoreProperties.withoutGateTimeout(
                2, List.of("test"), workspaceRoot.toString(), false,
                false, gateEnabled, true, java.time.Duration.ofHours(24));
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
        return new StubExecutor(NodeType.ANALYZE, ctx -> NodeOutcome.ok(analyzeResult()));
    }

    private static AnalyzeResult analyzeResult() {
        return new AnalyzeResult(ENTRY, "com.example", "Demo", List.of("Demo#hi"),
                ORIGINAL_SOURCE, "单类、单方法");
    }

    private static StubExecutor okRewrite() {
        return new StubExecutor(NodeType.REWRITE, ctx -> NodeOutcome.ok(
                new RewriteResult(ENTRY, REWRITTEN_SOURCE, "@@ -1,5 +1,9 @@",
                        "补充 shout() 方法", "stub-model", ctx.attempt())));
    }

    private static StubExecutor okVerify() {
        return new StubExecutor(NodeType.VERIFY, ctx -> NodeOutcome.ok(greenVerify()));
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
