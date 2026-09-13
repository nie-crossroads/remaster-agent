package com.remasteragent.llm.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
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

    private ChatModel buildModel(LlmProperties properties, String modelName) {
        return OpenAiChatModel.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .modelName(modelName)
                // 中转网关延迟波动大，超时给足；重试交给 SDK，避免我们自己写重试循环
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .maxRetries(properties.maxRetries())
                .logRequests(properties.logRequests())
                .logResponses(properties.logRequests())
                .build();
    }
}
