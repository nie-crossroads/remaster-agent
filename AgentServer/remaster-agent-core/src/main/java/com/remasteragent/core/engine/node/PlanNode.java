package com.remasteragent.core.engine.node;

import com.remasteragent.common.agent.PlanResult;
import com.remasteragent.common.domain.LlmCallRecord;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.core.config.CoreProperties;
import com.remasteragent.core.engine.NodeContext;
import com.remasteragent.core.engine.NodeExecutor;
import com.remasteragent.core.engine.NodeOutcome;
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

    /** 单测入口：不发布进度、不埋点。 */
    public PlanNode(TaskStore taskStore, MigrationPlanner planner, LlmProperties llmProperties,
                    CoreProperties coreProperties) {
        this(taskStore, planner, llmProperties, coreProperties, ProgressPublisher.NOOP, TraceTracer.NOOP);
    }

    /**
     * 生产入口。进度发布口用 {@link ObjectProvider} 取（取不到退化成 NOOP），
     * 与 {@code DagScheduler} / {@code RewriteNode} 保持同一种取法。
     */
    @Autowired
    public PlanNode(TaskStore taskStore, MigrationPlanner planner, LlmProperties llmProperties,
                    CoreProperties coreProperties,
                    ObjectProvider<ProgressPublisher> publisherProvider,
                    TraceTracer tracer) {
        this(taskStore, planner, llmProperties, coreProperties,
                publisherProvider == null
                        ? ProgressPublisher.NOOP
                        : publisherProvider.getIfAvailable(() -> ProgressPublisher.NOOP),
                tracer == null ? TraceTracer.NOOP : tracer);
    }

    private PlanNode(TaskStore taskStore, MigrationPlanner planner, LlmProperties llmProperties,
                     CoreProperties coreProperties, ProgressPublisher progressPublisher,
                     TraceTracer tracer) {
        this.taskStore = taskStore;
        this.planner = planner;
        this.llmProperties = llmProperties;
        this.coreProperties = coreProperties;
        this.progressPublisher = progressPublisher;
        this.tracer = tracer;
    }

    @Override
    public NodeType type() {
        return NodeType.PLAN;
    }

    @Override
    public NodeOutcome execute(NodeContext context) {
        List<PlanCommand.FileSummary> files;
        try {
            files = collectFiles(context.workspace());
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

        long planNodeId = context.node().id();
        // 门禁开启时，每个文件的链条铺成 REWRITE → GATE → VERIFY：
        // 改完先停下等人看一眼补丁，批准后才进沙箱验证。
        // GATE 节点与 REWRITE/VERIFY 一样在**运行期**插入，随后由调度循环发现并执行。
        boolean gate = coreProperties != null && coreProperties.requireRewriteApproval();
        for (String filePath : targets) {
            long rewriteId = taskStore.insertNode(context.task().id(),
                    RewriteNode.nodeKey(filePath), NodeType.REWRITE, List.of(planNodeId), 0);
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

    /** 读工作目录里的源码，抽出「路径 + 主类型 + 符号」交给规划模型。 */
    private List<PlanCommand.FileSummary> collectFiles(Path workspace) throws IOException {
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
                        header.symbols().stream().limit(MAX_SYMBOLS_PER_FILE).toList()));
            } catch (Exception e) {
                // 解析不了的文件不进候选：规划拿不到它的结构，硬塞进去只会让规划质量下降
                log.debug("规划时跳过无法解析的文件 {}: {}", relative, e.getMessage());
            }
        }
        return summaries;
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
