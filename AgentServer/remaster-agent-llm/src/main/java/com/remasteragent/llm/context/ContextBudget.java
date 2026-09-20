package com.remasteragent.llm.context;

import com.remasteragent.llm.config.ContextGovernanceProperties;

/**
 * 上下文预算策略 —— 决定「喂给模型的检索上下文」最多占多少 token、是否启用治理。
 *
 * <p>与成本治理（输出侧、已在 {@code LlmRetryExecutor} / {@code llm_call} 落地）相对，
 * 这是<b>输入侧</b>的预算：每次改写调用，检索召回的相关代码在进 prompt 前先过这一关，
 * 超预算的按优先级从低到高丢弃，保证真正要改的源码主体始终占主导地位。
 */
public record ContextBudget(
        int maxTokens,
        boolean enabled,
        TokenEstimator estimator) {

    /** 默认预算：3000 token ≈ 12000 字符，与旧硬编码上限一致，保证历史行为不变。 */
    public static final ContextBudget DEFAULT =
            new ContextBudget(3000, true, CharBasedTokenEstimator.codeDefault());

    /** 关闭治理：不做预算裁剪，检索召回全量透传。 */
    public static ContextBudget disabled() {
        return new ContextBudget(Integer.MAX_VALUE, false, CharBasedTokenEstimator.codeDefault());
    }

    /** 测试 / 临时用：给定上限与开关直接构造。 */
    public static ContextBudget of(int maxTokens, boolean enabled) {
        return new ContextBudget(maxTokens, enabled, CharBasedTokenEstimator.codeDefault());
    }

    /** 从配置属性构建；属性为空或非法时回落默认。 */
    public static ContextBudget from(ContextGovernanceProperties props) {
        if (props == null) {
            return DEFAULT;
        }
        int max = (props.maxTokens() == null || props.maxTokens() <= 0) ? 3000 : props.maxTokens();
        boolean enabled = props.enabled() == null || props.enabled();
        return new ContextBudget(max, enabled, CharBasedTokenEstimator.codeDefault());
    }
}
