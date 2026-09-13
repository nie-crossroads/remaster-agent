package com.remasteragent.core.engine;

import com.remasteragent.core.codec.JsonCodec;
import com.remasteragent.common.agent.AnalyzeResult;
import com.remasteragent.common.agent.RewriteResult;
import com.remasteragent.common.agent.VerifyResult;
import com.remasteragent.common.domain.NodeStatus;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.domain.TaskMetrics;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编排内核的确定性单测 —— 本项目的「可信度地基」。
 *
 * <h2>为什么这一段必须单测、而且必须不依赖 LLM</h2>
 * <p>回退重写是整个系统里最容易写错、也最贵的一段逻辑：写错了要么无限重试烧钱，
 * 要么该重试时不重试导致任务假失败。如果测试依赖真模型，那么它同时也就依赖了：
 * 网关可用性、模型随机性、当前余额、网络延迟 —— 任何一项波动都会让测试时红时绿。
 * 一个会自己变红的测试等于没有测试。
 *
 * <p>所以这里用三个桩件（{@link StubExecutor}）精确控制每一步的成败，
 * 用内存 store（{@link InMemoryTaskStore}）替代 PostgreSQL，用临时目录替代真沙箱。
 * 结论是稳定、可复现、毫秒级的：跑 100 次结果完全一致。
 *
 * <p>覆盖的五条路径：
 * <ol>
 *   <li>VERIFY 失败两次后成功 → 任务成功，恰好 3 轮改写</li>
 *   <li>VERIFY 一直失败 → 达到上限后任务失败，不再无限派生</li>
 *   <li>REWRITE 失败 → 本轮 VERIFY 被 SKIPPED，并派生下一轮</li>
 *   <li>ANALYZE 失败 → 不重试（前提不成立，重试没意义）</li>
 *   <li>断点续跑 → 已成功节点不重跑，且工作目录由 checkpoint 确定性重放</li>
 * </ol>
 */
class DagSchedulerRollbackTest {

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

    /** 与生产默认一致：最多 2 次重试 = 最多 3 轮（attempt 0/1/2）。 */
    private static final int MAX_REWRITE_ATTEMPTS = 2;

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
    // 1. VERIFY 失败两次后成功 —— 正常路径 + 回退
    // ==================================================================

    @Test
    @DisplayName("VERIFY 连续失败两次后成功：任务成功，恰好派生 3 轮改写，指标里记录回退")
    void verifyFailsTwiceThenSucceeds() throws IOException {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = newTask(store);

        AtomicInteger verifyCalls = new AtomicInteger();
        StubExecutor analyze = okAnalyze();
        StubExecutor rewrite = okRewrite();
        StubExecutor verify = new StubExecutor(NodeType.VERIFY, ctx -> {
            int call = verifyCalls.incrementAndGet();
            if (call <= 2) {
                return NodeOutcome.fail("编译失败: Demo.java:[7,17] 找不到符号 方法 shout()");
            }
            return NodeOutcome.ok(greenVerify());
        });

        scheduler(store, analyze, rewrite, verify).runTask(taskId);

        assertEquals(TaskStatus.SUCCEEDED, store.findTask(taskId).orElseThrow().status());

        // 三轮改写，attempt 依次 0 / 1 / 2
        assertEquals(List.of(0, 1, 2), store.attemptsOf(taskId, NodeType.REWRITE));
        assertEquals(List.of(0, 1, 2), store.attemptsOf(taskId, NodeType.VERIFY));

        // 审计痕迹：失败的轮次必须保留 FAILED，最后一轮 SUCCEEDED
        assertEquals(NodeStatus.FAILED, store.statusOf(taskId, VerifyNode.NODE_KEY, 0));
        assertEquals(NodeStatus.FAILED, store.statusOf(taskId, VerifyNode.NODE_KEY, 1));
        assertEquals(NodeStatus.SUCCEEDED, store.statusOf(taskId, VerifyNode.NODE_KEY, 2));

        // 节点数 = 1 ANALYZE + 3 REWRITE + 3 VERIFY
        assertEquals(7, store.findNodes(taskId).size());
        assertEquals(3, verify.callCount());
        assertEquals(2, verifyCalls.get() - 1);

        TaskMetrics metrics = metricsOf(store, taskId);
        assertEquals(1.0d, metrics.compilePassRate(), 1e-9);
        assertEquals(1.0d, metrics.testPassRate(), 1e-9);
        assertEquals(0.62d, metrics.coverage(), 1e-9);
        assertEquals(3, metrics.verifyAttempts());
        assertTrue(metrics.retried(), "发生过 3 轮改写，retried() 应为 true");

        // 重试反馈：第 1 轮改写应当拿到上一轮 VERIFY 的失败摘要，首轮不应有反馈
        assertNull(rewrite.feedbackOf(0), "首轮改写不应有失败反馈");
        assertNotNull(rewrite.feedbackOf(1), "第 1 轮改写应收到上一轮失败反馈");
        assertTrue(rewrite.feedbackOf(1).contains("找不到符号"),
                "反馈里应包含上一轮编译错误的关键行，实际为: " + rewrite.feedbackOf(1));
    }

    // ==================================================================
    // 2. VERIFY 一直失败 —— 上限护栏
    // ==================================================================

    @Test
    @DisplayName("VERIFY 始终失败：达到重试上限后任务判失败，不再无限派生节点")
    void verifyNeverRecoversStopsAtCap() throws IOException {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = newTask(store);

        StubExecutor verify = new StubExecutor(NodeType.VERIFY,
                ctx -> NodeOutcome.fail("编译失败: 找不到符号"));

        scheduler(store, okAnalyze(), okRewrite(), verify).runTask(taskId);

        assertEquals(TaskStatus.FAILED, store.findTask(taskId).orElseThrow().status());

        // 只有 0/1/2 三轮，第 3 轮不再派生 —— 这是成本护栏
        assertEquals(List.of(0, 1, 2), store.attemptsOf(taskId, NodeType.VERIFY));
        assertFalse(store.hasNode(taskId, VerifyNode.NODE_KEY, 3), "超出上限后不应再派生 VERIFY");
        assertFalse(store.hasNode(taskId, RewriteNode.NODE_KEY, 3), "超出上限后不应再派生 REWRITE");
        assertEquals(MAX_REWRITE_ATTEMPTS + 1, verify.callCount());

        // 失败任务也要留下量化指标，便于事后归因
        assertNotNull(store.metricsOf(taskId));
        TaskMetrics metrics = metricsOf(store, taskId);
        assertEquals(0.0d, metrics.compilePassRate(), 1e-9);
        assertTrue(metrics.retried());
    }

    // ==================================================================
    // 3. REWRITE 失败 —— 同轮 VERIFY 必须被 SKIPPED
    // ==================================================================

    @Test
    @DisplayName("REWRITE 产出非法：本轮 VERIFY 被标记 SKIPPED（不留悬挂节点），并派生下一轮")
    void rewriteFailureSkipsSameRoundVerify() throws IOException {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = newTask(store);

        AtomicInteger rewriteCalls = new AtomicInteger();
        StubExecutor rewrite = new StubExecutor(NodeType.REWRITE, ctx -> {
            if (rewriteCalls.incrementAndGet() == 1) {
                return NodeOutcome.fail("产出无法通过护栏：不是合法 Java 源文件");
            }
            return NodeOutcome.ok(rewriteOk(ctx.attempt()));
        });
        StubExecutor verify = new StubExecutor(NodeType.VERIFY, ctx -> NodeOutcome.ok(greenVerify()));

        scheduler(store, okAnalyze(), rewrite, verify).runTask(taskId);

        assertEquals(TaskStatus.SUCCEEDED, store.findTask(taskId).orElseThrow().status());

        // 关键断言：REWRITE(0) 失败后，VERIFY(0) 不能永远停在 PENDING
        assertEquals(NodeStatus.SKIPPED, store.statusOf(taskId, VerifyNode.NODE_KEY, 0));
        assertEquals(NodeStatus.FAILED, store.statusOf(taskId, RewriteNode.NODE_KEY, 0));
        assertEquals(NodeStatus.SUCCEEDED, store.statusOf(taskId, RewriteNode.NODE_KEY, 1));
        assertEquals(NodeStatus.SUCCEEDED, store.statusOf(taskId, VerifyNode.NODE_KEY, 1));

        // VERIFY 只跑了一轮（attempt=1）；attempt=0 那轮被跳过，没浪费一次沙箱构建
        assertEquals(1, verify.callCount());

        // 第二轮改写拿到的反馈来自失败的 REWRITE（因为 VERIFY 是被跳过的，不是失败）
        assertEquals("产出无法通过护栏：不是合法 Java 源文件", rewrite.feedbackOf(1));
    }

    // ==================================================================
    // 4. ANALYZE 失败 —— 不重试
    // ==================================================================

    @Test
    @DisplayName("ANALYZE 失败：不派生重试，任务立即判失败（任务前提不成立）")
    void analyzeFailureDoesNotRetry() throws IOException {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = newTask(store);

        StubExecutor analyze = new StubExecutor(NodeType.ANALYZE,
                ctx -> NodeOutcome.fail("无法解析源文件：语法错误"));
        StubExecutor rewrite = okRewrite();
        StubExecutor verify = new StubExecutor(NodeType.VERIFY, ctx -> NodeOutcome.ok(greenVerify()));

        scheduler(store, analyze, rewrite, verify).runTask(taskId);

        assertEquals(TaskStatus.FAILED, store.findTask(taskId).orElseThrow().status());
        assertEquals(1, analyze.callCount());
        assertEquals(0, rewrite.callCount(), "上游未成功，REWRITE 不应被执行");
        assertEquals(0, verify.callCount());

        // 只有初始三个节点，没有派生任何 attempt=1
        assertEquals(3, store.findNodes(taskId).size());
        assertFalse(store.hasNode(taskId, RewriteNode.NODE_KEY, 1));
        assertFalse(store.hasNode(taskId, VerifyNode.NODE_KEY, 1));
    }

    // ==================================================================
    // 5. 断点续跑 —— 不重跑已成功节点 + 由 checkpoint 重放工作目录
    // ==================================================================

    @Test
    @DisplayName("断点续跑：已成功的节点不重跑，工作目录从 checkpoint 确定性重放")
    void resumeFromCheckpointReplaysWorkspace() throws IOException {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = newTask(store);
        JsonCodec mapper = new JsonCodec();

        // 预置「进程被杀」前的 checkpoint：ANALYZE、REWRITE(0) 已成功，VERIFY(0) 尚在排队
        long analyzeId = store.insertNode(taskId, AnalyzeNode.NODE_KEY, NodeType.ANALYZE, List.of(), 0);
        store.markNodeSucceeded(analyzeId, mapper.write(
                new AnalyzeResult(ENTRY, "com.example", "Demo", List.of("Demo#hi"),
                        ORIGINAL_SOURCE, "单类、单方法")));

        long rewriteId = store.insertNode(taskId, RewriteNode.NODE_KEY, NodeType.REWRITE,
                List.of(analyzeId), 0);
        store.markNodeSucceeded(rewriteId, mapper.write(rewriteOk(0)));

        store.insertNode(taskId, VerifyNode.NODE_KEY, NodeType.VERIFY, List.of(rewriteId), 0);

        // 沙箱工作目录此刻不存在 —— 模拟「改写内容只活在 checkpoint 里」
        Path workspace = workspaceRoot.resolve("task-" + taskId);
        assertFalse(Files.exists(workspace), "前置条件：工作目录应尚未创建");

        StubExecutor analyze = okAnalyze();
        StubExecutor rewrite = okRewrite();
        StubExecutor verify = new StubExecutor(NodeType.VERIFY, ctx -> NodeOutcome.ok(greenVerify()));

        scheduler(store, analyze, rewrite, verify).runTask(taskId);

        assertEquals(TaskStatus.SUCCEEDED, store.findTask(taskId).orElseThrow().status());

        // 已成功节点零重跑 —— 这是「不重复烧 token」的保证
        assertEquals(0, analyze.callCount());
        assertEquals(0, rewrite.callCount());
        assertEquals(1, verify.callCount());

        // 工作目录被重放：改写产物真的写回了沙箱，且不是原始内容
        Path replayed = workspace.resolve(ENTRY);
        assertTrue(Files.exists(replayed), "checkpoint 重放后文件应存在");
        assertEquals(REWRITTEN_SOURCE, Files.readString(replayed, StandardCharsets.UTF_8));

        // 没有新派生节点：仍然是初始三个
        assertEquals(3, store.findNodes(taskId).size());
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    private long newTask(InMemoryTaskStore store) {
        return store.createTask(projectRoot.toString(), ENTRY, 21);
    }

    private DagScheduler scheduler(InMemoryTaskStore store, NodeExecutor... executors) {
        assertTrue(executors.length >= 3, "至少需要 ANALYZE / REWRITE / VERIFY 三个执行器");
        CoreProperties properties = new CoreProperties(
                MAX_REWRITE_ATTEMPTS, List.of("test"), workspaceRoot.toString(), false);
        return new DagScheduler(store, properties, new JsonCodec(),
                List.of(executors), publishers(ProgressPublisher.NOOP));
    }

    /**
     * 最小 {@link ObjectProvider} 桩件：直接返回给定发布器。
     * 生产的调度器用 ObjectProvider 是为了在缺少发布器 bean 时优雅降级成 NOOP，
     * 这里单测不需要那个语义，给个恒定返回即可。
     */
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

    private TaskMetrics metricsOf(InMemoryTaskStore store, long taskId) throws IOException {
        String json = store.metricsOf(taskId);
        assertNotNull(json, "任务收尾必须写入指标 JSON");
        // 用生产 mapper 反序列化：指标是 Worker 写、API 读的，两端必须是同一套配置
        return JsonCodec.defaultMapper().readValue(json, TaskMetrics.class);
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

    private static RewriteResult rewriteOk(int attempt) {
        return new RewriteResult(ENTRY, REWRITTEN_SOURCE, "@@ -1,5 +1,9 @@",
                "补充 shout() 方法", "stub-model", attempt);
    }

    private static VerifyResult greenVerify() {
        return new VerifyResult(true, 0, 4, 4, 0, 0, 0.62d, "", 1500L, false);
    }

    // ------------------------------------------------------------------
    // 桩件
    // ------------------------------------------------------------------

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

        /** 第 n 次调用收到的重试反馈。 */
        String feedbackOf(int invocationIndex) {
            return invocations.get(invocationIndex).retryFeedback();
        }
    }
}
