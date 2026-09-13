package com.remasteragent.core.queue;

import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.core.engine.DagScheduler;
import com.remasteragent.core.store.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务消费者 —— Worker 进程的主循环。
 *
 * <p>它本身不是 Spring Bean：由各应用自行决定是否启动它。
 * 这样「Worker 独立进程」与「开发时嵌进 API 进程」两种跑法共用同一份实现，
 * 而 core 不必为了一个开关去引 {@code spring-boot-autoconfigure}。
 *
 * <h2>循环结构</h2>
 * <pre>
 *   启动 → 建消费组 → 重置上次被杀的残留节点 → 循环 { 接管遗留消息 → 取新消息 → 执行 → 确认 }
 * </pre>
 *
 * <p><b>单线程、一次一个任务</b>。这不是偷懒：一个迁移任务本身就是几十秒到几分钟的
 * 顺序过程（分析 → 改写 → 编译 → 跑测试），并发跑多个任务只会让它们在同一个 Maven
 * 本地仓库上互相争抢。要吞吐就多开 Worker 实例 —— 消费组天然会分配消息，
 * 而任务之间本来就没有共享状态。把并发放在进程级而不是线程级，也让
 * 「进程被杀」这个故障模型的推理简单得多。
 */
public class TaskConsumer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TaskConsumer.class);

    /** 消费循环遇到非预期异常后的重试间隔。 */
    private static final long ERROR_RETRY_DELAY_MS = 5_000L;

    private final TaskQueue queue;
    private final TaskStore taskStore;
    private final DagScheduler scheduler;
    private final QueueProperties properties;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread loopThread;

    public TaskConsumer(TaskQueue queue, TaskStore taskStore, DagScheduler scheduler,
                        QueueProperties properties) {
        this.queue = queue;
        this.taskStore = taskStore;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    /**
     * 启动消费循环。可重复调用，重复调用是空操作。
     *
     * <h3>关于 {@code resetStaleRunningNodes()} 的一个已知边界</h3>
     * <p>启动时会把所有 {@code RUNNING} 的节点重置为 {@code PENDING} —— 那是上一次
     * Worker 被杀留下的残骸，不重置的话这些节点永远等不到调度（{@code findRunnable}
     * 只看 PENDING），任务会静默卡住。但它是<b>全局</b>的：
     * 如果两个 Worker 同时在跑，B 的启动会把 A 正在执行的节点重置掉，导致重复执行。
     *
     * <p>当前阶段（单 Worker / 开发期内嵌）不构成问题，而且代价被两层机制兜住了：
     * 重复投递会被「已成功节点跳过」消掉，工作目录由 checkpoint 重放而非保留现场。
     * 真要多 Worker 部署时，这里必须改成<b>按任务</b>重置（只重置当前要跑的那个任务），
     * 或引入租约/心跳来判定「哪个节点真的没人管了」。
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.debug("消费循环已在运行，忽略重复启动");
            return;
        }

        queue.initialize();
        int reset = taskStore.resetStaleRunningNodes();
        if (reset > 0) {
            log.warn("启动时重置了 {} 个残留的 RUNNING 节点（上一次进程被杀留下的）", reset);
        }

        Thread thread = new Thread(this::loop, "remaster-task-consumer");
        // 设为守护线程：主线程退出时不让消费循环拖住 JVM 关闭
        thread.setDaemon(true);
        this.loopThread = thread;
        thread.start();
        log.info("任务消费循环已启动");
    }

    private void loop() {
        while (running.get()) {
            try {
                if (processOnce() == 0) {
                    // poll() 是阻塞读，正常不会空转到这；真到了说明配置成了非阻塞，
                    // 稍等一拍避免把 CPU 打满
                    sleepQuietly(200L);
                }
            } catch (Exception e) {
                // 队列暂时不可用（Redis 抖动）不该让消费循环退出 —— 退出了就再也没人消费了。
                // 这里不单独捕获 InterruptedException：poll() 不会抛它，
                // 停机靠 running 标志在下一轮循环开头退出（见 close()）。
                log.error("消费循环异常，{} ms 后重试", ERROR_RETRY_DELAY_MS, e);
                sleepQuietly(ERROR_RETRY_DELAY_MS);
            }
        }
        log.info("任务消费循环已停止");
    }

    /**
     * 执行一轮消费：先接管遗留消息，再取新消息，逐条处理。
     *
     * <p>把「一轮」从「循环」里拆出来，是为了让这段真正有决策逻辑的代码
     * 能被<b>同步地</b>单测 —— 循环只是调度它，真正的判断（要不要跑、要不要确认）
     * 都在这里。若只能通过启动线程来测，断言就必须依赖 sleep 和轮询，
     * 那是一种会随机变红的测试，比没有测试更糟。
     *
     * <p>顺序上先接管遗留消息：它们更老，先处理才能让任务大致按投递顺序推进。
     *
     * @return 本轮处理的条数，供循环判断是否需要退让
     */
    int processOnce() {
        int handled = process(queue.reclaimAbandoned());
        handled += process(queue.poll());
        return handled;
    }

    private int process(List<TaskQueue.QueueMessage> messages) {
        for (TaskQueue.QueueMessage message : messages) {
            handle(message);
        }
        return messages.size();
    }

    private void handle(TaskQueue.QueueMessage message) {
        long taskId = message.taskId();
        boolean acknowledge = true;
        try {
            TaskStatus status = taskStore.findTask(taskId).map(MigrationTask::status).orElse(null);

            if (status == null) {
                log.warn("任务 #{} 不存在，丢弃这条队列消息", taskId);
            } else if (isTerminal(status)) {
                // 至少一次投递的必然结果：同一任务可能被投两次。
                // 这里挡一道，避免把已经跑完的任务再跑一遍
                log.info("任务 #{} 已是终态 {}，跳过", taskId, status);
            } else {
                scheduler.runTask(taskId);
            }
        } catch (Exception e) {
            log.error("任务 #{} 执行过程中抛出异常", taskId, e);
            // 关键判断：只有任务确实落了终态才确认。
            // 若是数据库抖动之类的临时故障把它卡在半路，就不确认 ——
            // 消息会在空闲超时后被自己或其它 Worker 接管，从 checkpoint 续跑。
            acknowledge = isTerminal(currentStatus(taskId));
        } finally {
            if (acknowledge) {
                try {
                    queue.ack(message.handle());
                } catch (Exception e) {
                    log.warn("确认消息 {} 失败，该任务可能被重复投递（幂等机制会兜住）", message.handle(), e);
                }
            } else {
                log.warn("任务 #{} 未到达终态，保留未确认状态等待接管重跑", taskId);
            }
        }
    }

    private TaskStatus currentStatus(long taskId) {
        try {
            return taskStore.findTask(taskId).map(MigrationTask::status).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.SUCCEEDED || status == TaskStatus.FAILED;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running.set(false);
        Thread thread = loopThread;
        if (thread == null) {
            return;
        }
        // 循环此刻多半正阻塞在 poll() 里，必须等它返回并检查 running 标志才会退出。
        // 等待时间必须覆盖一次阻塞读的时长，否则 join 会提前超时、把「还在跑」误当成「已停」。
        long waitMs = properties.blockMillis() + 2_000L;
        thread.interrupt();
        try {
            thread.join(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            log.warn("消费循环未在 {} ms 内退出，将随进程结束（它是守护线程，不会阻塞 JVM 关闭）", waitMs);
        }
    }
}
