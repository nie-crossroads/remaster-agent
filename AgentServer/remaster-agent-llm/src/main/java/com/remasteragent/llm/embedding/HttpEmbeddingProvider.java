package com.remasteragent.llm.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remasteragent.llm.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * OpenAI 兼容 {@code /embeddings} 的向量实现。
 *
 * <h2>为什么手写 HTTP 而不用 LangChain4j 的 embedding 类</h2>
 * <p>项目里 chat 走 LangChain4j 是有理由的（它管住了消息抽象与重试）。但 embedding 的请求/响应
 * 形状极简（就是「一句话进、一个数组出」），而 {@code OpenAiEmbeddingModel} 在 1.x 的几个小版本间
 * 签名时有调整（构造器、返回类型、参数名），反而引入版本耦合。这里用 JDK 自带的
 * {@link HttpClient} 直连，零新增依赖、行为完全可控 —— 与「chat 用 LangChain4j」并不冲突，
 * 因为两处的复杂度不在一个量级。
 *
 * <p><b>{@code dimensions} 只在显式配置时才发送。</b>OpenAI 的 v3 系列支持靠该参数降维，
 * 但不少兼容网关/本地模型（如 bge-m3）不认识它，发了会直接报 400。所以默认不发，
 * 由调用方按自己的模型决定 —— 宁可让维度不匹配在写入时以一条清晰日志暴露，
 * 也不要默认发一个可能被拒的参数。
 */
public class HttpEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpEmbeddingProvider.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final String endpoint;
    private final String apiKey;
    private final String modelName;
    private final int dimensions;
    private final int timeoutSeconds;

    public HttpEmbeddingProvider(LlmProperties properties) {
        // 用「有效」地址与 Key：向量模型常和 chat 不在同一个网关（本项目就是 —— chat 走中转网关，
        // 向量走云厂商 MaaS）。属性层已经把「留空则复用 chat 的」这条兜底做掉了，
        // 所以这里不再各自判断，避免两处各写一套 fallback 逻辑后行为不一致。
        this.endpoint = properties.effectiveEmbeddingBaseUrl() + "/embeddings";
        this.apiKey = properties.effectiveEmbeddingApiKey();
        this.modelName = properties.embeddingModel();
        this.dimensions = properties.effectiveEmbeddingDimensions();
        this.timeoutSeconds = properties.timeoutSeconds();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .build();
    }

    @Override
    public String modelName() {
        return modelName;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public float[] embed(String text) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", modelName);
        body.put("input", text == null ? "" : text);
        if (dimensions > 0) {
            body.put("dimensions", dimensions);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body)))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("embedding 调用被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("embedding 调用异常: " + e.getMessage(), e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("embedding 调用失败 HTTP " + response.statusCode()
                    + "，响应: " + abbreviate(response.body(), 300));
        }
        return parseVector(response.body());
    }

    private float[] parseVector(String responseBody) {
        JsonNode vector;
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            vector = root.path("data").path(0).path("embedding");
        } catch (Exception e) {
            throw new IllegalStateException("embedding 响应无法解析: " + e.getMessage(), e);
        }
        if (!vector.isArray() || vector.isEmpty()) {
            throw new IllegalStateException("embedding 响应里没有向量，响应: " + abbreviate(responseBody, 300));
        }
        float[] result = new float[vector.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = (float) vector.get(i).asDouble();
        }
        if (dimensions > 0 && result.length != dimensions) {
            // 不抛异常：让写入环节以「维度不符」的方式显式失败，比在这里断言更贴近真实约束
            log.warn("embedding 实际维度 {} 与配置 {} 不一致；code_chunk.embedding 是 vector(1024)，写入会被拒绝",
                    result.length, dimensions);
        }
        return result;
    }

    private static String writeJson(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 embedding 请求失败", e);
        }
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }
}
