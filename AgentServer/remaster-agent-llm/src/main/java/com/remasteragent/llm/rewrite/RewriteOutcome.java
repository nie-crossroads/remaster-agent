package com.remasteragent.llm.rewrite;

import com.remasteragent.common.agent.RewriteProposal;

/**
 * 改写结果，含成本埋点需要的用量信息。
 *
 * <p>把 token 用量与耗时和产出一起返回，而不是让调用方另外去查 ——
 * 因为「一次调用」的成本信息只有在调用现场才拿得到，事后再想补是补不出来的。
 *
 * @param proposal         模型给出的结构化产出
 * @param model            实际使用的模型名
 * @param promptTokens     输入 token
 * @param completionTokens 输出 token
 * @param latencyMs        调用耗时
 * @param rawResponse      原始回复，解析失败时用于排查
 */
public record RewriteOutcome(
        RewriteProposal proposal,
        String model,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        String rawResponse
) {
}
