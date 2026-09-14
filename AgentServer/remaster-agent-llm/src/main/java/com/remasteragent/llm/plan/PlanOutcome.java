package com.remasteragent.llm.plan;

import com.remasteragent.common.agent.PlanResult;

/**
 * 一次规划调用的产出 —— 计划 + 用量。
 *
 * <p>与 {@code RewriteOutcome} 同构：把 token 用量一起带出来，调用方才能落 {@code llm_call} 表。
 * 规划是低频高阶调用，它那一次的成本占比不低，同样需要被计入。
 *
 * @param plan             解析出的迁移计划
 * @param model            实际使用的模型名
 * @param promptTokens     输入 token
 * @param completionTokens 输出 token
 * @param latencyMs        耗时（毫秒）
 */
public record PlanOutcome(
        PlanResult plan,
        String model,
        int promptTokens,
        int completionTokens,
        long latencyMs
) {
}
