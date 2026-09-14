package com.remasteragent.core.engine;

import com.remasteragent.common.agent.RewriteResult;
import com.remasteragent.common.agent.VerifyResult;
import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.NodeStatus;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.domain.TaskMetrics;
import com.remasteragent.common.domain.TaskMetricsSnapshot;
import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.core.codec.JsonCodec;
import com.remasteragent.core.config.CoreProperties;
import com.remasteragent.core.engine.node.AnalyzeNode;
import com.remasteragent.core.engine.node.PlanNode;
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
import java.util.LinkedHashMap;
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

            // 调度主循环：每轮重新查就绪节点，因为上一轮执行可能往 DAG 里追加了新节点
            // （回退重写的 attempt+1、规划阶段动态铺进的 REWRITE/VERIFY）
            int guard = 0;
            while (true) {
                if (pauseForPlanReviewIfNeeded(task)) {
                    // 挂起不是完成：直接返回，不走 finalizeTask。批准后重新入队，从 checkpoint 续跑
                    return;
                }
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
     * <p>判断依据是「库里还没有节点」，所以断点续跑时不会重复铺图。
     *
     * <p><b>两种拓扑取决于 PLAN 执行器是否装配：</b>
     * <ul>
     *   <li>有 PLAN（阶段 2 生产形态）：只铺 ANALYZE → PLAN；真正的 REWRITE / VERIFY
     *       由 {@link PlanNode} 在运行期按迁移计划动态插入。这就是「理解整库 → 自动规划 DAG」。</li>
     *   <li>无 PLAN（可降级部署 / 单测桩件）：退回阶段 1 的线性三步，行为与之前完全一致。</li>
     * </ul>
     * <p>把拓扑选择建在「执行器是否存在」上，而不是一个开关：能力缺失时自动退化，
     * 不需要运维记得去改配置，也不会出现「配了 PLAN 却没有实现」的悬空状态。
     */
    private void bootstrapDagIfAbsent(long taskId) {
        if (!taskStore.findNodes(taskId).isEmpty()) {
            log.info("任务 #{} 已有节点记录，按 checkpoint 续跑", taskId);
            return;
        }

        long analyzeId = taskStore.insertNode(taskId, AnalyzeNode.NODE_KEY, NodeType.ANALYZE, List.of(), 0);

        if (executors.containsKey(NodeType.PLAN)) {
            taskStore.insertNode(taskId, PlanNode.NODE_KEY, NodeType.PLAN, List.of(analyzeId), 0);
            log.info("初始 DAG 已铺开: ANALYZE → PLAN（REWRITE/VERIFY 将由规划动态生成）");
            return;
        }

        long rewriteId = taskStore.insertNode(taskId, RewriteNode.NODE_KEY, NodeType.REWRITE,
                List.of(analyzeId), 0);
        taskStore.insertNode(taskId, VerifyNode.NODE_KEY, NodeType.VERIFY, List.of(rewriteId), 0);
        log.info("初始 DAG 已铺开，共 {} 个节点: ANALYZE → REWRITE → VERIFY",
                taskStore.findNodes(taskId).size());
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

        // 回退必须落在「同一个文件」上：多文件场景下，不能因为 f1 失败而去重写 f2
        String filePath = keySuffix(failedNode.nodeKey());

        if (failedNode.nodeType() == NodeType.REWRITE) {
            taskStore.findNode(task.id(), verifyKey(filePath), failedNode.attempt())
                    .filter(verify -> verify.status() == NodeStatus.PENDING
                            || verify.status() == NodeStatus.RUNNING)
                    .ifPresent(verify -> {
                        taskStore.markNodeSkipped(verify.id(), "上游 REWRITE 失败，本轮终止");
                        log.info("已跳过本轮 VERIFY 节点（上游重写失败）");
                    });
        }

        long baseId = baseDependencyId(task.id());
        long rewriteId = taskStore.insertNode(task.id(), rewriteKey(filePath), NodeType.REWRITE,
                List.of(baseId), nextAttempt);
        taskStore.insertNode(task.id(), verifyKey(filePath), NodeType.VERIFY,
                List.of(rewriteId), nextAttempt);

        log.info("↻ 已派生第 {} 轮重写（attempt={} 文件={}），失败反馈 {} 字",
                nextAttempt + 1, nextAttempt, filePath == null ? "(入口文件)" : filePath,
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
        // 取「同一文件」上一轮的信息，避免多文件下把别的文件的失败反馈喂错给这一轮
        String filePath = keySuffix(node.nodeKey());
        int previous = node.attempt() - 1;
        return taskStore.findNode(taskId, verifyKey(filePath), previous)
                .filter(n -> n.status() == NodeStatus.FAILED)
                .map(DagNode::error)
                .or(() -> taskStore.findNode(taskId, rewriteKey(filePath), previous)
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

    /**
     * 从 checkpoint 重放改写产物 —— 多文件场景下按文件逐个重放各自「最新成功」的那一版。
     *
     * <p>单文件时退化为原来的行为（只有一个文件，取它最新成功的改写）。
     */
    private void replaySucceededRewrites(long taskId, Path workspace) {
        Map<String, RewriteResult> latestByFile = new LinkedHashMap<>();
        for (DagNode node : taskStore.findNodes(taskId)) {
            if (node.nodeType() != NodeType.REWRITE || node.status() != NodeStatus.SUCCEEDED) {
                continue;
            }
            RewriteResult rewrite = json.read(node.resultJson(), RewriteResult.class).orElse(null);
            if (rewrite == null || rewrite.filePath() == null) {
                // 读不出来就跳过：重放失败只影响「省下的那一轮模型调用」，不影响正确性
                continue;
            }
            RewriteResult existing = latestByFile.get(rewrite.filePath());
            if (existing == null || rewrite.attempt() >= existing.attempt()) {
                latestByFile.put(rewrite.filePath(), rewrite);
            }
        }

        for (RewriteResult rewrite : latestByFile.values()) {
            try {
                Path target = workspace.resolve(rewrite.filePath()).normalize();
                Files.writeString(target, rewrite.newContent(), StandardCharsets.UTF_8);
                log.info("已从 checkpoint 重放改写产物: {} (attempt={})",
                        rewrite.filePath(), rewrite.attempt());
            } catch (IOException e) {
                log.warn("重放改写产物失败，将按原始工程继续: {}", rewrite.filePath(), e);
            }
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

        // 每个文件的「最新一轮 VERIFY」—— 回退会留下历史 FAILED，只有最新那轮代表现状。
        // 键用节点键里 ':' 之后的部分（文件路径）；裸键（隐式单文件）统一归到 ""。
        Map<String, DagNode> latestVerifyByFile = new LinkedHashMap<>();
        for (DagNode node : nodes) {
            if (node.nodeType() != NodeType.VERIFY) {
                continue;
            }
            String fileKey = keySuffix(node.nodeKey());
            fileKey = fileKey == null ? "" : fileKey;
            DagNode current = latestVerifyByFile.get(fileKey);
            if (current == null || node.attempt() > current.attempt()) {
                latestVerifyByFile.put(fileKey, node);
            }
        }

        // 成功 = 至少有一个 VERIFY，且每个文件的最新一轮都通过（一票否决 —— 有文件没过就是没过）
        boolean succeeded = !latestVerifyByFile.isEmpty()
                && latestVerifyByFile.values().stream()
                        .allMatch(node -> node.status() == NodeStatus.SUCCEEDED);

        // 指标取「最后一次铺开的 VERIFY」：它跑的是整个工程，最能代表最终态
        DagNode representative = nodes.stream()
                .filter(node -> node.nodeType() == NodeType.VERIFY)
                .max(Comparator.comparingLong(DagNode::id))
                .orElse(null);

        TaskMetrics metrics = buildMetrics(task, nodes, representative);
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
            String reason = latestVerifyByFile.values().stream()
                    .filter(node -> node.status() != NodeStatus.SUCCEEDED)
                    .findFirst()
                    .map(DagNode::error)
                    .orElse("没有产生 VERIFY 节点");
            taskStore.updateTaskStatus(taskId, TaskStatus.FAILED, trim(reason, 1000));
            log.warn("任务 #{} 失败，原因: {}", taskId, trim(reason, 200));
            publish(ProgressEvent.taskStatus(taskId, TaskStatus.FAILED.name(), trim(reason, 300)));
        }

        // 落库用存储形状（TaskMetrics），推给前端用传输形状（TaskMetricsSnapshot）。
        // 直接用 TaskMetrics 序列化会把派生方法 compilePassRate()/testPassRate()/retried()
        // 全丢掉（Jackson 对 record 只认组件），前端覆盖 metrics 后进度条归零、图标变 ✗ ——
        // 而覆盖率因为恰好是组件所以照常显示，极难联想到是形状问题。详见 TaskMetricsSnapshot 注释。
        publish(ProgressEvent.taskMetrics(taskId, json.write(TaskMetricsSnapshot.of(metrics))));
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

    /**
     * 规划评审门控 —— 阶段 2「规划结果人工评审」的落点。
     *
     * <p>开启评审（{@code remaster.core.require-plan-approval=true}）时，PLAN 成功即把任务挂到
     * {@code WAITING_HUMAN}，不占用 Worker；人工批准后重新入队，任务从 checkpoint 继续 ——
     * PLAN 已成功不会重跑，直接进入它铺出的 REWRITE。默认关闭，端到端行为与阶段 1/3 一致。
     *
     * @return true 表示已挂起，调用方应立刻返回而<b>不</b>走 finalizeTask（挂起不是完成）
     */
    private boolean pauseForPlanReviewIfNeeded(MigrationTask task) {
        if (!properties.requirePlanApproval()) {
            return false;
        }
        if (taskStore.findLatestSucceeded(task.id(), PlanNode.NODE_KEY).isEmpty()) {
            return false;
        }
        if (taskStore.isPlanApproved(task.id())) {
            return false;
        }
        taskStore.updateTaskStatus(task.id(), TaskStatus.WAITING_HUMAN, null);
        publish(ProgressEvent.taskStatus(task.id(), TaskStatus.WAITING_HUMAN.name(),
                "迁移计划已生成，等待人工评审"));
        log.info("任务 #{} 已挂起等待规划评审；批准后将从此 checkpoint 继续", task.id());
        return true;
    }

    /** 取节点键中 ':' 之后的部分（文件路径）；裸键（隐式单文件模式）返回 null。 */
    private static String keySuffix(String nodeKey) {
        if (nodeKey == null) {
            return null;
        }
        int colon = nodeKey.indexOf(':');
        return (colon < 0 || colon == nodeKey.length() - 1) ? null : nodeKey.substring(colon + 1);
    }

    /** 改写节点键：有文件用带文件的键，否则退回裸键（与 bootstrap 的退化路径一致）。 */
    private static String rewriteKey(String filePath) {
        return filePath == null ? RewriteNode.NODE_KEY : RewriteNode.nodeKey(filePath);
    }

    private static String verifyKey(String filePath) {
        return filePath == null ? VerifyNode.NODE_KEY : VerifyNode.nodeKey(filePath);
    }

    /**
     * 派生节点的基准依赖：优先 PLAN（阶段 2），否则 ANALYZE。
     *
     * <p>二者都必然已成功，用它作依赖能保证新派生的节点立即可就绪。<b>多文件的串行执行
     * 由调度循环「一趟一趟执行就绪节点」保证，而不靠依赖链</b> —— 依赖链在回退时会缠成死结
     * （回退节点依赖的那个 VERIFY 恰好是失败的那个，永远等不到 SUCCEEDED）。
     */
    private long baseDependencyId(long taskId) {
        return taskStore.findNode(taskId, PlanNode.NODE_KEY, 0).map(DagNode::id)
                .or(() -> taskStore.findNode(taskId, AnalyzeNode.NODE_KEY, 0).map(DagNode::id))
                .orElseThrow(() -> new IllegalStateException("初始 ANALYZE/PLAN 节点缺失"));
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
