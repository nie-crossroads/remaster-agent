package com.remasteragent.core.engine.node;

import com.remasteragent.common.agent.PlanResult;
import com.remasteragent.common.domain.LlmCallRecord;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.core.config.CoreProperties;
import com.remasteragent.core.engine.NodeContext;
import com.remasteragent.core.engine.NodeExecutor;
import com.remasteragent.core.engine.NodeOutcome;
import com.remasteragent.core.engine.PomUpgradeDecision;
import com.remasteragent.core.progress.ProgressPublisher;
import com.remasteragent.core.progress.RetryProgressListener;
import com.remasteragent.core.rag.SourceFiles;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.core.trace.TraceTracer;
import com.remasteragent.llm.config.LlmProperties;
import com.remasteragent.llm.plan.MigrationPlanner;
import com.remasteragent.llm.plan.PlanCommand;
import com.remasteragent.llm.plan.PlanOutcome;
import com.remasteragent.tools.ast.JavaSourceAnalyzer;
import com.remasteragent.tools.ast.JdkRemovalScanner;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PLAN 节点：看一眼整个工程，决定要迁移哪些文件，并把决定翻译成 DAG 节点。
 *
 * <h2>它为什么能「动态扩图」</h2>
 * <p>{@code DagScheduler} 的调度循环每轮都重新问一次「哪些节点就绪」，而不是预先算好拓扑序。
 * 这正是为 PLAN 这样的节点准备的：它在<b>运行期</b>往 DAG 里插入后续的 REWRITE / VERIFY，
 * 下一轮调度就能看见它们。如果调度器依赖预先算好的拓扑序，这种动态扩图就无从谈起 ——
 * 这也是本项目坚持自研编排、不用现成链路编排框架的原因之一。
 *
 * <h2>兜底：计划为空也要验证一次入口文件</h2>
 * <p>如果模型认为「没有文件需要迁移」，这里仍会铺一个入口文件的 REWRITE/VERIFY。
 * 理由：入口文件是用户明确要求迁移的目标，即使模型判断它已现代化，也应该真的编译验证一次 ——
 * 否则任务会变成「什么都没做就报成功」，那是比失败更糟的假阳性。
 */
@Component
public class PlanNode implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlanNode.class);

    public static final String NODE_KEY = "plan";

    /** 送进规划的候选文件上限 —— 规划只看结构，过大的清单既费 token 也降低判断质量。 */
    private static final int MAX_PLAN_FILES = 60;

    /** 每个文件带上的符号数上限，避免单个大类的符号把 prompt 撑爆。 */
    private static final int MAX_SYMBOLS_PER_FILE = 20;

    private final TaskStore taskStore;
    private final MigrationPlanner planner;
    private final LlmProperties llmProperties;
    private final CoreProperties coreProperties;
    private final ProgressPublisher progressPublisher;
    private final TraceTracer tracer;
    private final JdkRemovalScanner removalScanner;

    /**
     * 容器里有没有 POM_REWRITE 执行器。
     *
     * <p>与本类选择拓扑的既有原则一致（见 {@code DagScheduler.gateEnabled}）：
     * <b>组件不在就退化，不铺出执行不了的节点</b>。铺了而没人执行，节点会被判 FAILED、
     * 任务跟着失败，而报错信息只会说「没有可用的节点执行器」——那是部署问题，
     * 不该伪装成业务失败。
     */
    private final boolean pomRewriteAvailable;

    /** 单测入口：不发布进度、不埋点，且假定没有 POM_REWRITE 执行器（退化为阶段 2 拓扑）。 */
    public PlanNode(TaskStore taskStore, MigrationPlanner planner, LlmProperties llmProperties,
                    CoreProperties coreProperties) {
        this(taskStore, planner, llmProperties, coreProperties, ProgressPublisher.NOOP, TraceTracer.NOOP, false,
                JdkRemovalScanner.create());
    }

    /**
     * 生产入口。进度发布口用 {@link ObjectProvider} 取（取不到退化成 NOOP），
     * 与 {@code DagScheduler} / {@code RewriteNode} 保持同一种取法。
     * 移除扫描器同样用 {@link ObjectProvider} 取，取不到退化成一个新实例（它是无状态的纯工具）。
     */
    @Autowired
    public PlanNode(TaskStore taskStore, MigrationPlanner planner, LlmProperties llmProperties,
                    CoreProperties coreProperties,
                    ObjectProvider<ProgressPublisher> publisherProvider,
                    ObjectProvider<PomRewriteNode> pomRewriteProvider,
                    TraceTracer tracer,
                    ObjectProvider<JdkRemovalScanner> scannerProvider) {
        this(taskStore, planner, llmProperties, coreProperties,
                publisherProvider == null
                        ? ProgressPublisher.NOOP
                        : publisherProvider.getIfAvailable(() -> ProgressPublisher.NOOP),
                tracer == null ? TraceTracer.NOOP : tracer,
                pomRewriteProvider != null && pomRewriteProvider.getIfAvailable() != null,
                scannerProvider == null
                        ? JdkRemovalScanner.create()
                        : scannerProvider.getIfAvailable(JdkRemovalScanner::create));
    }

    private PlanNode(TaskStore taskStore, MigrationPlanner planner, LlmProperties llmProperties,
                     CoreProperties coreProperties, ProgressPublisher progressPublisher,
                     TraceTracer tracer, boolean pomRewriteAvailable, JdkRemovalScanner removalScanner) {
        this.taskStore = taskStore;
        this.planner = planner;
        this.llmProperties = llmProperties;
        this.coreProperties = coreProperties;
        this.progressPublisher = progressPublisher;
        this.tracer = tracer;
        this.pomRewriteAvailable = pomRewriteAvailable;
        this.removalScanner = removalScanner;
    }

    @Override
    public NodeType type() {
        return NodeType.PLAN;
    }

    @Override
    public NodeOutcome execute(NodeContext context) {
        // 规划之前先把整库扫一遍：哪些文件 import 了目标 JDK 已删除的包（javax.xml.ws、javax.annotation…）。
        // 这份风险地图有两处用途：① 作为「风险信号」喂给规划器，让它知道哪些文件必须迁移；
        // ② 作为确定性安全网——模型漏挑的风险文件，由它强制补进清单（见下方 mergeRiskFiles）。
        JdkRemovalScanner.ProjectRemovalRisks removalRisks =
                removalScanner.scan(context.workspace());
        if (!removalRisks.filesWithBlockingRisk().isEmpty()) {
            log.info("全工程扫描发现 {} 个文件含 JDK 已移除的 import：{}",
                    removalRisks.filesWithBlockingRisk().size(), removalRisks.filesWithBlockingRisk());
        }

        List<PlanCommand.FileSummary> files;
        try {
            files = collectFiles(context.workspace(), removalRisks);
        } catch (Exception e) {
            return NodeOutcome.fail("收集工程文件失败: " + e.getMessage());
        }
        if (files.isEmpty()) {
            return NodeOutcome.fail("工作目录里没有可分析的 Java 源文件");
        }

        PlanCommand command = new PlanCommand(
                context.task().entryFile(), context.task().targetJdk(), files,
                new RetryProgressListener(progressPublisher, context.task().id(),
                        context.node().id(), context.node().nodeKey(), context.attempt(), "规划"));

        PlanOutcome outcome;
        Span llmSpan = tracer.startLlm(context.task().id(), context.node().id(), NodeType.PLAN.name());
        try {
            outcome = planner.plan(command);
        } catch (MigrationPlanner.PlanFailedException e) {
            TraceTracer.endError(llmSpan, e.getMessage());
            return NodeOutcome.fail("规划产出不可用: " + e.getMessage());
        } catch (Exception e) {
            TraceTracer.endException(llmSpan, e);
            return NodeOutcome.fail("调用大模型规划失败: " + e);
        }
        llmSpan.setAttribute("llm.model", outcome.model() == null ? "unknown" : outcome.model());
        llmSpan.setAttribute("llm.prompt_tokens", (long) outcome.promptTokens());
        llmSpan.setAttribute("llm.completion_tokens", (long) outcome.completionTokens());
        llmSpan.setAttribute("llm.latency_ms", outcome.latencyMs());
        llmSpan.setAttribute("llm.cost", estimateCost(outcome));
        TraceTracer.endOk(llmSpan);

        recordLlmCall(context, outcome);

        List<String> targets = outcome.plan().filePaths();
        if (targets.isEmpty()) {
            targets = List.of(context.task().entryFile());
            log.info("规划未列出任何文件，兜底为入口文件: {}", context.task().entryFile());
        }

        // 确定性安全网：模型可能漏掉「含 JDK 已移除 import」的文件（如老 SOAP handler、@Resource 服务类）。
        // 这些文件不修整个工程就编不过，必须由规则强制补进清单，而不是听任模型判断。
        // 入口文件若命中移除风险但模型没选，也会在这里被补回来。
        List<String> merged = mergeRiskFiles(targets, removalRisks);
        if (merged.size() != targets.size()) {
            List<String> added = new ArrayList<>(merged);
            added.removeAll(targets);
            log.warn("安全网强制补入 {} 个模型漏掉的移除风险文件: {}", added.size(), added);
            targets = merged;
        }

        long planNodeId = context.node().id();
        // 编译级别低于目标 JDK 时，先插一个 POM_REWRITE，所有文件链改挂它后面。
        // 顺序不能反：模型产出的 record / 文本块在 release=8 下会被 javac 拒收，
        // 先改代码后改 pom 会让每个文件都「改对了但编译不过」，白烧整轮回退配额。
        long chainBase = planNodeId;
        String pomReason = PomUpgradeDecision.reason(context.workspace(), context.task().targetJdk())
                .orElse(null);
        if (pomReason != null && pomRewriteAvailable) {
            chainBase = taskStore.insertNode(context.task().id(), PomRewriteNode.NODE_KEY,
                    NodeType.POM_REWRITE, List.of(planNodeId), 0);
            log.info("已插入编译级别升级节点（{}），{} 个文件链改挂它之后", pomReason, targets.size());
        } else if (pomReason != null) {
            log.warn("工程需要升级编译级别（{}），但容器里没有 POM_REWRITE 执行器，已跳过 —— "
                    + "后续改写若使用 Java 17+ 语法将编译失败", pomReason);
        }

        // 门禁开启时，每个文件的链条铺成 REWRITE → GATE → VERIFY：
        // 改完先停下等人看一眼补丁，批准后才进沙箱验证。
        // GATE 节点与 REWRITE/VERIFY 一样在**运行期**插入，随后由调度循环发现并执行。
        boolean gate = coreProperties != null && coreProperties.requireRewriteApproval();
        for (String filePath : targets) {
            long rewriteId = taskStore.insertNode(context.task().id(),
                    RewriteNode.nodeKey(filePath), NodeType.REWRITE, List.of(chainBase), 0);
            long verifyDependency = rewriteId;
            if (gate) {
                verifyDependency = taskStore.insertNode(context.task().id(),
                        GateNode.nodeKey(filePath), NodeType.GATE, List.of(rewriteId), 0);
            }
            taskStore.insertNode(context.task().id(),
                    VerifyNode.nodeKey(filePath), NodeType.VERIFY, List.of(verifyDependency), 0);
        }
        log.info("规划完成: {} 个文件待迁移 → {}（门禁 {}）",
                targets.size(), targets, gate ? "开启" : "关闭");

        return NodeOutcome.ok(effectivePlan(outcome.plan(), targets));
    }

    /** 把 checkpoint 里的计划对齐到「实际会执行的文件」，避免评审看到的与实际不符。 */
    private static PlanResult effectivePlan(PlanResult raw, List<String> targets) {
        Map<String, String> rationaleByPath = new LinkedHashMap<>();
        if (raw.steps() != null) {
            for (PlanResult.PlanStep step : raw.steps()) {
                if (step.filePath() != null && !step.filePath().isBlank()) {
                    rationaleByPath.putIfAbsent(step.filePath(), step.rationale());
                }
            }
        }
        List<PlanResult.PlanStep> steps = new ArrayList<>(targets.size());
        for (String filePath : targets) {
            steps.add(new PlanResult.PlanStep(filePath, rationaleByPath.getOrDefault(filePath, "")));
        }
        return new PlanResult(raw.summary(), steps);
    }

    /** 读工作目录里的源码，抽出「路径 + 主类型 + 符号 + 移除风险」交给规划模型。 */
    private List<PlanCommand.FileSummary> collectFiles(Path workspace,
                                                      JdkRemovalScanner.ProjectRemovalRisks removalRisks)
            throws IOException {
        List<Path> javaFiles = SourceFiles.listJavaFiles(workspace, MAX_PLAN_FILES);
        List<PlanCommand.FileSummary> summaries = new ArrayList<>(javaFiles.size());
        for (Path file : javaFiles) {
            String relative = SourceFiles.relativePath(workspace, file);
            try {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                JavaSourceAnalyzer.ParsedHeader header = JavaSourceAnalyzer.parseHeader(source);
                summaries.add(new PlanCommand.FileSummary(
                        relative,
                        header.primaryType(),
                        header.symbols().stream().limit(MAX_SYMBOLS_PER_FILE).toList(),
                        removalRisks.flagsFor(relative)));
            } catch (Exception e) {
                // 解析不了的文件不进候选：规划拿不到它的结构，硬塞进去只会让规划质量下降
                log.debug("规划时跳过无法解析的文件 {}: {}", relative, e.getMessage());
            }
        }
        return summaries;
    }

    /**
     * 把「含 JDK 已移除 import」的风险文件强制并入迁移清单。
     *
     * <p>模型按符号/文件名判断「要不要改」，天然会漏掉那些<b>只有 import 一行旧 API、正文早已现代化</b>的文件
     * （典型如只 {@code import javax.xml.ws.RequestWrapper} 却已注释掉用法的 handler，或 {@code @Resource} 注入的服务类）。
     * 这类文件一旦漏掉，VERIFY 阶段整个工程编译失败，而失败点却落在模型「没打算改」的文件上，
     * 既无法归因也不会收敛。所以这里用规则兜底：风险地图里出现过的文件，一个都不能少。
     *
     * @param proposed     模型建议的迁移文件清单（顺序保留）
     * @param removalRisks 全工程移除风险地图
     * @return 去重后的最终清单：先模型顺序，再补入模型漏掉的风险文件
     */
    static List<String> mergeRiskFiles(List<String> proposed,
                                       JdkRemovalScanner.ProjectRemovalRisks removalRisks) {
        List<String> merged = new ArrayList<>(proposed);
        for (String riskFile : removalRisks.filesWithBlockingRisk()) {
            boolean already = merged.stream().anyMatch(p -> p.replace('\\', '/').equalsIgnoreCase(riskFile));
            if (!already) {
                merged.add(riskFile);
            }
        }
        return merged;
    }

    /** 按单价表估算成本 —— 与 span 上的 {@code llm.cost} 共用，避免两处算出两个数。 */
    private double estimateCost(PlanOutcome outcome) {
        return outcome.promptTokens() / 1_000_000d * llmProperties.inputPricePerMillion()
                + outcome.completionTokens() / 1_000_000d * llmProperties.outputPricePerMillion();
    }

    private void recordLlmCall(NodeContext context, PlanOutcome outcome) {
        taskStore.recordLlmCall(new LlmCallRecord(
                null,
                context.task().id(),
                context.node().id(),
                outcome.model(),
                NodeType.PLAN.name(),
                outcome.promptTokens(),
                outcome.completionTokens(),
                estimateCost(outcome),
                outcome.latencyMs(),
                // 当前 span 的 trace id —— 这一行账因此能 join 回整条链路（见 RewriteNode 同处说明）
                TraceTracer.currentTraceId(),
                Instant.now()));
    }
}
