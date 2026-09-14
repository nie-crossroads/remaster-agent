package com.remasteragent.core.queue;

import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.NodeStatus;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.core.codec.JsonCodec;
import com.remasteragent.core.config.CoreProperties;
import com.remasteragent.core.engine.DagScheduler;
import com.remasteragent.core.progress.ProgressPublisher;
import com.remasteragent.core.store.InMemoryTaskStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 */
class TaskConsumerTest {

    private InMemoryTaskStore store;
    private InMemoryTaskQueue queue;
    private final List<Long> runInvocations = new ArrayList<>();

    /** 每次 runTask 被调用时执行的行为，由各用例替换。 */
    private Consumer<Long> onRun = taskId -> {
    };

    @BeforeEach
    void setUp() {
        store = new InMemoryTaskStore();
        queue = new InMemoryTaskQueue();
        runInvocations.clear();
        onRun = taskId -> {
        };
    }

    @Test
    @DisplayName("任务已是终态：不重跑，但仍要确认消息")
    void terminalTaskIsSkippedButAcknowledged() {
        long taskId = store.createTask("E:/demo", "src/Demo.java", 21);
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
        long taskId = store.createTask("E:/demo", "src/Demo.java", 21);
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
        long taskId = store.createTask("E:/demo", "src/Demo.java", 21);
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
        long taskId = store.createTask("E:/demo", "src/Demo.java", 21);
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
        long taskId = store.createTask("E:/demo", "src/Demo.java", 21);
        TaskQueue.QueueMessage abandoned = queue.addReclaimable(taskId);
        consumer().processOnce();

        assertEquals(List.of(taskId), runInvocations);
        assertEquals(List.of(abandoned.handle()), queue.ackedHandles(),
                "接管到的消息同样必须确认，否则它会在 PEL 里再挂一轮");
    }

    @Test
    @DisplayName("一轮里遗留消息先于新消息被处理（顺序影响任务的推进次序）")
    void reclaimedMessagesAreHandledBeforeFreshOnes() {
        long oldTask = store.createTask("E:/demo", "Old.java", 21);
        long newTask = store.createTask("E:/demo", "New.java", 21);
        queue.addReclaimable(oldTask);
        queue.enqueue(newTask);

        consumer().processOnce();

        assertEquals(List.of(oldTask, newTask), runInvocations);
    }

    @Test
    @DisplayName("启动时会初始化队列并重置残留的 RUNNING 节点；关闭后线程退出")
    void startInitializesQueueAndResetsStaleNodes() {
        long taskId = store.createTask("E:/demo", "src/Demo.java", 21);
        long nodeId = store.insertNode(taskId, "rewrite", NodeType.REWRITE, List.of(), 0);
        store.markNodeRunning(nodeId);

        TaskConsumer consumer = consumer();
        consumer.start();
        try {
            assertTrue(queue.isInitialized(), "启动时必须建好消费组，否则消息会读不到");
            DagNode node = store.findNode(taskId, "rewrite", 0).orElseThrow();
            assertEquals(NodeStatus.PENDING, node.status(),
                    "上次进程被杀留下的 RUNNING 节点必须重置，否则永远等不到调度");
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

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    private TaskConsumer consumer() {
        return new TaskConsumer(queue, store, scheduler(), queueProperties());
    }

    /** 用桩件替掉真正的调度器：只记录被调用的任务 id，行为由用例注入。 */
    private DagScheduler scheduler() {
        // 第 5 参 requirePlanApproval=false：本用例只验「消费 → 调调度器」这段，不涉及人工评审
        CoreProperties coreProperties = new CoreProperties(2, List.of("test"), ".unused", false, false);
        return new DagScheduler(store, coreProperties, new JsonCodec(), List.of(),
                publishers(ProgressPublisher.NOOP)) {
            @Override
            public void runTask(long taskId) {
                runInvocations.add(taskId);
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
