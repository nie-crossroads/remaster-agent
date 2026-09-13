package com.remasteragent.llm.rewrite;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remasteragent.common.agent.RewriteProposal;
import com.remasteragent.llm.config.LlmPurpose;
import com.remasteragent.llm.config.ModelRegistry;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 代码改写器 —— 直接调用 ChatModel 而不是用 AiServices 声明式接口。
 *
 * <p><b>为什么不用 AiServices。</b>声明式写法确实更短，但它的返回值只给你映射好的对象，
 * token 用量、原始回复这些信息拿不到（或要绕一圈才拿得到）。而本项目的成本治理要求
 * 每次调用都落 {@code llm_call} 表，用量是一等公民。所以这里选择直接调模型、
 * 自己解析结构化输出 —— 多写的这几十行换来的是完整可观测性，值得。
 *
 * <p>关于产出的容错：模型即使被明确要求「只返回 JSON」，也常带代码围栏或前后解释文字。
 * 这属于正常行为，不应当作故障。所以用 {@link JsonExtractor} 在正文里定位 JSON 对象，
 * 只有在真的找不到完整 JSON 时才判失败。
 */
@Component
public class CodeRewriter {

    private static final Logger log = LoggerFactory.getLogger(CodeRewriter.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String SYSTEM_PROMPT = """
            You are a Java code modernization engine. You rewrite ONE Java file so that it uses
            modern Java %d idioms while preserving its observable behavior exactly.

            Hard rules:
            1. Keep the package declaration unchanged.
            2. Keep the primary type name unchanged.
            3. Preserve behavior. Do not change business logic, do not add features,
               do not remove or weaken tests, do not rename public methods.
            4. Output the COMPLETE file content. Not a fragment. Not a diff. Not a patch.
            5. If some part cannot be safely modernized, leave it exactly as it is.
            6. Do not insert comments like "// changed" or "// improved" unless the change
               is genuinely non-obvious to a reviewer.

            Modernization targets, in priority order:
            - Replace APIs removed or deprecated in modern Java (legacy collection types,
              old date/time classes, boxed constructors, finalize, etc.).
            - Replace anonymous inner classes with lambdas or method references where the
              target type is a functional interface.
            - Use java.time instead of java.util.Date/Calendar/SimpleDateFormat.
            - Use var only when the right-hand side makes the type obvious.
            - Use text blocks for multi-line string literals.
            - Prefer immutable collections and enhanced switch where it genuinely helps.

            Respond with ONLY a JSON object, no markdown fences, no extra prose:
            {"filePath":"<path as given>","newContent":"<full file content>","rationale":"<中文，2-4句，说明改了什么以及为什么>"}

            In newContent you MUST escape newlines as \\n and double quotes as \\".
            The value must be valid JSON string content.
            """;

    private final ModelRegistry modelRegistry;

    public CodeRewriter(ModelRegistry modelRegistry) {
        this.modelRegistry = modelRegistry;
    }

    /**
     * 执行一次改写。
     *
     * @throws RewriteFailedException 模型回复无法解析成合法产出时抛出。
     *                                调用方（RewriteNode）应把它当成一次失败尝试，
     *                                走 attempt+1 的回退路径，而不是让整个任务崩掉。
     */
    public RewriteOutcome rewrite(RewriteCommand command) {
        ChatModel model = modelRegistry.forPurpose(LlmPurpose.REWRITE);
        String modelName = modelRegistry.modelNameForPurpose(LlmPurpose.REWRITE);

        List<ChatMessage> messages = List.of(
                SystemMessage.from(SYSTEM_PROMPT.formatted(command.targetJdk())),
                UserMessage.from(buildUserPrompt(command)));

        long startedAt = System.currentTimeMillis();
        ChatResponse response = model.chat(messages);
        long latencyMs = System.currentTimeMillis() - startedAt;

        String raw = response.aiMessage() == null ? "" : response.aiMessage().text();
        TokenUsage usage = response.tokenUsage();
        int promptTokens = usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
        int completionTokens = usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();

        log.info("改写调用完成 [{}] 文件={} attempt={} 耗时={}ms tokens={}/{}",
                modelName, command.filePath(), command.attempt(), latencyMs,
                promptTokens, completionTokens);

        RewriteProposal proposal = parseProposal(raw, command, modelName);

        return new RewriteOutcome(proposal, modelName, promptTokens, completionTokens, latencyMs, raw);
    }

    private RewriteProposal parseProposal(String raw, RewriteCommand command, String modelName) {
        // 要求必须带 newContent：这样正文里若出现旧代码示例（如 class A { }），
        // 不会被误当成产出的 JSON —— {} 是合法 JSON，只按「可解析」判会踩这个坑
        String json = JsonExtractor.extractObject(raw, "newContent");
        if (json == null) {
            throw new RewriteFailedException(
                    "模型回复里找不到完整的 JSON 对象（可能被截断）。模型=" + modelName
                            + "，回复前 300 字: " + abbreviate(raw, 300));
        }
        RewriteProposal proposal;
        try {
            proposal = MAPPER.readValue(json, RewriteProposal.class);
        } catch (Exception e) {
            throw new RewriteFailedException(
                    "JSON 解析失败: " + e.getMessage() + "，片段: " + abbreviate(json, 300), e);
        }

        if (proposal.newContent() == null || proposal.newContent().isBlank()) {
            throw new RewriteFailedException("模型返回的 newContent 为空");
        }

        // 模型有时会把 filePath 漏掉或改写，以我们下发的路径为准 ——
        // 它无权决定改哪个文件，这一点不能由模型说了算
        if (proposal.filePath() == null || proposal.filePath().isBlank()) {
            proposal = new RewriteProposal(command.filePath(), proposal.newContent(), proposal.rationale());
        }

        return proposal;
    }

    private String buildUserPrompt(RewriteCommand command) {
        StringBuilder sb = new StringBuilder();
        sb.append("File path: ").append(command.filePath()).append('\n');
        if (command.packageName() != null && !command.packageName().isBlank()) {
            sb.append("Package (must stay unchanged): ").append(command.packageName()).append('\n');
        }
        if (command.className() != null && !command.className().isBlank()) {
            sb.append("Primary type (must stay unchanged): ").append(command.className()).append('\n');
        }

        if (command.isRetry()) {
            sb.append("\n--- 上一次尝试失败了，这是第 ").append(command.attempt()).append(" 次重试 ---\n");
            sb.append("失败信息如下，请针对性地修正，不要重复同样的错误：\n");
            sb.append(command.failureFeedback() == null || command.failureFeedback().isBlank()
                    ? "(没有拿到具体的失败信息，请重新审视代码是否能在目标 JDK 上编译通过)"
                    : command.failureFeedback());
            sb.append("\n--- 失败信息结束 ---\n");
        }

        sb.append("\nSource code to modernize:\n```java\n");
        sb.append(command.sourceContent());
        sb.append("\n```\n");
        return sb.toString();
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }

    /** 模型产出无法解析成合法结构时抛出，属于「可重试的失败」而非系统故障。 */
    public static class RewriteFailedException extends RuntimeException {
        public RewriteFailedException(String message) {
            super(message);
        }

        public RewriteFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
