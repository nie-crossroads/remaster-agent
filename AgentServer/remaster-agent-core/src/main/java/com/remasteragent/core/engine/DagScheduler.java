package com.remasteragent.core.engine;

import com.remasteragent.common.agent.PomRewriteResult;
import com.remasteragent.common.agent.DependencyUpgradeResult;
import com.remasteragent.common.agent.ParentUpgradeResult;
import com.remasteragent.common.agent.RewriteResult;
import com.remasteragent.common.agent.VerifyResult;
import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.HumanGate;
import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.NodeStatus;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.domain.TaskMetrics;
import com.remasteragent.common.domain.TaskMetricsSnapshot;
import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.core.codec.JsonCodec;
import com.remasteragent.core.config.CoreProperties;
import com.remasteragent.core.engine.node.AnalyzeNode;
import com.remasteragent.core.engine.node.GateNode;
import com.remasteragent.core.engine.node.PlanNode;
import com.remasteragent.core.engine.node.PomRewriteNode;
import com.remasteragent.core.engine.node.RewriteNode;
import com.remasteragent.core.engine.node.VerifyNode;
import com.remasteragent.core.progress.ProgressEvent;
import com.remasteragent.core.progress.ProgressPublisher;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.core.trace.TraceTracer;
import com.remasteragent.tools.ast.LegacyImportDetector;
import com.remasteragent.tools.sandbox.WorkspacePreparer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private final TraceTracer tracer;

    /**
     * 单测入口：不埋点、不发布进度。
     *
     * <p>编排层的单测刻意不依赖任何基础设施 —— 不连库、不调模型、不跑 Maven、也不起 OTel SDK。
     * 让埋点成为「可选的最后一层」，是这条纪律能一直守住的必要条件：
     * 一旦它变成构造 DagScheduler 的硬依赖，所有确定性单测都得先配一套追踪环境。
     */
    public DagScheduler(TaskStore taskStore,
                        CoreProperties properties,
                        JsonCodec json,
                        List<NodeExecutor> nodeExecutors,
                        ObjectProvider<ProgressPublisher> publisherProvider) {
        this(taskStore, properties, json, nodeExecutors, publisherProvider, TraceTracer.NOOP);
    }

    /** 生产入口。 */
    @Autowired
    public DagScheduler(TaskStore taskStore,
                        CoreProperties properties,
                        JsonCodec json,
                        List<NodeExecutor> nodeExecutors,
                        ObjectProvider<ProgressPublisher> publisherProvider,
                        TraceTracer tracer) {
        this.taskStore = taskStore;
        this.properties = properties;
        this.json = json;
        this.progressPublisher = publisherProvider.getIfAvailable(() -> ProgressPublisher.NOOP);
        this.tracer = tracer == null ? TraceTracer.NOOP : tracer;
        nodeExecutors.forEach(executor -> this.executors.put(executor.type(), executor));
        log.info("调度器已装配节点执行器: {}", this.executors.keySet());
        logSandboxRoot();
    }

    /**
     * 启动时把沙箱根目录固化到日志里。
     *
     * <p>为什么值得单独打一条：沙箱根若配成相对路径，会按<b>进程工作目录</b>解析 ——
     * 从不同目录启动就各生成一份，且彼此的同名 {@code task-N} 互不相干。真发生时表现为
     * 「文件明明改了却找不到」或「打开了上一批的过期副本」，而现场早已过去。
     * 启动时打出解析结果，下一眼就能确认「这次跑的是哪一份」。
     */
    private void logSandboxRoot() {
        Path root = properties.workspaceRootPath();
        if (properties.workspaceRootIsAbsolute()) {
            log.info("沙箱根目录: {}", root);
        } else {
            log.warn("沙箱根目录配成了相对路径 [{}] —— 解析为 {}，取决于进程工作目录（当前 cwd={}）。"
                            + "换个目录启动会另起一份沙箱、同名任务目录互相覆盖。"
                            + "请改为绝对路径，或用环境变量 REMASTER_WORKSPACE_ROOT 覆盖。",
                    properties.workspaceRoot(), root, System.getProperty("user.dir"));
        }
    }

    /**
     * 执行一个任务 —— 这是 Worker 消费到消息后的唯一入口。
     *
     * <p>整条链路是同步的：对阶段 1 的单文件场景，一次任务几十秒到几分钟，同步执行最简单也最好排查。
     * 需要并发时，扩 Worker 实例即可（同一个 Redis Stream consumer group 竞争消费），
     * 而不是在这个方法里开线程池 —— 那样任务状态与线程生命周期就会纠缠在一起。
     */
    public void runTask(long taskId) {
        runTask(taskId, null);
    }

    /**
     * 执行一个任务，并把它挂到上游链路下面。
     *
     * @param parentTraceparent 由 API 进程经队列消息带过来的 W3C {@code traceparent}。
     *                          为 null 时本次执行自成一条 trace —— 追踪断了不影响任务执行。
     */
    public void runTask(long taskId, String parentTraceparent) {
        MigrationTask task = taskStore.findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));

        Span taskSpan = tracer.startTask(taskId, task.projectRoot(), task.entryFile(),
                task.targetJdk(), parentTraceparent);
        // span 的结束刻意放在 try/catch 之外、且两条路径各一次：放进 finally 就必须额外判断
        // 「是否已经 end 过」，而漏判的后果是重复导出同一段——比多写两行难查得多。
        try (Scope ignored = taskSpan.makeCurrent()) {
            executeTask(task);
            recordTaskOutcome(taskSpan, taskId);
        } catch (RuntimeException e) {
            TraceTracer.endException(taskSpan, e);
            throw e;
        }
        TraceTracer.endOk(taskSpan);
    }

    /**
     * 主执行体（在任务 span 的上下文内运行）。
     *
     * <p>拆出来是为了让 {@link #runTask(long, String)} 只负责 span 的生死 ——
     * 「计时开始/结束」与「干活」混在一个方法里时，任何一处提前 return 都可能漏掉 {@code end()}，
     * 而漏掉的后果是「这条链路永远查不到」，不会报错。
     */
    private void executeTask(MigrationTask task) {
        long taskId = task.id();

        // 取消请求可能在任务排队期间就被置上了（人在它被 Worker 取走前就叫停）。
        // 在这里先看一眼，是为了省掉一次「把整个工程复制一份」的无用功 ——
        // 那一步在大工程上要好几秒。
        if (taskStore.isCancelRequested(taskId)) {
            finalizeCancelled(task, "任务在开始执行前已被取消");
            return;
        }

        log.info("开始执行任务 #{} 工程={} 目标文件={}", taskId, task.projectRoot(), task.entryFile());

        // 按任务重置残留的 RUNNING 节点（上一次进程被杀留下的）。
        // 放在这里而不是 Worker 启动时：重置范围恰好等于「本进程接下来要跑的那份 DAG」，
        // 多 Worker 并存时互不干扰 —— 全局重置会把别的 Worker 正在跑的节点一起清掉。
        int reset = taskStore.resetStaleRunningNodes(taskId);
        if (reset > 0) {
            log.warn("任务 #{} 开始前重置了 {} 个残留的 RUNNING 节点（上一次进程被杀留下的）",
                    taskId, reset);
        }

        taskStore.updateTaskStatus(taskId, TaskStatus.RUNNING, null);
        publish(ProgressEvent.taskStatus(taskId, TaskStatus.RUNNING.name(), "任务开始执行"));

        try {
            Path workspace = restoreWorkspace(task);
            bootstrapDagIfAbsent(task, workspace);

            // 调度主循环：每轮重新查就绪节点，因为上一轮执行可能往 DAG 里追加了新节点
            // （回退重写的 attempt+1、规划阶段动态铺进的 REWRITE/VERIFY）
            int guard = 0;
            while (true) {
                if (pauseForPlanReviewIfNeeded(task)) {
                    // 挂起不是完成：直接返回，不走 finalizeTask。批准后重新入队，从 checkpoint 续跑
                    return;
                }
                if (pauseForGateIfNeeded(task)) {
                    // 通用 GATE 门禁正在等人工：同样挂起不占 Worker，批准后从 checkpoint 续跑
                    return;
                }
                if (taskStore.isCancelRequested(taskId)) {
                    // 协作式取消：只在这里（节点边界）停。此刻没有任何节点在跑，
                    // 停下是干净的 —— 工作目录由 checkpoint 重放生成，不存在半截状态。
                    finalizeCancelled(task, "任务被人工取消");
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
                    if (executeNode(task, node, workspace)) {
                        // 本节点挂起（GATE 门禁）：立刻返回，既不再跑同批的其它节点，
                        // 也不走 finalizeTask —— 挂起不是完成
                        return;
                    }
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

    /**
     * 取消落定 —— 把任务置为 {@code CANCELLED}。
     *
     * <p>刻意<b>不写指标</b>：取消意味着「没跑完」，此刻算出来的编译/单测通过率只反映中途状态。
     * 把它写进 {@code metrics} 会让它和「真跑完的结果」混在同一列里，而报表按这一列聚合时
     * 根本分不出差别 —— 那等于用一个看起来正常的数字污染整个统计。
     */
    private void finalizeCancelled(MigrationTask task, String reason) {
        taskStore.updateTaskStatus(task.id(), TaskStatus.CANCELLED, reason);
        publish(ProgressEvent.taskStatus(task.id(), TaskStatus.CANCELLED.name(), reason));
        log.info("⏹ 任务 #{} 已取消: {}", task.id(), reason);
    }

    /**
     * 把「这次执行最后落在什么状态」写进任务 span 的属性。
     *
     * <p>为什么值得多查一次库：span 的「成功/失败」讲的是<b>执行过程</b>有没有出事，
     * 而任务状态讲的是<b>业务结论</b>。一个跑完全过程的迁移任务同样可能是 FAILED
     * （编译没过），这时 span 是 OK 的，只看得不到这个结论。两者都留着，
     * 「哪一跳最慢」和「最后成没成」才都答得上。
     *
     * <p>失败只在日志里 —— 为了写一个属性而让整个任务的收尾抛异常，本末倒置。
     */
    private void recordTaskOutcome(Span taskSpan, long taskId) {
        try {
            taskStore.findTask(taskId).ifPresent(current ->
                    taskSpan.setAttribute("task.outcome", current.status().name()));
        } catch (Exception e) {
            log.debug("读取任务 #{} 的最终状态以标注 trace 失败（不影响任务）: {}", taskId, e.getMessage());
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
    private void bootstrapDagIfAbsent(MigrationTask task, Path workspace) {
        long taskId = task.id();
        if (!taskStore.findNodes(taskId).isEmpty()) {
            log.info("任务 #{} 已有节点记录，按 checkpoint 续跑", taskId);
            return;
        }

        // 整仓升级模式（没有入口文件）：要做的事只有一件 —— 把全仓 pom 的编译级别抬上去。
        // 这条链刻意不含 ANALYZE：它读的就是入口文件（这里没有），而它顺带做的工程索引
        // 对本模式毫无用处（POM_REWRITE 与 VERIFY 都不调模型），跑一遍只是白烧 embedding 调用。
        if (task.entryFile() == null) {
            bootstrapUpgradeOnly(task);
            return;
        }

        long analyzeId = taskStore.insertNode(taskId, AnalyzeNode.NODE_KEY, NodeType.ANALYZE, List.of(), 0);

        if (executors.containsKey(NodeType.PLAN)) {
            taskStore.insertNode(taskId, PlanNode.NODE_KEY, NodeType.PLAN, List.of(analyzeId), 0);
            log.info("初始 DAG 已铺开: ANALYZE → PLAN（REWRITE/VERIFY 将由规划动态生成）");
            return;
        }

        // 无 PLAN 的降级拓扑里也要先升编译级别 —— 与 PlanNode 分支保持同一顺序，
        // 否则同一个工程「有 PLAN 时能升级、没有 PLAN 时升不上去」，是极难复现的行为差异。
        long chainBase = analyzeId;
        String pomReason = PomUpgradeDecision.reason(workspace, task.targetJdk()).orElse(null);
        if (pomReason != null && executors.containsKey(NodeType.POM_REWRITE)) {
            chainBase = taskStore.insertNode(taskId, PomRewriteNode.NODE_KEY, NodeType.POM_REWRITE,
                    List.of(analyzeId), 0);
            log.info("已插入编译级别升级节点（{}）", pomReason);
        }

        long rewriteId = taskStore.insertNode(taskId, RewriteNode.NODE_KEY, NodeType.REWRITE,
                List.of(chainBase), 0);

        // 门禁开启时把 VERIFY 挂到 GATE 之后，而不是直接挂 REWRITE：
        // 改完先停下等人看一眼补丁，批准后才进沙箱验证
        long verifyDependency = rewriteId;
        String topology = "ANALYZE → REWRITE → VERIFY";
        if (gateEnabled()) {
            verifyDependency = taskStore.insertNode(taskId, GateNode.NODE_KEY, NodeType.GATE,
                    List.of(rewriteId), 0);
            topology = "ANALYZE → REWRITE → GATE → VERIFY";
        }
        taskStore.insertNode(taskId, VerifyNode.NODE_KEY, NodeType.VERIFY, List.of(verifyDependency), 0);

        log.info("初始 DAG 已铺开，共 {} 个节点: {}", taskStore.findNodes(taskId).size(), topology);
    }

    /**
     * 「整仓升级」模式的初始 DAG：{@code POM_REWRITE →（GATE）→ VERIFY}。
     *
     * <p>没有入口文件，所以既没有 ANALYZE（它读的是入口文件），也没有 REWRITE（没有改写目标）。
     * 剩下的正是这个模式的全部内容：抬编译级别，然后在沙箱里用 {@code mvn test} 证明整仓在目标 JDK 下能跑。
     *
     * <p><b>缺 POM_REWRITE 执行器时直接抛异常，而不是退化铺一条别的链。</b>
     * 这个模式的全部内容就是那一个节点 —— 退化之后任务会「什么也没做」却可能显示成功，
     * 属于最坏的一类结果：看起来正常运行，实际上没有任何效果。宁可带着明确原因失败。
     */
    private void bootstrapUpgradeOnly(MigrationTask task) {
        long taskId = task.id();
        if (!executors.containsKey(NodeType.POM_REWRITE)) {
            throw new IllegalStateException(
                    "当前部署未装配 POM_REWRITE 执行器，无法执行整仓升级（任务 #" + taskId + "）");
        }

        long pomId = taskStore.insertNode(taskId, PomRewriteNode.NODE_KEY, NodeType.POM_REWRITE,
                List.of(), 0);

        // 门禁语义照旧生效：开了 requireRewriteApproval 就先停下让人看 pom 补丁。
        // 门禁键用裸 gate（不带文件）—— 这个模式改的是全仓多份 pom，没有单个文件可点名
        long verifyDependency = pomId;
        String topology = "POM_REWRITE → VERIFY";
        if (gateEnabled()) {
            verifyDependency = taskStore.insertNode(taskId, GateNode.NODE_KEY, NodeType.GATE,
                    List.of(pomId), 0);
            topology = "POM_REWRITE → GATE → VERIFY";
        }
        taskStore.insertNode(taskId, VerifyNode.NODE_KEY, NodeType.VERIFY,
                List.of(verifyDependency), 0);

        log.info("初始 DAG 已铺开（整仓升级模式，目标 JDK {}），共 {} 个节点: {}",
                task.targetJdk(), taskStore.findNodes(taskId).size(), topology);
    }

    /**
     * 是否启用「改写后人工门禁」。
     *
     * <p>判据是<b>开关 + 执行器都存在</b>，与本类选择拓扑的既有原则一致：
     * 配了开关但没装配 {@code GateNode} 时自动退化，不铺出无法执行的节点。
     */
    private boolean gateEnabled() {
        return properties.requireRewriteApproval() && executors.containsKey(NodeType.GATE);
    }

    // ------------------------------------------------------------------
    // 节点执行
    // ------------------------------------------------------------------

    /**
     * 执行一个节点，并给这一段计时。
     *
     * <p>节点 span 是整条链路里最整齐的一层 —— 「这个任务的时间花在分析、改写还是验证上」
     * 全靠它回答。外层只负责 span 的生死，真正的结果判定在 {@link #executeNodeInSpan}，
     * 因为「结果是什么」只有在知道 {@code NodeOutcome} 的地方才说得清。
     *
     * @return {@code true} 表示该节点<b>挂起</b>（GATE 门禁在等人工）——
     *         调用方应立即返回，不要继续跑同批其它节点，也不要走 finalizeTask
     */
    private boolean executeNode(MigrationTask task, DagNode node, Path workspace) {
        Span span = tracer.startNode(task.id(), node.id(), node.nodeKey(),
                node.nodeType().name(), node.attempt());
        try (Scope ignored = span.makeCurrent()) {
            return executeNodeInSpan(task, node, workspace, span);
        } catch (RuntimeException e) {
            // 只有「标记节点状态时数据库挂了」这类系统故障才会走到这 ——
            // 节点实现自身的异常在下面已被转成 NodeOutcome.fail
            TraceTracer.endException(span, e);
            throw e;
        }
    }

    /**
     * 节点执行体。{@code span} 由调用方创建、在这里按结果结束 ——
     * 挂起走 {@code endUnset}（既非成功也非失败，只是停了）、成功走 {@code endOk}、
     * 失败走 {@code endError}。三者混为一谈会让「出错率」这个指标失去意义。
     */
    private boolean executeNodeInSpan(MigrationTask task, DagNode node, Path workspace, Span span) {
        NodeExecutor executor = executors.get(node.nodeType());
        if (executor == null) {
            log.error("没有节点类型 {} 的执行器，标记失败", node.nodeType());
            taskStore.markNodeFailed(node.id(), "没有可用的节点执行器: " + node.nodeType(), null);
            TraceTracer.endError(span, "没有可用的节点执行器: " + node.nodeType());
            return false;
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

        if (outcome.suspended()) {
            span.addEvent("gate.suspended");
            TraceTracer.endUnset(span);
            return suspendForGate(task, node, outcome);
        }

        if (outcome.success()) {
            taskStore.markNodeSucceeded(node.id(), json.write(outcome.result()));
            log.info("✔ 节点 [{}] attempt={} 成功", node.nodeKey(), node.attempt());
            publish(ProgressEvent.nodeStatus(task.id(), node.id(), node.nodeKey(),
                    NodeStatus.SUCCEEDED.name(), node.attempt(), describe(node)));
            TraceTracer.endOk(span);
            return false;
        }

        taskStore.markNodeFailed(node.id(), outcome.error(), json.write(outcome.result()));
        log.warn("✗ 节点 [{}] attempt={} 失败: {}", node.nodeKey(), node.attempt(), outcome.error());
        publish(ProgressEvent.nodeStatus(task.id(), node.id(), node.nodeKey(),
                NodeStatus.FAILED.name(), node.attempt(), trim(outcome.error(), 200)));

        planRetry(task, node, outcome, workspace);
        TraceTracer.endError(span, outcome.error());
        return false;
    }

    /**
     * GATE 门禁挂起 —— 把任务停在 WAITING_HUMAN，等人放行。
     *
     * <p>节点退回 PENDING 而非留在 RUNNING：它没跑完，也不该被
     * {@code resetStaleRunningNodes}（Worker 重启的全局清残骸）当成残留再动一次。
     * 真正阻止它被重复执行的是 {@link #pauseForGateIfNeeded}，不是这个状态。
     *
     * <p>挂起与阶段 2 的规划评审共用 {@code WAITING_HUMAN} 这个任务状态，
     * 但两者的解除入口不同（{@code /plan/approve} vs {@code /gate/approve}），
     * 由「是否存在等待中的 human_gate 行」区分。
     */
    private boolean suspendForGate(MigrationTask task, DagNode node, NodeOutcome outcome) {
        taskStore.markNodePending(node.id());
        taskStore.updateTaskStatus(task.id(), TaskStatus.WAITING_HUMAN, null);

        String message = suspendMessage(outcome, node);
        publish(ProgressEvent.nodeStatus(task.id(), node.id(), node.nodeKey(),
                NodeStatus.PENDING.name(), node.attempt(), message));
        publish(ProgressEvent.taskStatus(task.id(), TaskStatus.WAITING_HUMAN.name(), message));
        log.info("⏸ 任务 #{} 已挂起等待人工门禁（节点 {}）", task.id(), node.nodeKey());
        return true;
    }

    /**
     * 失败后的回退决策 —— 整个闭环里最需要讲清楚的一段逻辑，也是阶段 5 多文件迁移的命门。
     *
     * <h2>批语义（阶段 5 多文件迁移的核心修正）</h2>
     * <p>旧拓扑是「每文件 rewrite → verify」各铺一条链，但 VERIFY 跑的是整仓 {@code mvn test}。
     * 一旦 f2 还 javax、f1 已改完，VERIFY(f1) 会因整仓编不过而失败，于是反复重跑 f1 ——
     * 永远修不到 f2，形成不收敛死循环，且白白烧掉整轮 VERIFY 配额。这正是博客工程端到端卡死的根因。
     * 修正后的拓扑（见 {@link PlanNode}）：先铺所有 REWRITE，再铺<b>一条</b>依赖全部 REWRITE 的整仓 VERIFY。
     * 回退也随之改成「批」的：</p>
     * <ul>
     *   <li>REWRITE 失败 → 只重跑这一文件的下一轮 REWRITE（批语义：不牵连其它已成功的文件），
     *       并 {@link #reissueBatchVerify(long)} 重铺整仓 VERIFY 依赖最新改写。</li>
     *   <li>VERIFY 失败 → 整仓没过，按「工作目录里仍含旧 javax 等需迁移 import 的文件」
     *       <b>精准</b>重跑这些文件的下一轮 REWRITE，再重铺整仓 VERIFY。
     *       已干净的文件不重跑，避免「模型对干净文件返回原样 → +0/-0 → 被判失败 → 死循环」。</li>
     *   <li>次数用尽 → 不再派生，任务在 {@link #finalizeTask} 里被判失败。</li>
     * </ul>
     *
     * <p>REWRITE 失败时有一个容易漏掉的细节：它对应的整仓 VERIFY 永远等不到这条 REWRITE 成功，
     * 会永远停在 PENDING。必须显式标记为 SKIPPED，否则 DAG 上会挂着一个永远不会执行的节点，
     * 让人误以为任务还没跑完。</p>
     */
    private void planRetry(MigrationTask task, DagNode failedNode, NodeOutcome outcome, Path workspace) {
        if (properties.stopOnFirstFailure()) {
            log.warn("stopOnFirstFailure=true，不派生重试");
            return;
        }

        boolean retryable = failedNode.nodeType() == NodeType.REWRITE
                || failedNode.nodeType() == NodeType.VERIFY;
        if (!retryable) {
            return;
        }

        long taskId = task.id();
        String filePath = keySuffix(failedNode.nodeKey());

        if (failedNode.nodeType() == NodeType.REWRITE) {
            retryRewrites(taskId, filePath, failedNode, outcome);
        } else {
            retryBatchVerify(taskId, workspace, outcome);
        }
    }

    /**
     * REWRITE 失败 → 只重跑这一文件（批语义：不牵连其它已成功的文件），并 {@link #reissueBatchVerify}
     * 重铺整仓 VERIFY 依赖最新改写。
     *
     * <p>次数上限用「本文件自己的改写轮次」计（{@code maxRewriteAttempts}），与 VERIFY 重试相互独立 ——
     * 否则 REWRITE 失败越多，下方 {@code reissueBatchVerify} 把整仓 VERIFY 的 attempt 胀得越大，
     * 首次真的整仓 VERIFY 一失败就被 {@code maxRewriteAttempts} 截断、精准重跑「仍含旧 import 文件」
     * 的收敛保证彻底失效（博客工程端到端就曾因此卡死）。这是修复 {@code #1} 的关键：
     * VERIFY 的重试上限必须按「真正失败的整仓 VERIFY 轮数」计，而不是被 REWRITE 失败自增的节点 attempt。</p>
     */
    private void retryRewrites(long taskId, String filePath, DagNode failedNode, NodeOutcome outcome) {
        int nextAttempt = failedNode.attempt() + 1;
        if (nextAttempt > properties.maxRewriteAttempts()) {
            log.warn("重写尝试已用尽（上限 {} 次），文件 {} 不再重跑，任务将靠 VERIFY 重试收敛或判失败",
                    properties.maxRewriteAttempts(), filePath == null ? "(入口文件)" : filePath);
            // 仍重铺整仓 VERIFY（依赖最新成功改写），让 DAG 能继续往下走而非卡在悬挂节点
            reissueBatchVerify(taskId);
            publish(ProgressEvent.taskStatus(taskId, TaskStatus.RUNNING.name(), "重写尝试已用尽"));
            return;
        }
        // 本轮的下游全部作废：门禁（若开了）与整仓 VERIFY 都永远等不到这条 REWRITE 成功，
        // 不显式跳过就会在 DAG 上留下永远 PENDING 的悬挂节点
        skipIfPending(taskId, gateKey(filePath), failedNode.attempt(),
                "上游 REWRITE 失败，本轮门禁已跳过");
        skipIfPending(taskId, VerifyNode.NODE_KEY, failedNode.attempt(),
                "上游 REWRITE 失败，本轮整仓 VERIFY 已跳过");
        // 只重跑这一文件（批语义：不牵连其它已成功的文件）
        long baseId = baseDependencyId(taskId);
        insertRetryNode(taskId, rewriteKey(filePath), NodeType.REWRITE, List.of(baseId), nextAttempt);
        log.info("↻ 已派生第 {} 轮重写（attempt={} 文件={}），失败反馈 {} 字",
                nextAttempt + 1, nextAttempt, filePath == null ? "(入口文件)" : filePath,
                outcome.error() == null ? 0 : outcome.error().length());
        reissueBatchVerify(taskId);
        publish(ProgressEvent.taskStatus(taskId, TaskStatus.RUNNING.name(),
                "第 " + (nextAttempt + 1) + " 轮重写已排入队列"));
    }

    /**
     * 整仓 VERIFY 失败 → 精准重跑「工作目录里仍含旧 import」的文件，干净文件不重跑
     * （避免无差别重跑干净文件触发 +0/-0 死循环）。扫描为空（都干净却仍失败）则退化为重跑全部。
     *
     * <p>次数上限用「真正失败的整仓 VERIFY 轮数」计（{@link #countVerifyFailures}），
     * <b>不是</b>被 {@code reissueBatchVerify} 因 REWRITE 失败而自增的节点 attempt ——
     * 这是修复 {@code #1} 的核心，否则 REWRITE 失败越多，VERIFY 的 attempt 胀得越大，
     * 首次真的整仓 VERIFY 一失败就被 {@code maxRewriteAttempts} 截断、精准重跑根本不会触发。</p>
     */
    private void retryBatchVerify(long taskId, Path workspace, NodeOutcome outcome) {
        if (countVerifyFailures(taskId) > properties.maxRewriteAttempts()) {
            log.warn("整仓验证重试已用尽（上限 {} 轮），任务将判定为失败", properties.maxRewriteAttempts());
            return;
        }

        // 精准重跑「工作目录里仍含旧 import」的文件，避免无差别重跑干净文件触发 +0/-0 死循环。
        // 扫描为空（都干净却仍失败）则退化为重跑全部。
        List<String> toRewrite = filesStillNeedingMigration(taskId, workspace);
        boolean scanEmpty = toRewrite.isEmpty();
        if (scanEmpty) {
            toRewrite = collectTargetFiles(taskId);
            log.warn("VERIFY 失败但工作目录里没有任何文件仍含旧 import（可能失败原因不在迁移范围），"
                    + "退化为重跑全部 {} 个文件", toRewrite.size());
        }
        if (toRewrite.isEmpty()) {
            log.warn("VERIFY 失败且没有任何可重跑的文件，任务将无法收敛");
            return;
        }

        long baseId = baseDependencyId(taskId);
        int retried = 0;
        for (String f : toRewrite) {
            // 仍受「每文件 maxRewriteAttempts」约束：已耗尽改写次数的文件不再派生，
            // 否则会去重跑一个「注定改不好」的文件、拖延收敛却无果。
            int latest = latestRewriteAttempt(taskId, f);
            if (latest + 1 > properties.maxRewriteAttempts()) {
                log.warn("文件 {} 重写尝试已用尽（最新 attempt={}），跳过精准重跑", f, latest);
                continue;
            }
            insertRetryNode(taskId, rewriteKey(f), NodeType.REWRITE, List.of(baseId), latest + 1);
            retried++;
        }
        if (retried == 0) {
            log.warn("VERIFY 失败，但所有仍含旧 import 的文件都已耗尽改写次数，任务将无法收敛");
            return;
        }

        int nextRound = countVerifyFailures(taskId); // 本次失败已计入，故下一轮编号 = 已失败轮数
        if (scanEmpty) {
            log.info("↻ 已派生第 {} 轮整仓重跑（全部 {} 个文件，其中 {} 个重新改写）",
                    nextRound + 1, toRewrite.size(), retried);
        } else {
            log.info("↻ 已派生第 {} 轮针对性重跑（{} 个文件仍含旧 import：{}）",
                    nextRound + 1, retried, toRewrite);
        }

        // 重铺整仓 VERIFY：依赖「每文件最新一轮的 REWRITE」，并作废所有仍 PENDING 的旧 VERIFY，
        // 避免依赖错位导致 VERIFY 在重跑完成前抢跑（抢跑会让「改到一半的工程」被当作验证对象）。
        reissueBatchVerify(taskId);
        publish(ProgressEvent.taskStatus(taskId, TaskStatus.RUNNING.name(),
                "第 " + (nextRound + 1) + " 轮精准重跑已排入队列"));
    }

    /** 真正失败的整仓 VERIFY 轮数（SKIPPED 的「重铺」节点不计入，否则上限会被节点 attempt 胀穿）。 */
    private int countVerifyFailures(long taskId) {
        return (int) taskStore.findNodes(taskId).stream()
                .filter(node -> node.nodeType() == NodeType.VERIFY
                        && node.status() == NodeStatus.FAILED)
                .count();
    }

    /** 某个文件的 REWRITE 最新一轮（取最大 attempt）；没有 REWRITE 节点时返回 -1。 */
    private int latestRewriteAttempt(long taskId, String filePath) {
        String fileKey = filePath == null ? "" : filePath;
        return taskStore.findNodes(taskId).stream()
                .filter(node -> node.nodeType() == NodeType.REWRITE
                        && fileKey.equals(keySuffixOrDefault(node.nodeKey())))
                .mapToInt(DagNode::attempt)
                .max()
                .orElse(-1);
    }

    /** 与 {@link #keySuffix} 同口径，但空键归一为 ""，便于按文件聚合。 */
    private static String keySuffixOrDefault(String nodeKey) {
        String suffix = keySuffix(nodeKey);
        return suffix == null ? "" : suffix;
    }

    /**
     * 收集本次迁移涉及的全部目标文件（去重，按 REWRITE 节点键还原文件路径）。
     *
     * <p>裸键（隐式单文件模式）统一归到 ""，与 {@link #keySuffix} 的口径一致。
     */
    private List<String> collectTargetFiles(long taskId) {
        LinkedHashSet<String> files = new LinkedHashSet<>();
        for (DagNode node : taskStore.findNodes(taskId)) {
            if (node.nodeType() != NodeType.REWRITE) {
                continue;
            }
            String fileKey = keySuffix(node.nodeKey());
            files.add(fileKey == null ? "" : fileKey);
        }
        return new ArrayList<>(files);
    }

    /**
     * 工作目录里仍含「需迁移的旧 import」（javax.* → jakarta 的白名单项、或 JDK 移除包）的文件清单。
     *
     * <p>这是批语义收敛性的关键：VERIFY 整仓失败后，只重跑这些文件，
     * 干净文件不重跑 → 不会触发「模型返回原样 → +0/-0 → 被判失败 → 死循环」。
     * 返回空表示工作目录里所有目标文件都已干净（仍失败则原因不在迁移范围）。
     */
    private List<String> filesStillNeedingMigration(long taskId, Path workspace) {
        List<String> result = new ArrayList<>();
        for (String filePath : collectTargetFiles(taskId)) {
            Path target = workspace.resolve(filePath).normalize();
            if (!target.startsWith(workspace) || !Files.isRegularFile(target)) {
                continue;
            }
            try {
                String source = Files.readString(target, StandardCharsets.UTF_8);
                if (fileStillHasOldImports(source)) {
                    result.add(filePath);
                }
            } catch (IOException e) {
                log.warn("读取工作目录文件失败，跳过扫描 {}: {}", filePath, e.getMessage());
            }
        }
        return result;
    }

    /** 文件是否仍含需迁移的旧 import：命中 JDK 移除包，或 javax → jakarta 白名单项。 */
    private static boolean fileStillHasOldImports(String source) {
        // 与 RewriteNode 的「改写后残留旧 import 校验」共用同一套判定，避免两套口径对不上
        return LegacyImportDetector.hasLeftover(source);
    }

    /**
     * 重铺整仓 VERIFY，使其依赖「每文件最新一轮的 REWRITE」。
     *
     * <p>批语义下 VERIFY 只有一条、但依赖全部改写。回退会往 DAG 里追加下一轮的 REWRITE，
     * 旧的 VERIFY 依赖的是上一轮改写（可能已失败或将被重跑），必须重铺：
     * 取每文件 attempt 最大的 REWRITE 作为新依赖，并跳过所有还 PENDING 的旧 VERIFY
     * （它们依赖错位，抢跑会让「改到一半的工程」被当作验证对象，且会与新 VERIFY 抢同一轮）。
     */
    private void reissueBatchVerify(long taskId) {
        Map<String, DagNode> latestRewriteByFile = new LinkedHashMap<>();
        for (DagNode node : taskStore.findNodes(taskId)) {
            if (node.nodeType() != NodeType.REWRITE) {
                continue;
            }
            String fileKey = keySuffix(node.nodeKey());
            fileKey = fileKey == null ? "" : fileKey;
            DagNode current = latestRewriteByFile.get(fileKey);
            if (current == null || node.attempt() > current.attempt()) {
                latestRewriteByFile.put(fileKey, node);
            }
        }
        List<Long> deps = latestRewriteByFile.values().stream().map(DagNode::id).toList();

        int newAttempt = taskStore.findNodes(taskId).stream()
                .filter(n -> n.nodeType() == NodeType.VERIFY)
                .mapToInt(DagNode::attempt)
                .max()
                .orElse(-1) + 1;
        for (DagNode node : taskStore.findNodes(taskId)) {
            if (node.nodeType() == NodeType.VERIFY
                    && node.status() == NodeStatus.PENDING
                    && node.attempt() < newAttempt) {
                taskStore.markNodeSkipped(node.id(), "回退重铺 VERIFY，旧 VERIFY 依赖错位已跳过");
            }
        }
        taskStore.insertNode(taskId, VerifyNode.NODE_KEY, NodeType.VERIFY, deps, newAttempt);
    }

    /** 把某个仍处于 PENDING/RUNNING 的节点标记为 SKIPPED（已是终态则不动）。 */
    private void skipIfPending(long taskId, String nodeKey, int attempt, String reason) {
        taskStore.findNode(taskId, nodeKey, attempt)
                .filter(node -> node.status() == NodeStatus.PENDING
                        || node.status() == NodeStatus.RUNNING)
                .ifPresent(node -> {
                    taskStore.markNodeSkipped(node.id(), reason);
                    log.info("已跳过节点 [{}] attempt={}：{}", nodeKey, attempt, reason);
                });
    }

    /**
     * 幂等插入重试节点：若 {@code (taskId, nodeKey, attempt)} 已存在则复用既有 id，不再插入。
     *
     * <p>必要性：多文件场景下，同一个文件的 {@code REWRITE} 与 {@code VERIFY} 可能先后失败，
     * 各自触发一次 {@link #planRetry}；两次都会尝试派生 {@code (file, attempt+1)} 的
     * REWRITE/VERIFY 节点，第二次插入会撞上 {@code uk_dag_node_task_key_attempt} 唯一约束，
     * 直接让整个任务 abort。先查后插可彻底消除这条碰撞路径。Worker 是单线程消费循环，
     * 不存在并发插入竞争，故无需数据库层的 {@code ON CONFLICT}。
     */
    private long insertRetryNode(long taskId, String nodeKey, NodeType nodeType,
                                List<Long> dependsOn, int attempt) {
        return taskStore.findNode(taskId, nodeKey, attempt)
                .map(DagNode::id)
                .orElseGet(() -> taskStore.insertNode(taskId, nodeKey, nodeType, dependsOn, attempt));
    }

    /**
     * 取出要喂给本轮重写的失败反馈。
     *
     * <p>批语义下只有一条整仓 VERIFY，其失败信息最具体（整仓编译错、失败用例名、编译错误定位行），
     * 优先取<b>最近一次失败的 VERIFY</b>。没有 VERIFY 失败（如 REWRITE 先于 VERIFY 失败、
     * 整仓 VERIFY 还没机会跑）则退回<b>同一文件上一轮 REWRITE</b> 自身的失败原因
     * （例如 Guardrail 判定的产出不合法）。
     */
    private String resolveRetryFeedback(long taskId, DagNode node) {
        if (node.nodeType() != NodeType.REWRITE || node.attempt() == 0) {
            return null;
        }
        String filePath = keySuffix(node.nodeKey());
        int previous = node.attempt() - 1;
        return taskStore.findNodes(taskId).stream()
                .filter(n -> n.nodeType() == NodeType.VERIFY && n.status() == NodeStatus.FAILED)
                .max(Comparator.comparingLong(DagNode::id))
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
        Path workspace = properties.workspaceRootPath().resolve("task-" + task.id());

        WorkspacePreparer.prepare(sourceRoot, workspace);
        replaySucceededRewrites(task.id(), workspace);
        return workspace;
    }

    /**
     * 从 checkpoint 重放改写产物 —— 多文件场景下按文件逐个重放各自「最新成功」的那一版。
     *
     * <p>单文件时退化为原来的行为（只有一个文件，取它最新成功的改写）。
     *
     * <p><b>POM_REWRITE 的产物也要重放</b>，理由与 {@code RewriteResult} 相同、但后果更隐蔽：
     * pom 不在任何 REWRITE 节点的产物里，任务一旦因人工门禁/规划评审挂起再重新入队，
     * 沙箱是重建的（{@code WorkspacePreparer.prepare} 清空重建），编译级别会悄悄退回升级前，
     * 后续 VERIFY 于是在旧级别下编译新语法而必然失败 —— 而失败原因看起来是「代码写错了」。
     * 这类「真因与现象错位」的坑在本文档里出现过太多次，一律用「产出带上完整内容 + 重放」堵死。
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
            writeReplayFile(workspace, rewrite.filePath(), rewrite.newContent());
        }

        for (DagNode node : taskStore.findNodes(taskId)) {
            if (node.nodeType() != NodeType.POM_REWRITE || node.status() != NodeStatus.SUCCEEDED) {
                continue;
            }
            PomRewriteResult pomRewrite = json.read(node.resultJson(), PomRewriteResult.class).orElse(null);
            if (pomRewrite == null) {
                continue;
            }
            for (PomRewriteResult.FileChange change : pomRewrite.files()) {
                writeReplayFile(workspace, change.filePath(), change.newContent());
            }
        }

        // DEPENDENCY_UPGRADE 的产出同样要重放：它往 pom 里注入 jakarta 依赖，这些改动不在任何
        // REWRITE 节点产物里。任务一旦挂起再重新入队，沙箱是重建的，依赖注入会被悄悄洗掉，
        // 后续 VERIFY 于是在「缺 jakarta 依赖」下编译改写后的 jakarta.* import 而必然失败 ——
        // 而失败原因看起来是「代码写错了」。与 POM_REWRITE 同一道坑，用重放堵死。
        for (DagNode node : taskStore.findNodes(taskId)) {
            if (node.nodeType() != NodeType.DEPENDENCY_UPGRADE || node.status() != NodeStatus.SUCCEEDED) {
                continue;
            }
            DependencyUpgradeResult depUpgrade = json.read(node.resultJson(), DependencyUpgradeResult.class).orElse(null);
            if (depUpgrade == null) {
                continue;
            }
            for (DependencyUpgradeResult.FileChange change : depUpgrade.files()) {
                writeReplayFile(workspace, change.filePath(), change.newContent());
            }
        }

        // PARENT_UPGRADE 的产出也要重放：它把 spring-boot-starter-parent 升到 3.x，这些改动同样不在任何
        // REWRITE 节点产物里。任务一旦挂起再重新入队，沙箱是重建的，parent 版本会悄悄退回升级前，
        // 后续 VERIFY 于是在旧 BOM（javax 命名空间）下编译改写后的 jakarta.* import 而必然失败 ——
        // 而失败原因看起来是「代码写错了」。与前两者同一道坑，用重放堵死。
        for (DagNode node : taskStore.findNodes(taskId)) {
            if (node.nodeType() != NodeType.PARENT_UPGRADE || node.status() != NodeStatus.SUCCEEDED) {
                continue;
            }
            ParentUpgradeResult parentUpgrade = json.read(node.resultJson(), ParentUpgradeResult.class).orElse(null);
            if (parentUpgrade == null) {
                continue;
            }
            for (ParentUpgradeResult.FileChange change : parentUpgrade.files()) {
                writeReplayFile(workspace, change.filePath(), change.newContent());
            }
        }
    }

    /** 把一个重放产物写到工作目录（失败只告警：重放不是任务成立的充分条件，VERIFY 才是）。 */
    private void writeReplayFile(Path workspace, String filePath, String content) {
        if (filePath == null || content == null) {
            return;
        }
        try {
            Path target = workspace.resolve(filePath).normalize();
            if (!target.startsWith(workspace)) {
                log.warn("重放产物路径越出工作目录，已跳过: {}", filePath);
                return;
            }
            Files.writeString(target, content, StandardCharsets.UTF_8);
            log.info("已从 checkpoint 重放改写产物: {}", filePath);
        } catch (IOException e) {
            log.warn("重放改写产物失败，将按原始工程继续: {}", filePath, e);
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
            // 跳过从未真正执行的 PENDING：它只是「已铺开但还没轮到」，没有 error。
            // 若把它当成最新一轮，失败原因会从真实编译错误退化成「没有产生 VERIFY 节点」这种假话
            // —— 实测 #57/#58 都栽在这里：真正跑过的 VERIFY 明明报了编译失败，却被 PENDING 顶掉，
            // 于是排查方向被彻底带偏（看起来像「根本没验证」，实际是「验证了但没编过」）。
            if (node.status() == NodeStatus.PENDING) {
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
                countRewriteTargets(nodes, lastVerify),
                verify != null && verify.compiled() ? countRewriteTargets(nodes, lastVerify) : 0,
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

    /**
     * 参与迁移的文件数 = 去重后的 REWRITE 目标数。
     *
     * <p>早先这里硬编码 1（阶段 1 只改一个文件的遗留）。PLAN 现在可以为一次任务规划
     * 多个文件（实测 {@code inventory-legacy} 一次规划了 4 个），于是「编译通过率」
     * 会永远显示 1/1 —— 分母是假的分母，比率再好看也没有意义。
     *
     * <p>没有 REWRITE 节点时：有 VERIFY 说明走过隐式单文件路径，回落 1；
     * 否则是 0（什么都没改写），不能顺手报 1 —— 那会让「没跑起来」显示成「100% 之外的失败」。
     */
    private int countRewriteTargets(List<DagNode> nodes, DagNode lastVerify) {
        long targets = nodes.stream()
                .filter(node -> node.nodeType() == NodeType.REWRITE)
                .map(node -> {
                    String suffix = keySuffix(node.nodeKey());
                    return suffix == null ? "" : suffix;
                })
                .distinct()
                .count();
        if (targets > 0) {
            return (int) targets;
        }
        return lastVerify == null ? 0 : 1;
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

    /**
     * 通用 GATE 门禁的挂起判据（阶段 3）—— 与规划评审并列的第二道「人在回路」。
     *
     * <p>判据极其简单：<b>库里存在一道 PENDING 的 human_gate</b> 就挂起。
     * 它在调度循环的每一轮开头检查，覆盖三种情形：
     * <ol>
     *   <li>首轮执行到 GATE 节点 → {@code GateNode} 落行并返回挂起，任务已是 WAITING_HUMAN；</li>
     *   <li>批准前的重复投递 / Worker 重启重入队 → 门还在，这里直接拦住，
     *       不会越过它去跑下游，也不会重复执行 GATE 节点落第二行；</li>
     *   <li>批准后重入队 → 门已是 APPROVED，{@code findOpenGate} 返回空，正常继续。</li>
     * </ol>
     *
     * <p>用「库里有没有等待中的门」而不是「节点是不是 GATE 类型」作判据，
     * 是因为前者的真值来源唯一（human_gate 表），且天然覆盖了「门已被批准、该放行」
     * 这个相反方向；后者需要额外判断门的审批状态，反而更容易写错。
     *
     * @return true 表示已挂起，调用方应立刻返回而不走 finalizeTask
     */
    private boolean pauseForGateIfNeeded(MigrationTask task) {
        Optional<HumanGate> openGate = taskStore.findOpenGate(task.id());
        if (openGate.isEmpty()) {
            return false;
        }
        if (task.status() != TaskStatus.WAITING_HUMAN) {
            // 只有状态还没反映出来时才推事件，避免重入队时把同一条挂起消息刷屏
            taskStore.updateTaskStatus(task.id(), TaskStatus.WAITING_HUMAN, null);
            publish(ProgressEvent.taskStatus(task.id(), TaskStatus.WAITING_HUMAN.name(),
                    "等待人工门禁审批（gate #" + openGate.get().id() + "）"));
        }
        log.info("任务 #{} 仍有一道待审批门禁 gate #{}，保持挂起", task.id(), openGate.get().id());
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

    /** 门禁节点键：与 rewrite 同款——有文件用带文件的键，否则退回裸键。 */
    private static String gateKey(String filePath) {
        return filePath == null ? GateNode.NODE_KEY : GateNode.nodeKey(filePath);
    }

    /** 把挂起现场翻成一句能展示给用户的话。 */
    private static String suspendMessage(NodeOutcome outcome, DagNode node) {
        if (outcome.result() instanceof GateNode.GateSuspend gate && gate.message() != null) {
            return gate.message();
        }
        return "已挂起等待人工审批（节点 " + node.nodeKey() + "）";
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
