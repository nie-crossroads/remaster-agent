package com.remasteragent.core.queue;

import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.NodeStatus;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.core.codec.JsonCodec;
import com.remasteragent.core.config.CoreProperties;
import com.remasteragent.core.engine.DagScheduler;
import com.remasteragent.core.gate.GateTimeoutSweeper;
import com.remasteragent.core.progress.ProgressPublisher;
import com.remasteragent.core.store.InMemoryTaskStore;
import com.remasteragent.core.workspace.WorkspaceCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务消费者的单测 —— 全部同步执行，没有 sleep、没有线程竞态。
 *
 * <p>消费逻辑里最要紧的一条判断是<b>「什么时候才该确认（ACK）消息」</b>。
 * 确认早了，任务会在半路被卡死却无人知晓；确认晚了，任务被重复执行、重复烧钱。
 * 这个判断完全由代码而非模型决定，所以可以用桩件把它钉死。
 *
 * <p>四个关键场景：
 * <ol>
 *   <li>任务已到终态 → 不重跑，但必须确认（否则消息会被反复接管）</li>
 *   <li>正常任务 → 执行并确认</li>
 *   <li>任务不存在 → 丢弃并确认（毒丸消息不能堵住队列）</li>
 *   <li>执行抛异常且任务<b>没有</b>落到终态 → 不确认，留给后续接管重跑</li>
 * </ol>
 *
 * <p>另覆盖一条接线：一轮消费会顺带触发过期沙箱回收（判据本身在 {@code WorkspaceCleanerTest}）。
 */
class TaskConsumerTest {

    private InMemoryTaskStore store;
    private InMemoryTaskQueue queue;
    private final List<Long> runInvocations = new ArrayList<>();

    /** 每次 runTask 被调用时收到的 traceparent，由桩件记录。 */
    private final List<String> runTraceparents = new ArrayList<>();

    /** 每次 runTask 被调用时执行的行为，由各用例替换。 */
    private Consumer<Long> onRun = taskId -> {
    };

    @BeforeEach
    void setUp() {
        store = new InMemoryTaskStore();
        queue = new InMemoryTaskQueue();
        runInvocations.clear();
        runTraceparents.clear();
        onRun = taskId -> {
        };
    }

    @Test
    @DisplayName("队列消息里的 traceparent 必须原样交给调度器 —— 跨进程链路全靠它")
    void traceparentFromMessageReachesScheduler() {
        long taskId = store.createTask("E:/demo", "src/Demo.java", 21, null);
        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        queue.enqueue(taskId, traceparent);

        consumer().processOnce();

        assertEquals(List.of(traceparent), runTraceparents,
                "丢了它，Worker 侧会自起一条孤立 trace，API 那一段在链路里直接消失（且不报错）");
    }

    @Test
    @DisplayName("没有 traceparent 时传 null —— 自起一条 trace，不影响任务执行")
    void missingTraceparentIsPassedThroughAsNull() {
        long taskId = store.createTask("E:/demo", "src/Demo.java", 21, null);
        queue.enqueue(taskId);

        consumer().processOnce();

        assertEquals(1, runTraceparents.size());
        assertNull(runTraceparents.get(0), "追踪断了不该让任务跑不动");
    }

    @Test
    @DisplayName("任务已是终态：不重跑，但仍要确认消息")
    void terminalTaskIsSkippedButAcknowledged() {
        long taskId = store.createTask("E:/demo", "src/Demo.java", 21, null);
        store.updateTaskStatus(taskId, TaskStatus.SUCCEEDED, null);
        queue.enqueue(taskId);

        consumer().processOnce();

        assertTrue(runInvocations.isEmpty(), "已成功的任务不应被重跑");
        assertEquals(1, queue.ackedHandles().size(),
                "必须确认：不确认的话这条消息会被反复接管，变成无限循环");
        assertTrue(queue.isEmpty());
    }

    @Test
    @DisplayName("正常任务：执行并确认")
    void pendingTaskRunsAndIsAcknowledged() {
        long taskId = store.createTask("E:/demo", "src/Demo.java", 21, null);
        queue.enqueue(taskId);

        consumer().processOnce();

        assertEquals(List.of(taskId), runInvocations);
        assertEquals(1, queue.ackedHandles().size());
    }

    @Test
    @DisplayName("任务不存在：丢弃并确认（毒丸消息不能堵住队列）")
    void unknownTaskIsDroppedAndAcknowledged() {
        queue.enqueue(999L);

        consumer().processOnce();

        assertTrue(runInvocations.isEmpty());
        assertEquals(1, queue.ackedHandles().size(),
                "指向不存在任务的消息如果留着，每次读取都会再次失败");
    }

    @Test
    @DisplayName("执行抛异常但任务已落终态：确认 —— 重试也没意义")
    void failureWithTerminalStatusIsAcknowledged() {
        long taskId = store.createTask("E:/demo", "src/Demo.java", 21, null);
        onRun = id -> {
            // 模拟调度器：失败时先把任务标成 FAILED，然后把异常抛上来
            store.updateTaskStatus(id, TaskStatus.FAILED, "重写尝试已用尽");
            throw new IllegalStateException("任务执行失败");
        };
        queue.enqueue(taskId);

        consumer().processOnce();

        assertEquals(1, queue.ackedHandles().size());
    }

    @Test
    @DisplayName("执行中途出错且任务未落终态：不确认，等待接管重跑")
    void failureWithoutTerminalStatusIsNotAcknowledged() {
        long taskId = store.createTask("E:/demo", "src/Demo.java", 21, null);
        onRun = id -> {
            // 模拟临时故障：数据库抖动、Redis 掉线 —— 任务停在 RUNNING
            throw new IllegalStateException("写 checkpoint 时连接断开");
        };
        queue.enqueue(taskId);

        consumer().processOnce();

        assertTrue(queue.ackedHandles().isEmpty(),
                "任务没到终态就确认，等于承认它永远卡住 —— 必须留着让 checkpoint 续跑");
        assertEquals(1, runInvocations.size(), "本轮确实尝试执行过");
    }

    @Test
    @DisplayName("接管『上一个 Worker 被杀』的遗留消息：同样会被执行")
    void reclaimedMessagesAreProcessed() {
        long taskId = store.createTask("E:/demo", "src/Demo.java", 21, null);
        TaskQueue.QueueMessage abandoned = queue.addReclaimable(taskId);
        consumer().processOnce();

        assertEquals(List.of(taskId), runInvocations);
        assertEquals(List.of(abandoned.handle()), queue.ackedHandles(),
                "接管到的消息同样必须确认，否则它会在 PEL 里再挂一轮");
    }

    @Test
    @DisplayName("一轮里遗留消息先于新消息被处理（顺序影响任务的推进次序）")
    void reclaimedMessagesAreHandledBeforeFreshOnes() {
        long oldTask = store.createTask("E:/demo", "Old.java", 21, null);
        long newTask = store.createTask("E:/demo", "New.java", 21, null);
        queue.addReclaimable(oldTask);
        queue.enqueue(newTask);

        consumer().processOnce();

        assertEquals(List.of(oldTask, newTask), runInvocations);
    }

    @Test
    @DisplayName("启动只初始化队列 —— 残留 RUNNING 节点不再在启动时被全局重置")
    void startOnlyInitializesQueue() {
        long taskId = store.createTask("E:/demo", "src/Demo.java", 21, null);
        long nodeId = store.insertNode(taskId, "rewrite", NodeType.REWRITE, List.of(), 0);
        store.markNodeRunning(nodeId);

        TaskConsumer consumer = consumer();
        consumer.start();
        try {
            assertTrue(queue.isInitialized(), "启动时必须建好消费组，否则消息会读不到");
            DagNode node = store.findNode(taskId, "rewrite", 0).orElseThrow();
            assertEquals(NodeStatus.RUNNING, node.status(),
                    "启动时的重置必须是【不做】的：全局扫全表会在多 Worker 并存时"
                            + "把别的 Worker 正在跑的节点一起清掉。重置已改到 DagScheduler.runTask "
                            + "入口、并且按任务做（见 DagSchedulerTest 对应用例）");
        } finally {
            consumer.close();
        }
    }

    @Test
    @DisplayName("重复 start 不会启动第二个消费线程")
    void startIsIdempotent() {
        TaskConsumer consumer = consumer();
        consumer.start();
        try {
            consumer.start();
            assertEquals(1, queue.initializeCount(), "重复启动不应重新初始化队列");
        } finally {
            consumer.close();
        }
    }

    @Test
    @DisplayName("空队列：一轮处理 0 条，不抛异常")
    void emptyQueueIsHarmless() {
        assertEquals(0, consumer().processOnce());
        assertFalse(queue.isInitialized(), "processOnce 不负责初始化，那是 start 的职责");
    }

    @Test
    @DisplayName("一轮消费顺带回收过期沙箱：终态且超期的 task-<id> 目录被永久删除")
    void expiredSandboxIsReclaimedOnProcessOnce(@TempDir Path sandboxRoot) throws IOException {
        long taskId = store.createTask("E:/demo", "src/Demo.java", 21, null);
        store.updateTaskStatus(taskId, TaskStatus.SUCCEEDED, null);
        Path sandbox = Files.createDirectories(sandboxRoot.resolve("task-" + taskId));
        Files.writeString(sandbox.resolve("pom.xml"), "<project/>");

        // 时钟拨到保留期之后：cutoff 落在任务终态时刻（= 刚刚）之后，这份沙箱已过期
        WorkspaceCleaner expired = new WorkspaceCleaner(sandboxRoot, Duration.ofHours(24), true,
                Duration.ofMinutes(30), store, Clock.offset(Clock.systemUTC(), Duration.ofHours(25)));

        new TaskConsumer(queue, store, scheduler(), queueProperties(), expired, gateSweeperDisabled())
                .processOnce();

        assertFalse(Files.exists(sandbox), "过期沙箱应被永久删除（不进回收站）");
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    private TaskConsumer consumer() {
        return new TaskConsumer(queue, store, scheduler(), queueProperties(),
                cleanupDisabled(), gateSweeperDisabled());
    }

    /**
     * 默认装配里的回收器是<b>关掉的</b>：这些用例断言的是「执行与确认」那段逻辑，
     * 不该顺带在磁盘上删东西。回收自身的判据由 {@code WorkspaceCleanerTest} 覆盖，
     * 「消费一轮会触发回收」这条接线由 {@link #expiredSandboxIsReclaimedOnProcessOnce} 覆盖。
     */
    private WorkspaceCleaner cleanupDisabled() {
        return new WorkspaceCleaner(Path.of(".unused-workspaces"), Duration.ofHours(24), false,
                Duration.ofMinutes(30), store, Clock.systemUTC());
    }

    /**
     * 门禁超时器同样是关掉的（{@code timeout = 0} 即「永不超时」）：
     * 它默认就不该生效，判据由 {@code GateTimeoutSweeperTest} 覆盖。
     */
    private GateTimeoutSweeper gateSweeperDisabled() {
        return new GateTimeoutSweeper(Duration.ZERO, Duration.ofMinutes(5), store,
                ProgressPublisher.NOOP, Clock.systemUTC());
    }

    /** 用桩件替掉真正的调度器：只记录被调用的任务 id，行为由用例注入。 */
    private DagScheduler scheduler() {
        // 后两个开关（requirePlanApproval / requireRewriteApproval）=false：本用例只验「消费 → 调调度器」
        // 这段，不涉及人工评审与门禁；沙箱回收的开关与保留期取默认（开启 / 24h，本处用不到）
        CoreProperties coreProperties = CoreProperties.withoutGateTimeout(2, List.of("test"), ".unused",
                false, false, false, true, Duration.ofHours(24));
        return new DagScheduler(store, coreProperties, new JsonCodec(), List.of(),
                publishers(ProgressPublisher.NOOP)) {
            /**
             * 覆写的是<b>两参</b>版本 —— 消费循环走的就是它（traceparent 是这次执行属于哪条链路的凭据）。
             * 只覆写单参版会静默地调用到真实实现：任务不跑、断言却只看到「没被调用」，
             * 排查时很容易往「消息没投递」上想，而问题其实在这个桩件上。
             */
            @Override
            public void runTask(long taskId, String parentTraceparent) {
                runInvocations.add(taskId);
                runTraceparents.add(parentTraceparent);
                onRun.accept(taskId);
            }
        };
    }

    private static QueueProperties queueProperties() {
        return new QueueProperties("remaster:tasks", "test-group", "test-consumer", 1, 5_000, 60);
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
}
