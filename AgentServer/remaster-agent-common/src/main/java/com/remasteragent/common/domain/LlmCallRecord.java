package com.remasteragent.common.domain;

import java.time.Instant;

/**
 * 单次 LLM 调用明细 —— 成本治理的数据基础。
 *
 * <p>每调用一次就落一行，不做聚合。原因很直接：聚合之后就没法回答
 * 「哪个节点最烧钱」「回退重写让成本涨了多少」这类问题了，而这两个问题恰好是面试高频。
 *
 * @param id                 自增主键
 * @param taskId             所属任务，允许为空（如探针调用）
 * @param nodeId             触发调用的节点
 * @param model              实际使用的模型名（多模型路由下会与默认模型不同）
 * @param purpose            调用用途，如 ANALYZE / REWRITE / VALIDATE
 * @param promptTokens       prompt token 数
 * @param completionTokens   completion token 数
 * @param cost               按单价表估算的成本
 * @param latencyMs          耗时
 * @param traceId            链路追踪 id，阶段 3 接 OTel 后用于串联全链路
 * @param createdAt          调用时间
 */
public record LlmCallRecord(
        Long id,
        Long taskId,
        Long nodeId,
        String model,
        String purpose,
        int promptTokens,
        int completionTokens,
        double cost,
        long latencyMs,
        String traceId,
        Instant createdAt
) {
}
