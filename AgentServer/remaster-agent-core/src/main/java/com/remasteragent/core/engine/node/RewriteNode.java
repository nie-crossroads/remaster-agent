package com.remasteragent.core.engine.node;

import com.remasteragent.common.agent.AnalyzeResult;
import com.remasteragent.common.agent.RewriteProposal;
import com.remasteragent.common.agent.RewriteResult;
import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.LlmCallRecord;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.rag.RetrievedChunk;
import com.remasteragent.core.codec.JsonCodec;
import com.remasteragent.core.engine.NodeContext;
import com.remasteragent.core.engine.NodeExecutor;
import com.remasteragent.core.engine.NodeOutcome;
import com.remasteragent.core.engine.ProposalGuardrail;
import com.remasteragent.core.progress.ProgressPublisher;
import com.remasteragent.core.progress.RetryProgressListener;
import com.remasteragent.core.rag.ContextRetriever;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.core.trace.TraceTracer;
import com.remasteragent.llm.config.LlmProperties;
import com.remasteragent.llm.rewrite.CodeRewriter;
import com.remasteragent.llm.rewrite.RewriteCommand;
import com.remasteragent.llm.rewrite.RewriteOutcome;
import com.remasteragent.tools.ast.JavaSourceAnalyzer;
import com.remasteragent.tools.ast.LegacyImportDetector;
import com.remasteragent.tools.ast.Spring3BreakingApiScanner;
import com.remasteragent.tools.diff.UnifiedDiffGenerator;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * REWRITE 节点：调模型生成现代化版本，校验后落到沙箱工作目录。
 *
 * <p>每一次执行都是<b>基于原始源码整文件覆盖</b>，而不是在上一次失败的产物上继续改。
 * 这个选择带来的直接好处是「回滚」不需要任何补偿逻辑：上一次失败的产物会在这次覆盖时自然消失。
 * 代价是文件很大时 token 消耗偏高 —— 阶段 2 换成结构化 patch 时再权衡。
 *
 * <p>它同时负责落两类账：{@code patch}（产出的 diff，供人工审查）与
 * {@code llm_call}（token 与成本）。放在这里而不是散在别处，是因为「一次调用」的成本信息
 * 只有在调用现场才拿得到，事后补不回来。
 *
 * <p><b>阶段 2 起它还负责「把上下文递进去」</b>：从 {@link ContextRetriever} 取回与目标类
 * 相关的跨文件代码块（谁调用了它、它依赖谁），随请求一起交给改写器拼进 prompt。
 * 节点只做「取」，不做「拼」—— 拼 prompt 是 LLM 层的事；节点也不直接依赖
 * {@code HybridRetriever}，而是依赖接口，这样「写盘 + 记账 + 护栏」这段最需要确定性验证的
 * 逻辑，在单测里塞一个 {@link ContextRetriever#NONE} 就能跑，不必连数据库。
 */
@Component
public class RewriteNode implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(RewriteNode.class);

    /** 上游分析节点的业务键，常量而非字面量散落各处 —— 它同时被调度器引用。 */
    public static final String NODE_KEY = "rewrite";
    public static final String ANALYZE_NODE_KEY = AnalyzeNode.NODE_KEY;

    /**
     * 某个文件的改写节点键：{@code rewrite:<相对路径>}。
     *
     * <p><b>为什么键里要带文件路径</b>：阶段 2 一次任务可能迁移多个文件，多个 REWRITE 节点
     * 必须彼此可区分；回退重写也要能精确定位「是哪个文件、第几轮」。这直接影响
     * {@link TaskStore} 里所有按 {@code (task_id, node_key, attempt)} 定位的查询。
     *
     * <p>不带文件的裸键 {@code rewrite} 仍被兼容（隐式退化为单文件模式，见 {@link #filePathOf}）。
     */
    public static String nodeKey(String filePath) {
        return NODE_KEY + ":" + filePath;
    }

    /** 从节点键解析目标文件；键里不带文件（隐式单文件模式）时返回 null，由调用方退回 ANALYZE 的入口文件。 */
    public static String filePathOf(DagNode node) {
        String key = node.nodeKey();
        if (key == null) {
            return null;
        }
        int colon = key.indexOf(':');
        if (colon < 0 || colon == key.length() - 1) {
            return null;
        }
        return key.substring(colon + 1);
    }

    private final TaskStore taskStore;
    private final CodeRewriter codeRewriter;
    private final LlmProperties llmProperties;
    private final JsonCodec json;
    private final ContextRetriever contextRetriever;
    private final ProgressPublisher progressPublisher;
    private final TraceTracer tracer;

    /** 单测入口：不发布进度（进度是给页面看的，不是业务数据），也不埋点。 */
    public RewriteNode(TaskStore taskStore, CodeRewriter codeRewriter,
                       LlmProperties llmProperties, JsonCodec json,
                       ContextRetriever contextRetriever) {
        this(taskStore, codeRewriter, llmProperties, json, contextRetriever,
                ProgressPublisher.NOOP, TraceTracer.NOOP);
    }

    /**
     * 生产入口。
     *
     * <p>进度发布口用 {@link ObjectProvider} 取而不是直接注入：core 刻意不依赖 Spring Web，
     * 而 {@code ProgressPublisher} 的实现来自 Worker 的装配，某些场景（单测、只跑 core 的构建）
     * 容器里根本没有这个 Bean。取不到就退化成 NOOP，节点逻辑照常跑 ——
     * 与 {@code DagScheduler} 保持同一种取法，避免两处行为不一致。
     */
    @Autowired
    public RewriteNode(TaskStore taskStore, CodeRewriter codeRewriter,
                       LlmProperties llmProperties, JsonCodec json,
                       ContextRetriever contextRetriever,
                       ObjectProvider<ProgressPublisher> publisherProvider,
                       TraceTracer tracer) {
        this(taskStore, codeRewriter, llmProperties, json, contextRetriever,
                publisherProvider == null
                        ? ProgressPublisher.NOOP
                        : publisherProvider.getIfAvailable(() -> ProgressPublisher.NOOP),
                tracer == null ? TraceTracer.NOOP : tracer);
    }

    private RewriteNode(TaskStore taskStore, CodeRewriter codeRewriter,
                        LlmProperties llmProperties, JsonCodec json,
                        ContextRetriever contextRetriever,
                        ProgressPublisher progressPublisher, TraceTracer tracer) {
        this.taskStore = taskStore;
        this.codeRewriter = codeRewriter;
        this.llmProperties = llmProperties;
        this.json = json;
        this.contextRetriever = contextRetriever;
        this.progressPublisher = progressPublisher;
        this.tracer = tracer;
    }

    @Override
    public NodeType type() {
        return NodeType.REWRITE;
    }

    @Override
    public NodeOutcome execute(NodeContext context) {
        String targetFile = resolveTargetFile(context);
        if (targetFile == null) {
            return NodeOutcome.fail("找不到要改写的目标文件（既无节点键标识，也无 ANALYZE 产出）");
        }

        Path workspace = context.workspace().normalize();
        Path target = workspace.resolve(targetFile).normalize();
        if (!target.startsWith(workspace)) {
            return NodeOutcome.fail("目标文件路径越出了工作目录，拒绝改写: " + targetFile);
        }
        if (!Files.isRegularFile(target)) {
            return NodeOutcome.fail("目标文件不存在: " + targetFile);
        }

        // 从工作目录读源码，而不是用 ANALYZE 快照：多文件场景下，前面的文件可能已经改过，
        // 工作目录才是「此刻的真相」；单文件场景下二者一致，无差别。
        String source;
        JavaSourceAnalyzer.ParsedHeader header;
        try {
            source = Files.readString(target, StandardCharsets.UTF_8);
            header = JavaSourceAnalyzer.parseHeader(source);
        } catch (Exception e) {
            return NodeOutcome.fail("读取或解析目标源码失败: " + e.getMessage());
        }

        // 框架破坏性变更（Spring Boot 2→3）由确定性扫描给出，与「上一次失败反馈」性质不同：
        // 它是首轮就有的静态事实，模型看到才知道「这个文件虽然没 javax，但底层 API 已经换了」。
        // 提示取自改写前的 source —— 与残留旧 import 校验同一时刻的真相。
        List<String> migrationHints = Spring3BreakingApiScanner.scan(source).stream()
                .map(finding -> finding.ruleId() + ": " + finding.hint())
                .toList();

        RewriteCommand command = new RewriteCommand(
                targetFile,
                header.packageName(),
                header.primaryType(),
                source,
                context.task().targetJdk(),
                context.attempt(),
                context.retryFeedback(),
                retrieveContext(context.task().projectRoot(), targetFile, header),
                new RetryProgressListener(progressPublisher, context.task().id(),
                        context.node().id(), context.node().nodeKey(), context.attempt(), "改写"),
                migrationHints);

        if (context.isRetry()) {
            log.info("第 {} 次重写 attempt={} 文件={} 携带失败反馈 {} 字",
                    context.attempt() + 1, context.attempt(), targetFile,
                    context.retryFeedback() == null ? 0 : context.retryFeedback().length());
        }

        RewriteOutcome outcome;
        Span llmSpan = tracer.startLlm(context.task().id(), context.node().id(),
                NodeType.REWRITE.name());
        try {
            outcome = codeRewriter.rewrite(command);
        } catch (CodeRewriter.RewriteFailedException e) {
            // 模型产出不可解析属于可重试失败，直接进 attempt+1
            TraceTracer.endError(llmSpan, e.getMessage());
            return NodeOutcome.fail("模型产出不可用: " + e.getMessage());
        } catch (Exception e) {
            TraceTracer.endException(llmSpan, e);
            return NodeOutcome.fail("调用大模型失败: " + e);
        }
        // 模型名、token 与耗时都在拿到响应之后才知道 —— 所以这些属性是「补」上去的，
        // 而不是建 span 时就能给的。trace 的价值恰恰在这里：它是记录事实，不是声明意图。
        annotateLlmSpan(llmSpan, outcome);
        TraceTracer.endOk(llmSpan);

        recordLlmCall(context, outcome);

        RewriteProposal proposal = outcome.proposal();
        // 护栏判据来自目标文件自身（包名/主类型不可被改坏），而不是别的文件。
        // 公开成员快照也来自改写前的 source：模型若删掉 public 方法/字段，会破坏调用方/测试。
        List<String> publicMembers = JavaSourceAnalyzer.publicMembers(source);
        ProposalGuardrail.GuardrailResult guardrail = ProposalGuardrail.check(
                header.packageName(), header.primaryType(), publicMembers, proposal);
        if (!guardrail.passed()) {
            log.warn("产出未通过校验: {}", guardrail.reason());
            return NodeOutcome.fail("产出未通过校验：" + guardrail.reason());
        }

        // 迁移完整度校验：改写产物若仍残留「本应被迁移走的旧 import」（JDK 移除包，或 javax → jakarta
        // 白名单项），直接判失败并带<b>精确</b>反馈重跑，而不是等到整仓 VERIFY 编译失败才暴露真因。
        // 这是兜住「模型漏改某个 import」这道最隐蔽的迁移质量问题的确定性安全网
        // —— 博客工程（BlogService）里 {@code javax.annotation.Resource} 就曾被漏改，导致整仓编译失败。
        // 判定口径与调度器一致（{@link LegacyImportDetector}），JDK 内建仍在的 javax.*（如 javax.crypto）
        // 不会被误伤，不会误判阻断改写。
        List<String> leftover = LegacyImportDetector.leftoverLegacyImports(proposal.newContent());
        if (!leftover.isEmpty()) {
            String joined = String.join(", ", leftover);
            log.warn("改写后仍残留需迁移的旧 import [{}]: {}", targetFile, joined);
            return NodeOutcome.fail("改写后仍残留需迁移的旧 import（未从 javax 迁到 jakarta）："
                    + joined + "。请将这些 import 改为对应的 jakarta.* 命名空间"
                    + "（例如 javax.annotation.Resource → jakarta.annotation.Resource、"
                    + "javax.persistence.* → jakarta.persistence.*），不要漏改。");
        }

        try {
            String diff = UnifiedDiffGenerator.generate(source, proposal.newContent(), targetFile);
            UnifiedDiffGenerator.DiffStat stat = UnifiedDiffGenerator.stat(diff);

            // 空补丁必须在这一步拦掉，不能留给 VERIFY 去「编译失败」兜底。
            // 模型原样返回源文件时 diff 是 +0/-0：若照常判 SUCCEEDED，就等于白烧一次完整尝试
            // （一次 LLM 调用 + 一次沙箱构建），而 VERIFY 报出的原因是「编译失败」，
            // 既不指向真因「你上一轮什么都没改」，也进不了下一轮的失败反馈。
            // 这里判 FAIL 会走 DagScheduler.planRetry → attempt+1，
            // 且失败原因由 resolveRetryFeedback 带回给模型，让它知道「上次那版等于没交」。
            if (stat.added() + stat.removed() == 0) {
                // 「模型原样返回」不一定是坏事 —— 得先问源文件到底有没有活要干。
                // 源文件已无残留旧 import、也未命中框架破坏性变更时，模型判断「无需改动」就是正确结论；
                // 此时若照旧判 FAIL，只会把已经干净的文件再拉回来跑一轮（实测 #57 里因此空跑 20 次，
                // 白白烧掉一轮 LLM 调用与一次沙箱构建），而 VERIFY 侧什么信息也拿不到。
                // 反过来，源文件确实有活要干却原样返回，才是真失败，必须带反馈重跑。
                boolean nothingToDo = !LegacyImportDetector.hasLeftover(source)
                        && !Spring3BreakingApiScanner.hasAny(source);
                if (nothingToDo) {
                    log.info("改写未产生差异，但源文件已无待迁移项，判定为无需改动: {}", targetFile);
                    return NodeOutcome.ok(new RewriteResult(
                            targetFile, source, "", "源文件已无待迁移项，无需改写",
                            outcome.model(), context.attempt()));
                }
                log.warn("改写未产生任何差异 [{}]，模型返回的内容与原文一致", targetFile);
                return NodeOutcome.fail("改写未产生任何差异（+0/-0）：返回的内容与原文完全相同。"
                        + "请重新审视迁移目标，至少完成一项实质改写（如 javax→jakarta、"
                        + "Date/Calendar→java.time、Stream.toList() 等），不要原样返回源文件。");
            }

            Files.writeString(target, proposal.newContent(), StandardCharsets.UTF_8);
            taskStore.insertPatch(context.node().id(), targetFile, diff, sha256(source));
            log.info("改写已应用 [{}] {} ({} 行)", targetFile, stat.describe(),
                    proposal.newContent().lines().count());

            return NodeOutcome.ok(new RewriteResult(
                    targetFile,
                    proposal.newContent(),
                    diff,
                    proposal.rationale(),
                    outcome.model(),
                    context.attempt()));
        } catch (Exception e) {
            return NodeOutcome.fail("写入沙箱工作目录失败: " + e.getMessage());
        }
    }

    /**
     * 取本次改写要带上的跨文件上下文。
     *
     * <h2>查询串为什么是「类的全限定名」</h2>
     * <p>检索意图直接决定召回质量。这里不去拼「路径 + 类名 + 方法名」的长串，而是用
     * {@code com.example.legacy.LegacySalesReport} 这一个符号：符号路能精确命中同名类型，
     * 全文路切词后拿到 {@code legacy / LegacySalesReport} 这类有判别力的词，
     * 而路径片段（{@code src / main / java}）只会带来一堆无差别命中。少即是多。
     *
     * <h2>为什么要把「目标文件自己」的块滤掉</h2>
     * <p>被改写的文件必然能被自己的类名召回到（它就是最相关的那一块）。但它的完整源码
     * 已经作为 {@code sourceContent} 出现在 prompt 里了 —— 再以「相关代码」的名义出现一次，
     * 就是同一段代码进 prompt 两遍：既浪费 token，又会让模型误以为「相关代码」里那份才是权威。
     * 所以在返回前按文件路径剔除。
     *
     * <p>检索失败一律降级为空列表：检索是增强，不该成为改写失败的单点。
     */
    private List<RetrievedChunk> retrieveContext(String projectRoot, String targetFile,
                                                 JavaSourceAnalyzer.ParsedHeader header) {
        String query = header.packageName().isEmpty()
                ? header.primaryType()
                : header.packageName() + "." + header.primaryType();
        if (query.isBlank()) {
            query = targetFile;
        }

        List<RetrievedChunk> hits;
        try {
            hits = contextRetriever.retrieve(projectRoot, query);
        } catch (Exception e) {
            log.warn("上下文检索失败，本次退回「只看目标文件」模式: {}", e.getMessage());
            return List.of();
        }
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }

        List<RetrievedChunk> filtered = hits.stream()
                .filter(hit -> hit.chunk() != null && !sameFile(hit.chunk().filePath(), targetFile))
                .toList();
        if (filtered.size() != hits.size()) {
            log.debug("检索命中 {} 块，剔除目标文件自身后保留 {}", hits.size(), filtered.size());
        }
        return filtered;
    }

    /** 比较两个工程相对路径是否指向同一文件 —— 索引与落盘可能一个用 {@code /} 一个用 {@code \}。 */
    private static boolean sameFile(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.replace('\\', '/').equalsIgnoreCase(right.replace('\\', '/'));
    }

    /**
     * 目标文件：优先取节点键里的（阶段 2 的计划节点）；
     * 节点键不带文件时退回 ANALYZE 分析的入口文件（隐式单文件模式，兼容阶段 1 的拓扑）。
     */
    private String resolveTargetFile(NodeContext context) {
        String fromKey = filePathOf(context.node());
        if (fromKey != null) {
            return fromKey;
        }
        AnalyzeResult analysis = loadAnalysis(context);
        return analysis == null ? null : analysis.filePath();
    }

    /** 从上游 ANALYZE 节点的 checkpoint 里读回分析结果。 */
    private AnalyzeResult loadAnalysis(NodeContext context) {
        return taskStore.findLatestSucceeded(context.task().id(), ANALYZE_NODE_KEY)
                .map(DagNode::resultJson)
                .flatMap(json -> this.json.read(json, AnalyzeResult.class))
                .orElse(null);
    }

    /**
     * 把「这次模型调用的实际形状」补到 span 上。
     *
     * <p>token 与成本写在 span 上，而不是只留在 {@code llm_call} 表里：两处的用途不同 ——
     * 表是账本（一行一次调用，可聚合可审计），span 是时间轴（这一段在整条链路里的位置与占比）。
     * 想让报表回答「重试让成本涨了多少」，两边都得有。
     */
    private void annotateLlmSpan(Span span, RewriteOutcome outcome) {
        span.setAttribute("llm.model", outcome.model() == null ? "unknown" : outcome.model());
        span.setAttribute("llm.prompt_tokens", (long) outcome.promptTokens());
        span.setAttribute("llm.completion_tokens", (long) outcome.completionTokens());
        span.setAttribute("llm.latency_ms", outcome.latencyMs());
        span.setAttribute("llm.cost", estimateCost(outcome));
    }

    /** 按单价表估算成本。与 {@link #recordLlmCall} 共用同一套算法，避免两处算出两个数。 */
    private double estimateCost(RewriteOutcome outcome) {
        return outcome.promptTokens() / 1_000_000d * llmProperties.inputPricePerMillion()
                + outcome.completionTokens() / 1_000_000d * llmProperties.outputPricePerMillion();
    }

    private void recordLlmCall(NodeContext context, RewriteOutcome outcome) {
        taskStore.recordLlmCall(new LlmCallRecord(
                null,
                context.task().id(),
                context.node().id(),
                outcome.model(),
                NodeType.REWRITE.name(),
                outcome.promptTokens(),
                outcome.completionTokens(),
                estimateCost(outcome),
                outcome.latencyMs(),
                // 写的就是当前 span 的 trace id —— 它此刻正被 DagScheduler 的节点 span 罩着，
                // 而节点 span 又挂在任务 span 下。于是这一行账能直接 join 回整条链路，
                // 这是 trace_id 这个「预留了很久的字段」第一次真正被填上。
                TraceTracer.currentTraceId(),
                Instant.now()));
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }
}
