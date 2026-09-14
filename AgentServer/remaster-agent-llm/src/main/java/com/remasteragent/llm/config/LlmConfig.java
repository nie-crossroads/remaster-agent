package com.remasteragent.llm.config;

import com.remasteragent.llm.embedding.EmbeddingProvider;
import com.remasteragent.llm.embedding.HttpEmbeddingProvider;
import com.remasteragent.llm.embedding.NoopEmbeddingProvider;
import com.remasteragent.llm.retry.LlmRetryExecutor;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 大模型层的装配。
 *
 * <p><b>为什么手写配置而不用官方 starter。</b>LangChain4j 提供了
 * {@code langchain4j-open-ai-spring-boot-starter}，但它的版本号带 {@code -beta} 后缀、
 * 与核心 BOM 的版本并不同步，等于多引入一个「版本对不上」的坑。这里的装配逻辑一共十几行，
 * 自己写反而更可控，而且能顺带把多模型路由、超时、重试这些策略显式表达出来 ——
 * 面试时被问「LangChain4j 怎么和 Spring 整合的」，能直接指着这段讲。
 *
 * <p>刻意<b>不设置 temperature</b>：本项目接的是中转网关，而新一代推理类模型
 * 往往不接受 temperature 参数，设了可能直接报错。改写任务本身需要的是稳定的结构化输出，
 * 靠的是 prompt 约束和产出校验（Guardrail），不是采样温度。
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public ModelRegistry modelRegistry(LlmProperties properties) {
        ChatModel defaultModel = buildModel(properties, properties.model());

        // 只有真的配了不同的改写模型才建第二个实例，避免无谓地多持有一份连接资源
        ChatModel rewriteModel = properties.hasDedicatedRewriteModel()
                ? buildModel(properties, properties.effectiveRewriteModel())
                : defaultModel;

        return new ModelRegistry(
                defaultModel,
                properties.model(),
                rewriteModel,
                properties.effectiveRewriteModel());
    }

    /**
     * 向量模型装配 —— 未配置时给一个「显式不可用」的占位，而不是让检索层去 try/catch。
     *
     * <p>这样「没配向量模型」是一种正常状态：混合检索看 {@code available()} 决定跳不跳向量路。
     */
    @Bean
    public EmbeddingProvider embeddingProvider(LlmProperties properties) {
        if (!properties.hasEmbeddingModel()) {
            log.info("未配置 embedding 模型（remaster.llm.embedding-model 为空）："
                    + "代码 RAG 的向量路禁用，混合检索退化为「全文 + 符号」两路");
            return NoopEmbeddingProvider.INSTANCE;
        }
        log.info("已启用 embedding 模型: {}（维度 {}，网关 {}）", properties.embeddingModel(),
                properties.effectiveEmbeddingDimensions() > 0
                        ? properties.effectiveEmbeddingDimensions()
                        : "由服务端决定",
                properties.hasDedicatedEmbeddingEndpoint()
                        ? properties.effectiveEmbeddingBaseUrl() + "（向量专用）"
                        : properties.effectiveEmbeddingBaseUrl() + "（与 chat 共用）");
        return new HttpEmbeddingProvider(properties);
    }

    /**
     * 重试执行器 —— 「重试几次、隔多久重试、最多花多久」这三件事只在这里定义。
     *
     * <p>做成 Bean 而不是各调用点自己 new：这三个参数都属于<b>部署策略</b>，应当跟着
     * {@code application.yml} 走。散落在调用点里就会演变成几处不一致的隐式默认值，
     * 而其中最危险的是「总预算」—— 只有集中一处，它才可能被当成一个需要被审视的数字。
     */
    @Bean
    public LlmRetryExecutor llmRetryExecutor(LlmProperties properties) {
        log.info("大模型重试策略: 单次调用最多 {} 次请求（1 次 + {} 次重试）"
                        + "｜退避 {}ms 起（指数增长，封顶 30s）｜总预算 {}s",
                properties.totalHttpAttempts(), properties.maxRetries(),
                properties.retryBackoffMillis(), properties.callBudgetSeconds());
        return new LlmRetryExecutor(
                properties.maxRetries(),
                properties.retryBackoffMillis(),
                properties.callBudgetSeconds());
    }

    private ChatModel buildModel(LlmProperties properties, String modelName) {
        return OpenAiChatModel.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .modelName(modelName)
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                // SDK 的内层重试必须关掉：重试已由 LlmRetryExecutor 统一接管。
                // 留着它就是两层重试相乘（3 × 3 = 9 次请求），贵且慢；
                // 更麻烦的是内层重试不经过我们的回调，节点和前端都看不见它 ——
                // 实测的故障现象就是一个节点跑了 6 分钟，页面却全程显示「运行中」，与卡死无从区分。
                .maxRetries(0)
                .logRequests(properties.logRequests())
                .logResponses(properties.logRequests())
                .build();
    }
}
