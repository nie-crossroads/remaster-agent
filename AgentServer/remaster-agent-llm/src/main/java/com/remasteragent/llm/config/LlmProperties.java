package com.remasteragent.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 大模型接入配置。
 *
 * <p>全部字段都可从外部覆盖（.env / 环境变量 / 命令行），代码里不出现任何模型名或密钥字面量。
 * 这条纪律的实际价值在排查故障时才体现：换模型、切网关、改超时都不用重新打包。
 *
 * @param baseUrl              OpenAI 兼容网关地址，如 {@code https://example.com/v1}
 * @param apiKey               API Key
 * @param model                默认模型
 * @param rewriteModel         改写节点专用模型；留空则与默认模型相同。
 *                             <b>为什么单独留一个</b>：改写是整条流水线里调用最频繁的节点，
 *                             用高档模型跑高频节点会让单任务成本失控，而改写的难度其实低于规划。
 * @param timeoutSeconds       HTTP 超时。中转网关延迟波动大，实测首连可接近 10 秒，不要设太小
 * @param maxRetries           失败重试次数
 * @param inputPricePerMillion  输入 token 单价（每百万），用于成本估算
 * @param outputPricePerMillion 输出 token 单价（每百万）
 * @param logRequests          是否打印完整请求体（会带代码内容，排查时才开）
 */
@ConfigurationProperties(prefix = "remaster.llm")
public record LlmProperties(
        String baseUrl,
        String apiKey,
        String model,
        String rewriteModel,
        Integer timeoutSeconds,
        Integer maxRetries,
        Double inputPricePerMillion,
        Double outputPricePerMillion,
        Boolean logRequests
) {

    public LlmProperties {
        timeoutSeconds = timeoutSeconds == null ? 180 : timeoutSeconds;
        maxRetries = maxRetries == null ? 2 : maxRetries;
        inputPricePerMillion = inputPricePerMillion == null ? 0d : inputPricePerMillion;
        outputPricePerMillion = outputPricePerMillion == null ? 0d : outputPricePerMillion;
        logRequests = logRequests != null && logRequests;
    }

    /** 改写节点实际使用的模型名。 */
    public String effectiveRewriteModel() {
        return (rewriteModel == null || rewriteModel.isBlank()) ? model : rewriteModel;
    }

    /** 改写节点是否真的用了独立模型 —— 日志里区分一下，避免误判成本异常。 */
    public boolean hasDedicatedRewriteModel() {
        return rewriteModel != null && !rewriteModel.isBlank() && !rewriteModel.equals(model);
    }
}
