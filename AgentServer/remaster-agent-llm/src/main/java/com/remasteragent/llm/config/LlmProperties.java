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
 * @param embeddingModel       代码 RAG 的向量模型；<b>留空则向量路禁用</b>，混合检索退化为
 *                             全文 + 符号两路。
 *                             注意它必须产出 1024 维向量，与 {@code code_chunk.embedding vector(1024)} 对齐。
 * @param embeddingDimensions  向量维度；留空（null）则请求里<b>不带</b> dimensions 参数。
 *                             OpenAI v3 系列靠它降维，但 bge-m3 等模型不认识该参数、发了会 400，
 *                             所以默认不发送，由使用者按自己的模型决定。
 * @param embeddingBaseUrl     向量模型专用网关地址；<b>留空则复用 {@code baseUrl}</b>。
 *                             <b>为什么单独给一个</b>：chat 与 embedding 常常不在同一个网关 ——
 *                             本项目的 chat 走中转网关，而向量模型是云厂商 MaaS 上的独立部署，
 *                             两者的地址与 Key 都不同。若只留一个地址，就只能二选一，
 *                             等于「配了 chat 就配不了真向量」，向量路会永远停在 Noop 上。
 * @param embeddingApiKey      向量模型专用 Key；留空则复用 {@code apiKey}。
 * @param timeoutSeconds       HTTP 单次请求超时。中转网关延迟波动大，实测首连可接近 10 秒，不要设太小。
 *                             但也别设得比网关自己的超时阈值大：上游（实测是 Cloudflare）超过阈值会直接
 *                             回一个 524，客户端等更久也拿不到内容，多等的那段纯粹是白等。
 * @param maxRetries           失败后最多<b>再</b>试几次。注意它现在由自研的 {@code LlmRetryExecutor} 消费，
 *                             SDK 内层的重试已被关闭 —— 两层重试会相乘（3 × 3 = 9 次），
 *                             而且内层重试对节点完全不可见，出问题时连日志都对不上。
 * @param retryBackoffMillis   重试前的退避基准：第 n 次失败后等 {@code base × 2^(n-1)}，封顶 30s。
 *                             <b>为什么要退避</b>：实测连着两次撞上同一个上游拥塞（两次都是 524），
 *                             不留间隔地重试等于把同样的请求立刻再送进同一个瓶颈。
 * @param callBudgetSeconds    单次调用（含全部重试）的总时间预算，超出即停止重试。
 *                             它是「最坏耗时」的硬上限 —— 没有它，上限就是「超时 × 尝试次数」这个乘积，
 *                             而那个数字会随着参数调整悄悄地膨胀到没有任何人预期的量级。
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
        String embeddingModel,
        Integer embeddingDimensions,
        String embeddingBaseUrl,
        String embeddingApiKey,
        Integer timeoutSeconds,
        Integer maxRetries,
        Integer retryBackoffMillis,
        Integer callBudgetSeconds,
        Double inputPricePerMillion,
        Double outputPricePerMillion,
        Boolean logRequests
) {

    public LlmProperties {
        timeoutSeconds = timeoutSeconds == null ? 180 : timeoutSeconds;
        maxRetries = maxRetries == null ? 2 : maxRetries;
        retryBackoffMillis = retryBackoffMillis == null ? 2000 : retryBackoffMillis;
        callBudgetSeconds = callBudgetSeconds == null ? 420 : callBudgetSeconds;
        inputPricePerMillion = inputPricePerMillion == null ? 0d : inputPricePerMillion;
        outputPricePerMillion = outputPricePerMillion == null ? 0d : outputPricePerMillion;
        logRequests = logRequests != null && logRequests;
    }

    /**
     * 一次调用最多会真正发出几次 HTTP 请求。
     *
     * <p>刻意把它做成派生方法而不是各处自己 {@code maxRetries + 1}：
     * 「重试 2 次」与「一共请求 3 次」是两个容易互相读错的说法，日志和文档里都该用同一个口径。
     */
    public int totalHttpAttempts() {
        return maxRetries + 1;
    }

    /** 改写节点实际使用的模型名。 */
    public String effectiveRewriteModel() {
        return (rewriteModel == null || rewriteModel.isBlank()) ? model : rewriteModel;
    }

    /** 改写节点是否真的用了独立模型 —— 日志里区分一下，避免误判成本异常。 */
    public boolean hasDedicatedRewriteModel() {
        return rewriteModel != null && !rewriteModel.isBlank() && !rewriteModel.equals(model);
    }

    /** 是否配置了 embedding 模型 —— 为 false 时向量检索路应被跳过，而不是抛异常。 */
    public boolean hasEmbeddingModel() {
        return embeddingModel != null && !embeddingModel.isBlank();
    }

    /** 配置的向量维度；0 表示未指定（请求里不带 dimensions 参数）。 */
    public int effectiveEmbeddingDimensions() {
        return embeddingDimensions == null ? 0 : embeddingDimensions;
    }

    /**
     * 向量模型实际使用的网关地址：配了专用地址就用专用的，否则复用 chat 的。
     *
     * <p>返回时去掉尾部斜杠，调用方拼 {@code /embeddings} 时才不会出现 {@code //embeddings}
     * （部分网关对双斜杠直接 404）。
     */
    public String effectiveEmbeddingBaseUrl() {
        String url = (embeddingBaseUrl == null || embeddingBaseUrl.isBlank()) ? baseUrl : embeddingBaseUrl;
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 向量模型实际使用的 Key；配了专用 Key 就用专用的，否则复用 chat 的。 */
    public String effectiveEmbeddingApiKey() {
        return (embeddingApiKey == null || embeddingApiKey.isBlank()) ? apiKey : embeddingApiKey;
    }

    /** 向量调用是否走了与 chat 不同的网关 —— 排查「配了模型却还在报 chat 的错」时第一个该看的。 */
    public boolean hasDedicatedEmbeddingEndpoint() {
        return embeddingBaseUrl != null && !embeddingBaseUrl.isBlank()
                && !effectiveEmbeddingBaseUrl().equals(stripTrailingSlash(baseUrl));
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
