package com.remasteragent.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上下文治理配置（输入侧 token 预算）。
 *
 * <p>与 {@link LlmProperties} 同前缀的子命名空间 {@code remaster.llm.context}，
 * 单独成 bean 是为了不改动 {@code LlmProperties} 那个 15 参的 record 及其一堆测试构造点。
 *
 * @param maxTokens 每次改写投喂的相关代码 token 上限（按 chars/4 估算）；≤0 回落默认 3000
 * @param enabled   治理总开关；false = 检索召回全量透传，不做预算裁剪
 */
@ConfigurationProperties(prefix = "remaster.llm.context")
public record ContextGovernanceProperties(
        Integer maxTokens,
        Boolean enabled) {
}
