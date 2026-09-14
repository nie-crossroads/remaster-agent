package com.remasteragent.llm.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量模型「专用网关 / 专用 Key」的解析规则。
 *
 * <h2>为什么值得单独钉一个测试</h2>
 * <p>chat 与 embedding 往往不在同一个网关：本项目的 chat 走中转网关（只提供 chat 模型），
 * 向量模型跑在云厂商 MaaS 上，地址与 Key 都不一样。如果这两个字段的兜底逻辑写错，
 * 表现不是「报错」而是「静默用错网关」—— 请求打到不提供 {@code /embeddings} 的 chat 网关上，
 * 只是每次检索都失败，然后被 RAG 的「向量路失败降级」吞掉，最后看起来像「向量检索效果不好」。
 *
 * <p>所以这里把四条规则钉死：配了用专用的、留空回落到 chat 的、空串等同于留空、尾部斜杠要吃掉
 * （否则拼出 {@code //embeddings}，不少网关直接 404）。
 */
class LlmPropertiesEmbeddingEndpointTest {

    private static LlmProperties props(String embeddingBaseUrl, String embeddingApiKey) {
        return new LlmProperties(
                "https://chat.example.com/v1/", "chat-key", "chat-model", null,
                "text-embedding-x", 1024, embeddingBaseUrl, embeddingApiKey,
                60, 0, 0, 0, 0d, 0d, false);
    }

    @Test
    @DisplayName("未配专用网关/Key 时回落到 chat 的，且尾部斜杠被去掉")
    void fallsBackToChatEndpoint() {
        LlmProperties p = props(null, null);

        assertEquals("https://chat.example.com/v1", p.effectiveEmbeddingBaseUrl(),
                "chat 的地址带尾部斜杠，拼接前必须吃掉，否则会拼出 //embeddings");
        assertEquals("chat-key", p.effectiveEmbeddingApiKey());
        assertFalse(p.hasDedicatedEmbeddingEndpoint());
    }

    @Test
    @DisplayName("配了专用网关/Key 就用专用的")
    void usesDedicatedEndpoint() {
        LlmProperties p = props("https://maas.example.com/compatible-mode/v1", "maas-key");

        assertEquals("https://maas.example.com/compatible-mode/v1", p.effectiveEmbeddingBaseUrl());
        assertEquals("maas-key", p.effectiveEmbeddingApiKey());
        assertTrue(p.hasDedicatedEmbeddingEndpoint());
    }

    @Test
    @DisplayName("空串等同于留空（.env 里写了键但没填值时不该把地址变成空）")
    void blankIsTreatedAsAbsent() {
        LlmProperties p = props("   ", "");

        assertEquals("https://chat.example.com/v1", p.effectiveEmbeddingBaseUrl());
        assertEquals("chat-key", p.effectiveEmbeddingApiKey());
        assertFalse(p.hasDedicatedEmbeddingEndpoint());
    }

    @Test
    @DisplayName("dimensions 留空 = 0 = 请求里不带 dimensions 参数")
    void dimensionsAreOptional() {
        LlmProperties without = new LlmProperties(
                "https://chat.example.com/v1", "k", "m", null, "e", null, null, null, 60, 0, 0, 0, 0d, 0d, false);
        assertEquals(0, without.effectiveEmbeddingDimensions());

        assertEquals(1024, props(null, null).effectiveEmbeddingDimensions());
    }

    @Test
    @DisplayName("embedding-model 留空 = 向量路禁用（而不是抛异常）")
    void blankEmbeddingModelDisablesVectorPath() {
        LlmProperties disabled = new LlmProperties(
                "https://chat.example.com/v1", "k", "m", null, "  ", 1024, null, null, 60, 0, 0, 0, 0d, 0d, false);
        assertFalse(disabled.hasEmbeddingModel());

        assertTrue(props(null, null).hasEmbeddingModel());
    }

    @Test
    @DisplayName("chat 地址就是 null 时也不抛异常（探针场景），且重试相关的默认值稳定")
    void nullBaseUrlIsSafe() {
        LlmProperties p = new LlmProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);

        assertEquals("", p.effectiveEmbeddingBaseUrl());
        assertEquals(180, p.timeoutSeconds(), "超时默认 180 秒");
        assertEquals(2, p.maxRetries());
        assertEquals(3, p.totalHttpAttempts(), "重试 2 次 = 一次调用最多真正发出 3 次请求");
        assertEquals(2000, p.retryBackoffMillis(), "退避基准默认 2 秒");
        assertEquals(420, p.callBudgetSeconds(), "单次调用（含重试）总预算默认 420 秒");
    }
}
