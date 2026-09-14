package com.remasteragent.llm.plan;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remasteragent.common.agent.PlanResult;
import com.remasteragent.llm.config.LlmPurpose;
import com.remasteragent.llm.config.ModelRegistry;
import com.remasteragent.llm.retry.LlmRetryExecutor;
import com.remasteragent.llm.rewrite.JsonExtractor;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 迁移规划器 —— 阶段 2（理解层）的「大脑」。
 *
 * <h2>它在整条链路里的位置</h2>
 * <p>阶段 1 的 DAG 是写死的三步：改入口文件 → 编译验证。阶段 2 让模型先看一眼整个工程，
 * 决定<b>要改哪些文件、按什么顺序</b>，再由 {@code PlanNode} 把这份决定翻译成真正的 DAG 节点。
 * 这就是「从看懂一个文件到理解整个代码库」的落点。
 *
 * <h2>为什么让模型做规划、而不写死规则</h2>
 * <p>「哪个文件需要现代化」看似能用规则（grep {@code java.util.Date}）解决，但实际不行：
 * 同一个 API 是否算遗留，取决于上下文 —— 一个文件里 {@code Date} 只是从外部传进来的参数，
 * 另一处却在做日期运算，只有后者需要改。这种判断需要理解代码意图，正是模型的强项；
 * 而规则只会在两个方向上同时犯错（漏改 + 误改）。
 *
 * <p>与 {@code CodeRewriter} 一致，这里直接调 {@link ChatModel} 而不是 AiServices：
 * 成本治理要求拿到 token 用量并落 {@code llm_call}，声明式接口拿不到这些。
 */
@Component
public class MigrationPlanner {

    private static final Logger log = LoggerFactory.getLogger(MigrationPlanner.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String SYSTEM_PROMPT = """
            You are a Java migration planner. You are given an inventory of Java source files in a
            legacy project. Decide which files must be modernized to reach Java %d, and in what order.

            Hard rules:
            1. Only include files that genuinely need modernization: legacy date/time APIs
               (java.util.Date, Calendar, SimpleDateFormat), anonymous inner classes that can become
               lambdas, boxed constructors (new Integer(...)), legacy collection usage, manual loops
               that can become streams ONLY when it clearly helps, etc.
            2. Do NOT include files that are already modern, test sources, or build files.
            3. If the entry file needs changes, it MUST be included.
            4. Order matters: files that others depend on come first.
            5. Prefer a small, precise set over a large speculative one — every file you list will
               trigger a real model call and a real sandbox build.
            6. Respond with ONLY a JSON object, no markdown fences, no extra prose:
               {"summary":"<中文，1-2 句概括本次迁移的整体思路>","steps":[{"filePath":"<path exactly as given>","rationale":"<中文，1-2 句说明该文件为什么需要迁移>"}]}
            7. If no file needs modernization, return {"summary":"...","steps":[]}.

            In the JSON, filePath MUST be copied exactly from the inventory — do not invent paths.
            """;

    private final ModelRegistry modelRegistry;
    private final LlmRetryExecutor retryExecutor;

    /** 单参数入口：不重试、直调一次。供桩件与单测使用。 */
    public MigrationPlanner(ModelRegistry modelRegistry) {
        this(modelRegistry, null);
    }

    /**
     * 生产入口：重试由 {@link LlmRetryExecutor} 接管（SDK 内层重试已在 {@code LlmConfig} 关闭）。
     *
     * <p>规划虽然比改写快得多（实测 12~28s），但走的是同一个网关、同样会撞上游 524。
     * 而且它失败的后果更重 —— 规划是任务的第一步，它失败意味着整个任务连一个文件都没开始改，
     * 谈不上「回退重写」那套兜底。所以这里没有理由不接上重试。
     */
    @Autowired
    public MigrationPlanner(ModelRegistry modelRegistry, LlmRetryExecutor retryExecutor) {
        this.modelRegistry = modelRegistry;
        this.retryExecutor = retryExecutor == null ? LlmRetryExecutor.noRetry() : retryExecutor;
    }

    /**
     * 规划一次迁移。
     *
     * @throws PlanFailedException 模型回复无法解析成合法计划时抛出，调用方应把它当一次失败
     */
    public PlanOutcome plan(PlanCommand command) {
        ChatModel model = modelRegistry.forPurpose(LlmPurpose.PLAN);
        String modelName = modelRegistry.modelNameForPurpose(LlmPurpose.PLAN);

        List<ChatMessage> messages = List.of(
                SystemMessage.from(SYSTEM_PROMPT.formatted(command.targetJdk())),
                UserMessage.from(buildUserPrompt(command)));

        long startedAt = System.currentTimeMillis();
        ChatResponse response = retryExecutor.execute(
                "规划 " + command.entryFile(),
                () -> model.chat(messages),
                command.attemptListener());
        long latencyMs = System.currentTimeMillis() - startedAt;

        String raw = response.aiMessage() == null ? "" : response.aiMessage().text();
        TokenUsage usage = response.tokenUsage();
        int promptTokens = usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
        int completionTokens = usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();

        log.info("规划调用完成 [{}] 候选文件={} 耗时={}ms tokens={}/{}",
                modelName, command.files().size(), latencyMs, promptTokens, completionTokens);

        PlanResult plan = parsePlan(raw, command, modelName);
        return new PlanOutcome(plan, modelName, promptTokens, completionTokens, latencyMs);
    }

    private PlanResult parsePlan(String raw, PlanCommand command, String modelName) {
        // 要求必须带 steps：与 Rewrite 侧要求 newContent 同理 —— 防止正文里出现的无关 JSON 被误当成计划
        String json = JsonExtractor.extractObject(raw, "steps");
        if (json == null) {
            throw new PlanFailedException(
                    "模型回复里找不到完整的计划 JSON（可能被截断）。模型=" + modelName
                            + "，回复前 300 字: " + abbreviate(raw, 300));
        }
        PlanResult plan;
        try {
            plan = MAPPER.readValue(json, PlanResult.class);
        } catch (Exception e) {
            throw new PlanFailedException("计划 JSON 解析失败: " + e.getMessage()
                    + "，片段: " + abbreviate(json, 300), e);
        }
        if (plan.steps() == null) {
            plan = new PlanResult(plan.summary(), List.of());
        }
        return plan;
    }

    private String buildUserPrompt(PlanCommand command) {
        StringBuilder sb = new StringBuilder();
        sb.append("Entry file: ").append(command.entryFile()).append('\n');
        sb.append("Target JDK: ").append(command.targetJdk()).append('\n');
        sb.append("\nFile inventory (path | primary type | symbols):\n");
        for (PlanCommand.FileSummary file : command.files()) {
            sb.append("- ").append(file.filePath())
                    .append(" | ").append(file.primaryType().isBlank() ? "(no type)" : file.primaryType())
                    .append(" | ").append(String.join(", ", file.symbols()))
                    .append('\n');
        }
        return sb.toString();
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }

    /** 模型产出无法解析成合法计划时抛出，属于「可重试的失败」而非系统故障。 */
    public static class PlanFailedException extends RuntimeException {
        public PlanFailedException(String message) {
            super(message);
        }

        public PlanFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
