package com.remasteragent.core.engine;

import com.remasteragent.common.agent.RewriteResult;
import com.remasteragent.common.agent.VerifyResult;
import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.NodeStatus;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.domain.TaskMetrics;
import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.core.codec.JsonCodec;
import com.remasteragent.core.config.CoreProperties;
import com.remasteragent.core.engine.node.AnalyzeNode;
import com.remasteragent.core.engine.node.RewriteNode;
import com.remasteragent.core.engine.node.VerifyNode;
import com.remasteragent.core.progress.ProgressEvent;
import com.remasteragent.core.progress.ProgressPublisher;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.tools.sandbox.WorkspacePreparer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DAG 调度器 —— 本项目自研编排层的核心。
 *
 * <h2>为什么不用现成的编排框架</h2>
 * <p>Agent 编排框架（LangGraph、Spring AI Graph 之类）擅长的是「把几个步骤串起来」。
 * 但这里要的是<b>长任务</b>的编排：一个任务可能跑几十分钟，中途某个节点失败要能只回退那一步、
 * 进程被杀要能从断点续跑、重试要有上限以免无限烧钱。这些是工作流引擎的语义，不是链路编排的语义。
 *
 * <h2>三个关键设计</h2>
 * <ol>
 *   <li><b>按需查就绪节点，而不是预先算拓扑序。</b>
 *       回退重写会在运行期往 DAG 里追加新节点，预先算好的拓扑序立刻失效。
 *       每轮问一次「哪些节点所有依赖都成功了」，天然支持动态扩图。</li>
 *   <li><b>失败不是异常，是流程的一部分。</b> 编译不过 → 回退重写 → 再验证，这是正常路径。
 *       只有系统故障才抛异常。</li>
 *   <li><b>从 checkpoint 重放工作目录。</b> 见 {@link #restoreWorkspace}。断点续跑不是
 *       「留着上次的现场」，而是「用原始工程 + 已成功节点的产出重新算一遍」——
 *       后者是确定性的，前者不是。</li>
 * </ol>
 */
@Component
public class DagScheduler {

    private static final Logger log = LoggerFactory.getLogger(DagScheduler.class);

    private final TaskStore taskStore;
    private final CoreProperties properties;
    private final JsonCodec json;
    private final Map<NodeType, NodeExecutor> executors = new EnumMap<>(NodeType.class);
    private final ProgressPublisher progressPublisher;

    public DagScheduler(TaskStore taskStore,
                        CoreProperties properties,
                        JsonCodec json,
                        List<NodeExecutor> nodeExecutors,
                        ObjectProvider<ProgressPublisher> publisherProvider) {
        this.taskStore = taskStore;
        this.properties = properties;
        this.json = json;
        this.progressPublisher = publisherProvider.getIfAvailable(() -> ProgressPublisher.NOOP);
        nodeExecutors.forEach(executor -> this.executors.put(executor.type(), executor));
        log.info("调度器已装配节点执行器: {}", this.executors.keySet());
    }

    /**
     * 执行一个任务 —— 这是 Worker 消费到消息后的唯一入口。
     *
     * <p>整条链路是同步的：对阶段 1 的单文件场景，一次任务几十秒到几分钟，同步执行最简单也最好排查。
     * 需要并发时，扩 Worker 实例即可（同一个 Redis Stream consumer group 竞争消费），
     * 而不是在这个方法里开线程池 —— 那样任务状态与线程生命周期就会纠缠在一起。
     */
    public void runTask(long taskId) {
        MigrationTask task = taskStore.findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));

        log.info("开始执行任务 #{} 工程={} 目标文件={}", taskId, task.projectRoot(), task.entryFile());
        taskStore.updateTaskStatus(taskId, TaskStatus.RUNNING, null);
        publish(ProgressEvent.taskStatus(taskId, TaskStatus.RUNNING.name(), "任务开始执行"));

        try {
            Path workspace = restoreWorkspace(task);
            bootstrapDagIfAbsent(taskId);

            // 调度主循环：每轮重新查就绪节点，因为上一轮执行可能往 DAG 里追加了新节点（回退重写）
            int guard = 0;
            while (true) {
                List<DagNode> runnable = taskStore.findRunnable(taskId);
                if (runnable.isEmpty()) {
                    break;
                }
                if (++guard > 200) {
                    // 正常任务不该跑这么多轮；触发说明 DAG 里出现了环或状态异常，
                    // 与其无限循环，不如带着现场失败
                    throw new IllegalStateException("调度轮次异常增长，疑似 DAG 出现环");
                }
                for (DagNode node : runnable) {
                    executeNode(task, node, workspace);
                }
            }

            finalizeTask(task, workspace);
        } catch (Exception e) {
            log.error("任务 #{} 执行失败", taskId, e);
            taskStore.updateTaskStatus(taskId, TaskStatus.FAILED, e.getMessage());
            publish(ProgressEvent.taskStatus(taskId, TaskStatus.FAILED.name(), "任务异常终止: " + e.getMessage()));
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // DAG 构建
    // ------------------------------------------------------------------

    /**
     * 首次进入时铺出初始 DAG。
     *
     * <p>阶段 1 是固定的线性三步；阶段 2 会由 MigrationPlanner 产出真正的 DAG 计划并批量落库。
     * 这里的判断依据是「库里还没有节点」，所以断点续跑时不会重复铺图。
     */
    private void bootstrapDagIfAbsent(long taskId) {
        if (!taskStore.findNodes(taskId).isEmpty()) {
            log.info("任务 #{} 已有节点记录，按 checkpoint 续跑", taskId);
            return;
        }

        long analyzeId = taskStore.insertNode(taskId, AnalyzeNode.NODE_KEY, NodeType.ANALYZE, List.of(), 0);
        long rewriteId = taskStore.insertNode(taskId, RewriteNode.NODE_KEY, NodeType.REWRITE,
                List.of(analyzeId), 0);
        taskStore.insertNode(taskId, VerifyNode.NODE_KEY, NodeType.VERIFY, List.of(rewriteId), 0);

        long count = taskStore.findNodes(taskId).size();
        log.info("初始 DAG 已铺开，共 {} 个节点: ANALYZE → REWRITE → VERIFY", count);
    }

    // ------------------------------------------------------------------
    // 节点执行
    // ------------------------------------------------------------------

    private void executeNode(MigrationTask task, DagNode node, Path workspace) {
        NodeExecutor executor = executors.get(node.nodeType());
        if (executor == null) {
            log.error("没有节点类型 {} 的执行器，标记失败", node.nodeType());
            taskStore.markNodeFailed(node.id(), "没有可用的节点执行器: " + node.nodeType(), null);
            return;
        }

        log.info("▶ 执行节点 [{}] attempt={} type={}", node.nodeKey(), node.attempt(), node.nodeType());
        taskStore.markNodeRunning(node.id());
        publish(ProgressEvent.nodeStatus(task.id(), node.id(), node.nodeKey(),
                NodeStatus.RUNNING.name(), node.attempt(), describe(node)));

        NodeContext context = new NodeContext(task, node, workspace, resolveRetryFeedback(task.id(), node));
        NodeOutcome outcome;
        try {
            outcome = executor.execute(context);
        } catch (Exception e) {
            // 节点实现抛异常 = 系统故障，但整个任务不应该就此崩掉：
            // 记下来、标记失败、让调度器决定后续（可能是重试，也可能是任务失败）
            log.error("节点 {} 执行抛出异常", node.nodeKey(), e);
            outcome = NodeOutcome.fail("节点执行异常: " + e);
        }

        if (outcome.success()) {
            taskStore.markNodeSucceeded(node.id(), json.write(outcome.result()));
            log.info("✔ 节点 [{}] attempt={} 成功", node.nodeKey(), node.attempt());
            publish(ProgressEvent.nodeStatus(task.id(), node.id(), node.nodeKey(),
                    NodeStatus.SUCCEEDED.name(), node.attempt(), describe(node)));
            return;
        }

        taskStore.markNodeFailed(node.id(), outcome.error(), json.write(outcome.result()));
        log.warn("✗ 节点 [{}] attempt={} 失败: {}", node.nodeKey(), node.attempt(), outcome.error());
        publish(ProgressEvent.nodeStatus(task.id(), node.id(), node.nodeKey(),
                NodeStatus.FAILED.name(), node.attempt(), trim(outcome.error(), 200)));

        planRetry(task, node, outcome);
    }

    /**
     * 失败后的回退决策 —— 整个闭环里最需要讲清楚的一段逻辑。
     *
     * <p>规则：
     * <ul>
     *   <li>ANALYZE 失败不重试。源码都解析不了说明任务前提不成立，重试只是浪费钱。</li>
     *   <li>REWRITE / VERIFY 失败 → 派生下一轮（attempt+1）的 REWRITE + VERIFY。</li>
     *   <li>次数用尽 → 不再派生，任务在 {@link #finalizeTask} 里被判失败。</li>
     * </ul>
     *
     * <p>REWRITE 失败时有一个容易漏掉的细节：它对应的 VERIFY(当前轮) 永远等不到依赖成功，
     * 会永远停在 PENDING。必须显式标记为 SKIPPED，否则 DAG 上会挂着一个永远不会执行的节点，
     * 让人误以为任务还没跑完。
     */
    private void planRetry(MigrationTask task, DagNode failedNode, NodeOutcome outcome) {
        if (properties.stopOnFirstFailure()) {
            log.warn("stopOnFirstFailure=true，不派生重试");
            return;
        }

        boolean retryable = failedNode.nodeType() == NodeType.REWRITE
                || failedNode.nodeType() == NodeType.VERIFY;
        if (!retryable) {
            return;
        }

        int nextAttempt = failedNode.attempt() + 1;
        if (nextAttempt > properties.maxRewriteAttempts()) {
            log.warn("重写尝试已用尽（上限 {} 次），任务将判定为失败", properties.maxRewriteAttempts());
            return;
        }

        if (failedNode.nodeType() == NodeType.REWRITE) {
            taskStore.findNode(task.id(), VerifyNode.NODE_KEY, failedNode.attempt())
                    .filter(verify -> verify.status() == NodeStatus.PENDING
                            || verify.status() == NodeStatus.RUNNING)
                    .ifPresent(verify -> {
                        taskStore.markNodeSkipped(verify.id(), "上游 REWRITE 失败，本轮终止");
                        log.info("已跳过本轮 VERIFY 节点（上游重写失败）");
                    });
        }

        long analyzeId = taskStore.findNode(task.id(), AnalyzeNode.NODE_KEY, 0)
                .map(DagNode::id)
                .orElseThrow(() -> new IllegalStateException("初始 ANALYZE 节点缺失"));

        long rewriteId = taskStore.insertNode(task.id(), RewriteNode.NODE_KEY, NodeType.REWRITE,
                List.of(analyzeId), nextAttempt);
        taskStore.insertNode(task.id(), VerifyNode.NODE_KEY, NodeType.VERIFY,
                List.of(rewriteId), nextAttempt);

        log.info("↻ 已派生第 {} 轮重写（attempt={}），失败反馈 {} 字",
                nextAttempt + 1, nextAttempt,
                outcome.error() == null ? 0 : outcome.error().length());
        publish(ProgressEvent.taskStatus(task.id(), TaskStatus.RUNNING.name(),
                "第 " + (nextAttempt + 1) + " 轮重写已排入队列"));
    }

    /**
     * 取出要喂给本轮重写的失败反馈。
     *
     * <p>优先取上一轮 VERIFY 的失败信息（最具体：失败用例名、编译错误行），
     * 没有就退回上一轮 REWRITE 的失败原因（比如 Guardrail 判定的产出不合法）。
     */
    private String resolveRetryFeedback(long taskId, DagNode node) {
        if (node.nodeType() != NodeType.REWRITE || node.attempt() == 0) {
            return null;
        }
        int previous = node.attempt() - 1;
        return taskStore.findNode(taskId, VerifyNode.NODE_KEY, previous)
                .filter(n -> n.status() == NodeStatus.FAILED)
                .map(DagNode::error)
                .or(() -> taskStore.findNode(taskId, RewriteNode.NODE_KEY, previous)
                        .filter(n -> n.status() == NodeStatus.FAILED)
                        .map(DagNode::error))
                .orElse(null);
    }

    // ------------------------------------------------------------------
    // 工作目录与收尾
    // ------------------------------------------------------------------

    /**
     * 准备工作目录 —— 从 checkpoint <b>重放</b>，而不是「保留上次的现场」。
     *
     * <p>做法：先把原工程完整复制一份（这一步天然把任何污染冲掉），
     * 再把已成功节点的产出重新应用上去。对阶段 1 来说就是「把最新一次成功的改写内容写回文件」。
     *
     * <p>为什么不用「保留现场」这种更省事的做法：进程被杀的时刻是不确定的，
     * 现场可能处于「文件写了一半」的状态。基于不确定的现场继续跑，
     * 会得到无法复现的结果 —— 而一个不可复现的评测报告在面试里是负资产。
     * 重放是确定性的：同样的 checkpoint 一定得到同样的工作目录。
     */
    private Path restoreWorkspace(MigrationTask task) {
        Path sourceRoot = Paths.get(task.projectRoot()).toAbsolutePath().normalize();
        Path workspace = Paths.get(properties.workspaceRoot())
                .toAbsolutePath().normalize()
                .resolve("task-" + task.id());

        WorkspacePreparer.prepare(sourceRoot, workspace);
        replaySucceededRewrites(task.id(), workspace);
        return workspace;
    }

    private void replaySucceededRewrites(long taskId, Path workspace) {
        Optional<DagNode> latest = taskStore.findLatestSucceeded(taskId, RewriteNode.NODE_KEY);
        if (latest.isEmpty()) {
            return;
        }
        String resultJson = latest.get().resultJson();
        RewriteResult rewrite = json.read(resultJson, RewriteResult.class).orElse(null);
        if (rewrite == null) {
            // 读不出来就按原始工程继续：重放失败只影响「省下的那一轮模型调用」，
            // 不影响正确性 —— 大不了从头再改一次
            return;
        }
        try {
            Path target = workspace.resolve(rewrite.filePath()).normalize();
            Files.writeString(target, rewrite.newContent(), StandardCharsets.UTF_8);
            log.info("已从 checkpoint 重放改写产物: {} (attempt={})",
                    rewrite.filePath(), rewrite.attempt());
        } catch (IOException e) {
            log.warn("重放改写产物失败，将按原始工程继续", e);
        }
    }

    /**
     * 收尾：判定任务成败并汇总量化指标。
     *
     * <p>判定依据是<b>最后一轮 VERIFY 的状态</b>，而不是「有没有节点失败过」——
     * 因为回退重写必然会产生失败节点，用后者判定会导致所有发生过重试的任务都被误判为失败。
     */
    private void finalizeTask(MigrationTask task, Path workspace) {
        long taskId = task.id();
        List<DagNode> nodes = taskStore.findNodes(taskId);

        Optional<DagNode> lastVerify = nodes.stream()
                .filter(node -> node.nodeType() == NodeType.VERIFY)
                .max(Comparator.comparingInt(DagNode::attempt));

        boolean succeeded = lastVerify
                .map(node -> node.status() == NodeStatus.SUCCEEDED)
                .orElse(false);

        TaskMetrics metrics = buildMetrics(task, nodes, lastVerify.orElse(null));
        taskStore.saveTaskMetrics(taskId, json.write(metrics));

        if (succeeded) {
            taskStore.updateTaskStatus(taskId, TaskStatus.SUCCEEDED, null);
            log.info("任务 #{} 成功｜编译通过率 {}｜单测通过率 {}｜覆盖率 {}｜成本 {}｜LLM 调用 {} 次",
                    taskId,
                    percent(metrics.compilePassRate()),
                    percent(metrics.testPassRate()),
                    metrics.coverage() < 0 ? "未采集" : percent(metrics.coverage()),
                    String.format("%.4f", metrics.totalCost()),
                    metrics.llmCalls());
            publish(ProgressEvent.taskStatus(taskId, TaskStatus.SUCCEEDED.name(), "任务完成"));
        } else {
            String reason = lastVerify.map(DagNode::error).orElse("没有产生 VERIFY 节点");
            taskStore.updateTaskStatus(taskId, TaskStatus.FAILED, trim(reason, 1000));
            log.warn("任务 #{} 失败，原因: {}", taskId, trim(reason, 200));
            publish(ProgressEvent.taskStatus(taskId, TaskStatus.FAILED.name(), trim(reason, 300)));
        }

        publish(ProgressEvent.taskMetrics(taskId, json.write(metrics)));
    }

    private TaskMetrics buildMetrics(MigrationTask task, List<DagNode> nodes, DagNode lastVerify) {
        VerifyResult verify = parseVerifyResult(lastVerify);
        TaskStore.CostSummary cost = taskStore.summarizeCost(task.id());
        long durationMs = Duration.between(
                task.createdAt() == null ? Instant.now() : task.createdAt(), Instant.now()).toMillis();

        return new TaskMetrics(
                1,
                verify != null && verify.compiled() ? 1 : 0,
                verify == null ? 0 : verify.testsTotal(),
                verify == null ? 0 : verify.testsPassed(),
                verify == null ? -1d : verify.coverage(),
                cost.calls(),
                cost.promptTokens(),
                cost.completionTokens(),
                cost.totalCost(),
                taskStore.countVerifyRounds(task.id()),
                durationMs);
    }

    private VerifyResult parseVerifyResult(DagNode node) {
        if (node == null) {
            return null;
        }
        // 读不出来就当作「没有验证结果」：旧 checkpoint 形状不一致时，
        // 指标会退化成 0 而不是让整条链路崩掉 —— 任务能跑完比指标完整更重要
        return json.read(node.resultJson(), VerifyResult.class).orElse(null);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private void publish(ProgressEvent event) {
        try {
            progressPublisher.publish(event);
        } catch (Exception e) {
            // 进度推送失败绝不能影响任务执行本身 —— 它是可观测性，不是正确性
            log.warn("发布进度事件失败: {}", event.type(), e);
        }
    }

    private static String describe(DagNode node) {
        return node.nodeType() + " (attempt " + node.attempt() + ")";
    }

    private static String percent(double ratio) {
        return String.format("%.1f%%", ratio * 100);
    }

    private static String trim(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
