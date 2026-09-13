package com.remasteragent.common.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * checkpoint 载荷的 JSON 形状契约测试。
 *
 * <h2>这个测试是怎么来的</h2>
 * <p>它不是预防性写的，是<b>被一个真实 bug 逼出来的</b>：{@code VerifyResult.isGreen()}
 * 是个 {@code boolean} 派生方法，Jackson 会把它当成 getter 一起序列化，于是写进
 * {@code dag_node.result} 的 JSON 里多出一个 {@code "green"} 字段。用默认配置的
 * ObjectMapper 反序列化时，这个「未知字段」直接抛异常，被 {@code DagScheduler} 的
 * catch 吞掉 —— 结果就是**任务明明成功，指标里编译通过率却是 0**。
 *
 * <p>最恶劣的地方在于它是静默的：任务状态是对的、日志是正常的，只有指标悄悄变错。
 * 而「量化指标」正是这个项目最核心的交付物，指标错了整个项目就失去了说服力。
 *
 * <h2>为什么用「严格 ObjectMapper」而不是 Spring Boot 的那个</h2>
 * <p>Spring Boot 默认关掉了 {@code FAIL_ON_UNKNOWN_PROPERTIES}，所以在生产里这个 bug
 * 不会抛异常 —— 但 checkpoint JSON 里会一直躺着一个多余的 {@code green} 字段。
 * 而 {@code DagScheduler} 接收的是任意注入的 ObjectMapper，格式契约不该依赖谁怎么配。
 * 用默认（严格）配置来测，等于把「JSON 形状必须等于记录字段」这条契约钉死。
 */
class CheckpointJsonShapeTest {

    private final ObjectMapper strict = new ObjectMapper();

    @Test
    @DisplayName("VerifyResult 的 JSON 恰好是 10 个字段，派生方法不得混入")
    void verifyResultJsonHasExactlyTheRecordComponents() throws Exception {
        VerifyResult result = new VerifyResult(true, 0, 4, 4, 0, 0, 0.62d, "", 1500L, false);

        JsonNode node = strict.readTree(strict.writeValueAsString(result));

        assertEquals(10, node.size(),
                "VerifyResult 的 JSON 必须恰好是 10 个记录字段，实际: " + node);
        assertFalse(node.has("green"),
                "isGreen() 是派生方法，不应被序列化；加了它会让严格反序列化直接失败");
        assertFalse(node.has("verificationGreen"));
        assertFalse(node.has("tests"), "hasTests() 不应被序列化");

        // 用严格配置往返一次 —— 这正是曾经失败的那一步
        VerifyResult restored = strict.readValue(strict.writeValueAsString(result), VerifyResult.class);
        assertEquals(result, restored);
    }

    @Test
    @DisplayName("@JsonIgnoreProperties 兜底：未知字段不会把旧 checkpoint 读坏")
    void unknownPropertiesAreTolerated() throws Exception {
        // 模拟「旧版本写下的 checkpoint 里带着 green 字段」
        String legacy = """
                {"compiled":true,"exitCode":0,"testsTotal":4,"testsPassed":4,"testsFailed":0,
                 "testsSkipped":0,"coverage":0.62,"failureExcerpt":"","durationMs":1500,
                 "timedOut":false,"green":true}
                """;

        VerifyResult restored = strict.readValue(legacy, VerifyResult.class);

        assertTrue(restored.compiled());
        assertTrue(restored.isGreen(), "派生判定应从 10 个字段重新算出来");
        assertEquals(0.62d, restored.coverage(), 1e-9);
    }

    @Test
    @DisplayName("REWRITE / ANALYZE 的 checkpoint 载荷同样能严格往返")
    void otherCheckpointPayloadsRoundTrip() throws Exception {
        RewriteResult rewrite = new RewriteResult(
                "src/main/java/com/example/Demo.java", "class Demo {}", "@@", "理由", "stub-model", 1);
        AnalyzeResult analyze = new AnalyzeResult(
                "src/main/java/com/example/Demo.java", "com.example", "Demo",
                List.of("Demo#hi"), "class Demo {}", "单方法");

        assertEquals(rewrite, strict.readValue(strict.writeValueAsString(rewrite), RewriteResult.class));
        assertEquals(analyze, strict.readValue(strict.writeValueAsString(analyze), AnalyzeResult.class));
    }
}
