package com.remasteragent.core.engine.node;

import com.remasteragent.common.agent.PlanResult;
import com.remasteragent.common.domain.LlmCallRecord;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.core.config.CoreProperties;
import com.remasteragent.core.engine.NodeContext;
import com.remasteragent.core.engine.NodeExecutor;
import com.remasteragent.core.engine.NodeOutcome;
import com.remasteragent.core.engine.PomUpgradeDecision;
import com.remasteragent.tools.pom.JakartaArtifactCatalog;
import com.remasteragent.tools.pom.PomDependencyUpgrader;
import com.remasteragent.tools.pom.SpringBoot3DependencyCatalog;
import com.remasteragent.core.progress.ProgressPublisher;
import com.remasteragent.core.progress.RetryProgressListener;
import com.remasteragent.core.rag.SourceFiles;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.tools.pom.SpringBootParentUpgrader;
import com.remasteragent.core.trace.TraceTracer;
import com.remasteragent.llm.config.LlmProperties;
import com.remasteragent.llm.plan.MigrationPlanner;
import com.remasteragent.llm.plan.PlanCommand;
import com.remasteragent.llm.plan.PlanOutcome;
import com.remasteragent.tools.ast.JavaSourceAnalyzer;
import com.remasteragent.tools.ast.JdkRemovalScanner;
import com.remasteragent.tools.ast.LegacyImportDetector;
import com.remasteragent.tools.ast.Spring3BreakingApiScanner;
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
import java.util.Set;
import java.util.stream.Collectors;

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
    private final boolean dependencyUpgradeAvailable;
    private final boolean parentUpgradeAvailable;

    /**
     * 容器里有没有 POM_REWRITE 执行器。
     *
     * <p>与本类选择拓扑的既有原则一致（见 {@code DagScheduler.gateEnabled}）：
     * <b>组件不在就退化，不铺出执行不了的节点</b>。铺了而没人执行，节点会被判 FAILED、
     * 任务跟着失败，而报错信息只会说「没有可用的节点执行器」——那是部署问题，
     * 不该伪装成业务失败。
     */
    private final boolean pomRewriteAvailable;

    /** 单测入口：不发布进度、不埋点，且假定没有 POM_REWRITE / PARENT_UPGRADE / DEPENDENCY_UPGRADE 执行器（退化为阶段 2 拓扑）。 */
    public PlanNode(TaskStore taskStore, MigrationPlanner planner, LlmProperties llmProperties,
                    CoreProperties coreProperties) {
        this(taskStore, planner, llmProperties, coreProperties, ProgressPublisher.NOOP, TraceTracer.NOOP, false,
                JdkRemovalScanner.create(), false, false);
    }

    /**
     * 生产入口。进度发布口用 {@link ObjectProvider} 取（取不到退化成 NOOP），
     * 与 {@code DagScheduler} / {@code RewriteNode} 保持同一种取法。
     * 移除扫描器同样用 {@link ObjectProvider} 取，取不到退化成一个新实例（它是无状态的纯工具）。
     * 依赖升级执行器用 {@link ObjectProvider} 取：取不到就退化成「不插该节点」，不铺出执行不了的节点。
     */
    @Autowired
    public PlanNode(TaskStore taskStore, MigrationPlanner planner, LlmProperties llmProperties,
                    CoreProperties coreProperties,
                    ObjectProvider<ProgressPublisher> publisherProvider,
                    ObjectProvider<PomRewriteNode> pomRewriteProvider,
                    ObjectProvider<ParentUpgradeNode> parentUpgradeProvider,
                    ObjectProvider<DependencyUpgradeNode> dependencyUpgradeProvider,
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
                        : scannerProvider.getIfAvailable(JdkRemovalScanner::create),
                dependencyUpgradeProvider != null && dependencyUpgradeProvider.getIfAvailable() != null,
                parentUpgradeProvider != null && parentUpgradeProvider.getIfAvailable() != null);
    }

    private PlanNode(TaskStore taskStore, MigrationPlanner planner, LlmProperties llmProperties,
                     CoreProperties coreProperties, ProgressPublisher progressPublisher,
                     TraceTracer tracer, boolean pomRewriteAvailable, JdkRemovalScanner removalScanner,
                     boolean dependencyUpgradeAvailable, boolean parentUpgradeAvailable) {
        this.taskStore = taskStore;
        this.planner = planner;
        this.llmProperties = llmProperties;
        this.coreProperties = coreProperties;
        this.progressPublisher = progressPublisher;
        this.tracer = tracer;
        this.pomRewriteAvailable = pomRewriteAvailable;
        this.removalScanner = removalScanner;
        this.dependencyUpgradeAvailable = dependencyUpgradeAvailable;
        this.parentUpgradeAvailable = parentUpgradeAvailable;
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

        // 模型有概率「把文件列入迁移清单、却没给理由」（实测博客工程 #79：24 个文件里 16 个 rationale 为空）。
        // 评审界面若直接显示空白，面试官会以为流程卡死、也不敢批准。
        // 兜底：用确定性安全网的三把尺子（JDK 已移除 import / javax→jakarta / SB2→3 破坏性 API）
        // 给<b>每一个</b>理由为空的文件补一句能解释的原因；三把尺子都解释不了的，也给一句诚实的元描述。
        // 这把尺子的口径与下方「强制补入清单」的三种安全网完全一致，只是用途从「决定要不要改」
        // 扩展到「解释为什么改」。下面的 removalFlagsNorm / legacySet / breakingSet 就是给
        // effectivePlan 用的归一化查表结构（统一小写 + 斜杠，避免大小写/分隔符对不上）。
        Map<String, List<String>> removalFlagsNorm = new LinkedHashMap<>();
        if (removalRisks != null) {
            removalRisks.byFile().forEach((k, v) -> {
                List<String> flags = v.stream().map(JdkRemovalScanner.RemovalRisk::humanFlag).toList();
                removalFlagsNorm.putIfAbsent(normalizePath(k).toLowerCase(), flags);
            });
        }

        // 安全网第一层：JDK 已移除的 import（javax.xml.ws、javax.annotation…）
        List<String> merged = mergeRiskFiles(targets, removalRisks);

        // 安全网第二层：javax→jakarta 命名空间（javax.validation / javax.servlet / javax.persistence…）
        List<String> legacyFiles = scanLegacyImportFiles(context.workspace());
        Set<String> legacySet = legacyFiles.stream()
                .map(f -> normalizePath(f).toLowerCase()).collect(Collectors.toSet());
        List<String> afterLegacy = new ArrayList<>(merged);
        for (String file : legacyFiles) {
            if (!containsPath(afterLegacy, file)) {
                afterLegacy.add(file);
            }
        }
        merged = afterLegacy;

        // 安全网第三层：Spring Boot 2→3 破坏性 API（如 RestTemplateConfig 用的 HttpComponentsClientHttpRequestFactory）
        Map<String, List<Spring3BreakingApiScanner.Finding>> breakingHits =
                Spring3BreakingApiScanner.scanProject(context.workspace());
        Set<String> breakingSet = breakingHits.keySet().stream()
                .map(f -> normalizePath(f).toLowerCase()).collect(Collectors.toSet());
        merged = mergeBreakingApiFiles(merged, breakingHits);

        if (merged.size() != targets.size()) {
            List<String> added = new ArrayList<>(merged);
            added.removeAll(targets);
            log.warn("安全网强制补入 {} 个模型漏掉的待迁移文件: {}", added.size(), added);
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

        // 工程声明了「大版本 < 3 的 spring-boot-starter-parent」时，在 POM_REWRITE 之后插一个 PARENT_UPGRADE，
        // 把 BOM 升到 jakarta 命名空间（Spring Boot 2.7 → 3.x）。它是第④层（注入 jakarta 依赖）能成立的前提——
        // 不先升 parent，注入的 jakarta.* 依赖只会跟旧 SB2 的 javax 栈冲突。仅当执行器可用时才插。
        long parentChainBase = chainBase;
        boolean parentNeeded = projectNeedsParentUpgrade(context.workspace());
        if (parentNeeded && parentUpgradeAvailable) {
            parentChainBase = taskStore.insertNode(context.task().id(), ParentUpgradeNode.NODE_KEY,
                    NodeType.PARENT_UPGRADE, List.of(chainBase), 0);
            log.info("已插入 Spring Boot parent 升级节点（工程用到需升级的 spring-boot-starter-parent），"
                    + " {} 个文件链改挂它之后", targets.size());
        } else if (parentNeeded) {
            log.warn("工程需升级 spring-boot-starter-parent 到 3.x，但容器里没有 PARENT_UPGRADE 执行器，已跳过 —— "
                    + "后续 jakarta 依赖注入与源码改写将因命名空间不匹配而编译失败");
        }

        // 工程用到了「从 JDK 移除、需 jakarta 依赖」的包，或含有需坐标/版本规范化的旧依赖时，
        // 在 PARENT_UPGRADE 之后插一个 DEPENDENCY_UPGRADE，让改写后的 jakarta.* import 在 VERIFY 里能编过、
        // 旧坐标（如 mysql-connector-java）也换成 SB3 形态。它必须在 REWRITE 之前执行（依赖顺序见下）。
        // 只有确实会需要、且执行器可用时才插：否则既空跑又白占一条 DAG。
        long dependencyChainBase = parentChainBase;
        boolean jakartaNeeded = JakartaArtifactCatalog.anyArtifactNeeded(removalRisks);
        boolean coordNeeded = pomNeedsDependencyUpgrade(context.workspace());
        // 第三类缺口：框架破坏性 API 换掉的底层库（如 Spring 6 的
        // HttpComponentsClientHttpRequestFactory 需要 httpclient5）。源码改得再对，classpath 里没这个
        // 库也编不过 —— 所以它和 jakarta 注入一样属于「依赖侧必须补」的部分。
        boolean sb3Needed = SpringBoot3DependencyCatalog.anyNeeded(breakingHits);
        if ((jakartaNeeded || coordNeeded || sb3Needed) && dependencyUpgradeAvailable) {
            dependencyChainBase = taskStore.insertNode(context.task().id(), DependencyUpgradeNode.NODE_KEY,
                    NodeType.DEPENDENCY_UPGRADE, List.of(parentChainBase), 0);
            log.info("已插入依赖升级节点（jakarta 依赖={}，坐标/版本规范化={}，SB3 破坏性 API 依赖={}），"
                    + " {} 个文件链改挂它之后", jakartaNeeded, coordNeeded, sb3Needed, targets.size());
        } else if (jakartaNeeded || coordNeeded || sb3Needed) {
            log.warn("工程需升级依赖才能编译（jakarta 依赖={}，坐标/版本规范化={}，SB3 破坏性 API 依赖={}），"
                    + "但容器里没有 DEPENDENCY_UPGRADE 执行器，已跳过", jakartaNeeded, coordNeeded, sb3Needed);
        }

        // 批语义：先铺出所有文件的 REWRITE（彼此独立、互不依赖），再铺「一条」整仓 VERIFY，
        // 它依赖全部 REWRITE（门禁开启时依赖全部 GATE）。这样 VERIFY 跑的是整仓 mvn test，
        // 但只在「所有文件都改完」之后才跑一次 —— 避免「改了 f1、f2 还 javax，于是整仓编译失败、
        // 反复重跑 f1」的不收敛死循环（详见 DagScheduler.planRetry 的批语义说明）。
        // 门禁开启时，每个文件仍铺一道「改写后人工门禁」，但整仓 VERIFY 统一接在所有门禁之后。
        boolean gate = coreProperties != null && coreProperties.requireRewriteApproval();
        List<Long> verifyDeps = new ArrayList<>();
        for (String filePath : targets) {
            long rewriteId = taskStore.insertNode(context.task().id(),
                    RewriteNode.nodeKey(filePath), NodeType.REWRITE, List.of(dependencyChainBase), 0);
            if (gate) {
                verifyDeps.add(taskStore.insertNode(context.task().id(),
                        GateNode.nodeKey(filePath), NodeType.GATE, List.of(rewriteId), 0));
            } else {
                verifyDeps.add(rewriteId);
            }
        }
        taskStore.insertNode(context.task().id(),
                VerifyNode.NODE_KEY, NodeType.VERIFY, verifyDeps, 0);
        log.info("规划完成（批语义）: {} 个文件待迁移 → {}（门禁 {}，单条整仓 VERIFY 依赖全部改写）",
                targets.size(), targets, gate ? "开启" : "关闭");

        return NodeOutcome.ok(effectivePlan(outcome.plan(), targets, removalFlagsNorm, legacySet, breakingSet));
    }

    /**
     * 把 checkpoint 里的计划对齐到「实际会执行的文件」，避免评审看到的与实际不符。
     *
     * <p>每个文件的理由优先级：模型给的（最具体）&gt; 确定性扫描能解释的原因（JDK 已移除 import /
     * javax→jakarta / SB2→3 破坏性 API）&gt; 诚实的元描述兜底。
     * 实测博客工程里模型会把文件列入清单却留空 rationale，若不兜底，评审界面会出现
     * 「要改但没理由」的空白，反而让人不敢批准。确定性扫描的口径与安全网完全一致，
     * 因此能给出可信的原因；三把尺子都解释不了的文件，也给一句诚实的元描述，不再留白。
     */
    private static PlanResult effectivePlan(PlanResult raw, List<String> targets,
                                           Map<String, List<String>> removalFlagsNorm,
                                           Set<String> legacySet, Set<String> breakingSet) {
        Map<String, String> modelRationaleByPath = new LinkedHashMap<>();
        if (raw.steps() != null) {
            for (PlanResult.PlanStep step : raw.steps()) {
                if (step.filePath() != null && !step.filePath().isBlank()) {
                    modelRationaleByPath.putIfAbsent(normalizePath(step.filePath()).toLowerCase(),
                            step.rationale());
                }
            }
        }
        List<PlanResult.PlanStep> steps = new ArrayList<>(targets.size());
        for (String filePath : targets) {
            String modelRationale = modelRationaleByPath.get(normalizePath(filePath).toLowerCase());
            String rationale;
            if (modelRationale != null && !modelRationale.isBlank()) {
                rationale = modelRationale;
            } else {
                String derived = scannerReasonFor(filePath, removalFlagsNorm, legacySet, breakingSet);
                rationale = (derived != null && !derived.isBlank())
                        ? derived
                        : "模型已将该文件列入本次迁移范围，但未附具体理由；确定性扫描未能定位具体遗留点，"
                          + "该文件仍属本次迁移范围，请人工确认其必要性。";
            }
            steps.add(new PlanResult.PlanStep(filePath, rationale));
        }
        return new PlanResult(raw.summary(), steps);
    }

    /**
     * 用确定性安全网的三把尺子给一个「理由为空」的文件补原因。
     * 优先级与 {@link #mergeRiskFiles} / 第二层安全网 / 第三层安全网一致：
     * JDK 已移除 import &gt; javax→jakarta &gt; SB2→3 破坏性 API。
     * 三把尺子都解释不了返回 {@code null}，交由 {@link #effectivePlan} 给一句诚实的元描述兜底。
     */
    private static String scannerReasonFor(String filePath,
                                          Map<String, List<String>> removalFlagsNorm,
                                          Set<String> legacySet, Set<String> breakingSet) {
        String norm = normalizePath(filePath).toLowerCase();
        List<String> flags = removalFlagsNorm.get(norm);
        if (flags != null && !flags.isEmpty()) {
            return "该文件仍 import 了 JDK 已移除的包（如 " + String.join("、", flags)
                    + "），不迁移会导致整个工程编译失败（确定性扫描确认）。";
        }
        if (legacySet != null && legacySet.contains(norm)) {
            return "该文件仍含 javax→jakarta 命名空间的遗留 import（如 javax.validation / javax.servlet），"
                    + "必须随整仓升级一起迁移（确定性扫描确认）。";
        }
        if (breakingSet != null && breakingSet.contains(norm)) {
            return "该文件使用了 Spring Boot 2→3 的破坏性 API，底层实现已变更，必须改写（确定性扫描确认）。";
        }
        return null;
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
     * 扫描工作目录，找出所有仍含「需要迁移的旧 import」的 java 文件（相对路径）。
     *
     * <p>判定口径与 {@link LegacyImportDetector} 完全一致（JDK 已移除包 + javax→jakarta 白名单，
     * 且排除 javax.crypto / javax.sql 这类 JDK 内建包）。用同一把尺子是关键：
     * 否则会出现「文件漏改但没进清单、改写后校验又管不到它」的真空区——
     * 规划认为不用改、改写校验认为改完了，最后一起在整仓编译时才炸出来。
     */
    private static List<String> scanLegacyImportFiles(Path workspace) {
        List<String> hits = new ArrayList<>();
        try {
            // 这里刻意不复用 MAX_PLAN_FILES —— 那个上限是给规划模型的 prompt 省上下文用的（默认 60 个），
            // 但真实工程常有上百个文件（实测博客工程 125 个），按它截断会让第 61 个之后的文件永远进不了清单。
            // 确定性扫描必须看全量，否则安全网自己就成了漏网之源。
            for (Path file : SourceFiles.listJavaFiles(workspace, Integer.MAX_VALUE)) {
                String source;
                try {
                    source = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                if (LegacyImportDetector.hasLeftover(source)) {
                    hits.add(SourceFiles.relativePath(workspace, file));
                }
            }
        } catch (IOException e) {
            log.warn("扫描遗留 import 文件失败: {}", e.getMessage());
        }
        return hits;
    }

    /**
     * 工程是否整体需要升级 Spring Boot parent（任意一份 pom 声明了 spring-boot-starter-parent 且大版本 < 3）。
     *
     * <p>只扫 pom 的 parent 声明，不读源码——这是阶段 5 第②层的前置判定，决定了要不要插 PARENT_UPGRADE 节点。
     */
    private static boolean projectNeedsParentUpgrade(Path workspace) {
        try {
            for (Path pom : SourceFiles.listPomFiles(workspace)) {
                String xml = Files.readString(pom, StandardCharsets.UTF_8);
                if (SpringBootParentUpgrader.needsUpgrade(xml)) {
                    return true;
                }
            }
        } catch (IOException e) {
            log.warn("扫描 pom 判断是否需要升级 spring-boot-starter-parent 失败: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 工程是否含有需坐标/版本规范化的旧依赖（如 {@code mysql:mysql-connector-java}、
     * {@code mybatis-spring-boot-starter} 2.3.x）。这是阶段 5 第③/④层的前置判定，
     * 决定了要不要插 DEPENDENCY_UPGRADE 节点（即使工程没用到任何需 jakarta 依赖的 API）。
     */
    private static boolean pomNeedsDependencyUpgrade(Path workspace) {
        try {
            for (Path pom : SourceFiles.listPomFiles(workspace)) {
                String xml = Files.readString(pom, StandardCharsets.UTF_8);
                if (PomDependencyUpgrader.anyUpgradeNeeded(xml)) {
                    return true;
                }
            }
        } catch (IOException e) {
            log.warn("扫描 pom 判断是否需要依赖坐标/版本升级失败: {}", e.getMessage());
        }
        return false;
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

    /**
     * 安全网第三层：把命中「Spring Boot 2→3 破坏性 API」的文件强制补进清单。
     *
     * <p>与 {@link #mergeRiskFiles} 同构、但补的是另一类漏网：那些文件<b>一个 javax import 都没有</b>，
     * 命名空间那两把尺子都看不见它们，却因为 Spring 6 换掉了底层实现 / 删掉了适配器而必须改。
     * 实测博客工程 {@code RestTemplateConfig.java} 就是这样整仓编译卡死的。</p>
     *
     * <p>抽成静态方法而不内联，是为了能像 {@link #mergeRiskFiles} 一样被确定性地单测 ——
     * 「哪些文件必须进清单」是这个项目最该被钉死、也最难靠端到端验证的一段逻辑。</p>
     */
    static List<String> mergeBreakingApiFiles(List<String> proposed,
                                              Map<String, List<Spring3BreakingApiScanner.Finding>> byFile) {
        List<String> merged = new ArrayList<>(proposed);
        for (String file : byFile.keySet()) {
            boolean already = merged.stream().anyMatch(p -> p.replace('\\', '/').equalsIgnoreCase(file));
            if (!already) {
                merged.add(file);
            }
        }
        return merged;
    }

    /** 路径归一化：反斜杠统一成斜杠，便于跨平台比对与做 map key。 */
    private static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    /** 大小写不敏感地判断清单是否已含某路径（安全网补文件时用同一口径比对）。 */
    private static boolean containsPath(List<String> list, String path) {
        String norm = normalizePath(path);
        return list.stream().anyMatch(p -> normalizePath(p).equals(norm));
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
