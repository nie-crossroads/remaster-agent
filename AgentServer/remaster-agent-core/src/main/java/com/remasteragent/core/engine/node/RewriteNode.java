package com.remasteragent.core.engine.node;

import com.remasteragent.common.agent.AnalyzeResult;
import com.remasteragent.common.agent.RewriteProposal;
import com.remasteragent.common.agent.RewriteResult;
import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.LlmCallRecord;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.core.codec.JsonCodec;
import com.remasteragent.core.engine.NodeContext;
import com.remasteragent.core.engine.NodeExecutor;
import com.remasteragent.core.engine.NodeOutcome;
import com.remasteragent.core.engine.ProposalGuardrail;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.llm.config.LlmProperties;
import com.remasteragent.llm.rewrite.CodeRewriter;
import com.remasteragent.llm.rewrite.RewriteCommand;
import com.remasteragent.llm.rewrite.RewriteOutcome;
import com.remasteragent.tools.diff.UnifiedDiffGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

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
 */
@Component
public class RewriteNode implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(RewriteNode.class);

    /** 上游分析节点的业务键，常量而非字面量散落各处 —— 它同时被调度器引用。 */
    public static final String NODE_KEY = "rewrite";
    public static final String ANALYZE_NODE_KEY = AnalyzeNode.NODE_KEY;

    private final TaskStore taskStore;
    private final CodeRewriter codeRewriter;
    private final LlmProperties llmProperties;
    private final JsonCodec json;

    public RewriteNode(TaskStore taskStore, CodeRewriter codeRewriter,
                       LlmProperties llmProperties, JsonCodec json) {
        this.taskStore = taskStore;
        this.codeRewriter = codeRewriter;
        this.llmProperties = llmProperties;
        this.json = json;
    }

    @Override
    public NodeType type() {
        return NodeType.REWRITE;
    }

    @Override
    public NodeOutcome execute(NodeContext context) {
        AnalyzeResult analysis = loadAnalysis(context);
        if (analysis == null) {
            return NodeOutcome.fail("找不到 ANALYZE 节点的产出，无法改写");
        }

        RewriteCommand command = new RewriteCommand(
                analysis.filePath(),
                analysis.packageName(),
                analysis.className(),
                analysis.sourceContent(),
                context.task().targetJdk(),
                context.attempt(),
                context.retryFeedback());

        if (context.isRetry()) {
            log.info("第 {} 次重写 attempt={} 文件={} 携带失败反馈 {} 字",
                    context.attempt() + 1, context.attempt(), analysis.filePath(),
                    context.retryFeedback() == null ? 0 : context.retryFeedback().length());
        }

        RewriteOutcome outcome;
        try {
            outcome = codeRewriter.rewrite(command);
        } catch (CodeRewriter.RewriteFailedException e) {
            // 模型产出不可解析属于可重试失败，直接进 attempt+1
            return NodeOutcome.fail("模型产出不可用: " + e.getMessage());
        } catch (Exception e) {
            return NodeOutcome.fail("调用大模型失败: " + e);
        }

        recordLlmCall(context, outcome);

        RewriteProposal proposal = outcome.proposal();
        ProposalGuardrail.GuardrailResult guardrail = ProposalGuardrail.check(
                analysis.packageName(), analysis.className(), proposal);
        if (!guardrail.passed()) {
            log.warn("产出未通过校验: {}", guardrail.reason());
            return NodeOutcome.fail("产出未通过校验：" + guardrail.reason());
        }

        try {
            Path target = context.workspace().resolve(analysis.filePath()).normalize();
            String diff = UnifiedDiffGenerator.generate(
                    analysis.sourceContent(), proposal.newContent(), analysis.filePath());
            Files.writeString(target, proposal.newContent(), StandardCharsets.UTF_8);

            taskStore.insertPatch(context.node().id(), analysis.filePath(), diff,
                    sha256(analysis.sourceContent()));

            UnifiedDiffGenerator.DiffStat stat = UnifiedDiffGenerator.stat(diff);
            log.info("改写已应用 [{}] {} ({} 行)", analysis.filePath(), stat.describe(),
                    proposal.newContent().lines().count());

            return NodeOutcome.ok(new RewriteResult(
                    analysis.filePath(),
                    proposal.newContent(),
                    diff,
                    proposal.rationale(),
                    outcome.model(),
                    context.attempt()));
        } catch (Exception e) {
            return NodeOutcome.fail("写入沙箱工作目录失败: " + e.getMessage());
        }
    }

    /** 从上游 ANALYZE 节点的 checkpoint 里读回分析结果。 */
    private AnalyzeResult loadAnalysis(NodeContext context) {
        return taskStore.findLatestSucceeded(context.task().id(), ANALYZE_NODE_KEY)
                .map(DagNode::resultJson)
                .flatMap(json -> this.json.read(json, AnalyzeResult.class))
                .orElse(null);
    }

    private void recordLlmCall(NodeContext context, RewriteOutcome outcome) {
        double cost = outcome.promptTokens() / 1_000_000d * llmProperties.inputPricePerMillion()
                + outcome.completionTokens() / 1_000_000d * llmProperties.outputPricePerMillion();

        taskStore.recordLlmCall(new LlmCallRecord(
                null,
                context.task().id(),
                context.node().id(),
                outcome.model(),
                NodeType.REWRITE.name(),
                outcome.promptTokens(),
                outcome.completionTokens(),
                cost,
                outcome.latencyMs(),
                null,
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
