package com.remasteragent.core.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remasteragent.core.progress.ProgressEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JsonCodec} 的契约测试 —— 这组用例的存在理由是一次真实的生产故障。
 *
 * <h2>故障是什么</h2>
 * <p>Worker 进程刻意不引 {@code spring-boot-starter-web}（它不对外提供 HTTP）。
 * 但 {@code Jackson2ObjectMapperBuilder} 属于 {@code spring-web}，
 * 于是 Spring Boot 那三个会产出 {@code ObjectMapper} 的自动配置全部退化为「不匹配」，
 * 整个容器里<b>一个 ObjectMapper Bean 都没有</b> → {@code DagScheduler} 启动失败、
 * Worker 根本起不来。
 *
 * <p>更隐蔽的是第二层：即使补一个裸 {@code new ObjectMapper()}，
 * 它不认识 {@code java.time}，而 {@link ProgressEvent} 带 {@code Instant}。
 * 发布进度事件时会抛 {@code InvalidDefinitionException}，然后被
 * 「进度推送失败不影响任务」的兜底 {@code catch} 吞掉 ——
 * 表现是<b>任务全绿、日志干净、页面永远不动</b>。
 *
 * <h2>所以这组用例守的是三条线</h2>
 * <ol>
 *   <li>时间类型必须能序列化（{@link #instantSerializesAsIsoString()} 与
 *       {@link #bareObjectMapperCannotHandleInstant()} 成对存在：后者证明前者不是多余的）</li>
 *   <li>读必须宽容（历史 checkpoint、跨进程版本不一致）</li>
 *   <li>写必须稳定（形状契约由 {@code CheckpointJsonShapeTest} 另行钉死）</li>
 * </ol>
 */
class JsonCodecTest {

    private final JsonCodec codec = new JsonCodec();

    // ------------------------------------------------------------------
    // 时间类型 —— 故障的直接现场
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Instant 序列化为 ISO-8601 字符串，而不是数字时间戳")
    void instantSerializesAsIsoString() throws Exception {
        String json = codec.write(ProgressEvent.taskStatus(1L, "RUNNING", "开始"));

        JsonNode at = codec.mapper().readTree(json).get("at");
        assertTrue(at != null && at.isTextual(),
                "at 必须是字符串。写成数字时间戳在跨语言、看日志、前端 Date 解析三处都是负资产，实际: " + json);
        assertTrue(at.asText().endsWith("Z"),
                "应以 Z 结尾表示 UTC，避免同一份数据在不同时区机器上被解读成不同时刻，实际: " + at);
    }

    @Test
    @DisplayName("ProgressEvent 能往返 —— 它要跨进程经 Redis 传到 API 进程")
    void progressEventRoundTrips() {
        ProgressEvent event = ProgressEvent.nodeStatus(9L, 33L, "rewrite", "SUCCEEDED", 1, "第 2 轮");

        String json = codec.write(event);
        ProgressEvent restored = codec.read(json, ProgressEvent.class).orElseThrow();

        assertEquals(event, restored);
        assertEquals(event.at(), restored.at(), "时间必须精确到纳秒不丢，否则前端排序会错乱");
    }

    @Test
    @DisplayName("裸 ObjectMapper 处理不了 Instant —— 这就是本类必须存在的原因")
    void bareObjectMapperCannotHandleInstant() {
        ProgressEvent event = ProgressEvent.taskStatus(1L, "RUNNING", "开始");

        // 钉住这个事实，而不是钉住某段实现：Future 有人觉得 JsonCodec 是多余的一层
        // 想直接注入 ObjectMapper 时，这条用例会告诉他为什么不行。
        // 注意 Jackson 不会通过 SPI 自动注册模块，只有 findAndRegisterModules() 才会 ——
        // 所以裸 new 出来的 mapper 一定不认识 java.time。
        assertThrows(Exception.class, () -> new ObjectMapper().writeValueAsString(event));
    }

    // ------------------------------------------------------------------
    // 读：宽容
    // ------------------------------------------------------------------

    @Test
    @DisplayName("读入未知字段不报错 —— 旧 checkpoint 要能被新代码读出来")
    void unknownFieldsAreIgnored() {
        String json = """
                {"at":"2026-09-10T12:00:00Z","type":"task_status","taskId":1,
                 "status":"RUNNING","attempt":0,"futureField":"以后才加的","legacyField":42}
                """;

        ProgressEvent event = codec.read(json, ProgressEvent.class).orElseThrow();

        assertEquals(1L, event.taskId());
        assertEquals("RUNNING", event.status());
    }

    @Test
    @DisplayName("读入坏数据返回 empty 而不是抛异常 —— 一条坏 checkpoint 不该让任务卡死")
    void malformedInputDegradesToEmpty() {
        assertTrue(codec.read((String) null, ProgressEvent.class).isEmpty());
        assertTrue(codec.read("", ProgressEvent.class).isEmpty());
        assertTrue(codec.read("   ", ProgressEvent.class).isEmpty());
        assertTrue(codec.read("{ 这不是 JSON", ProgressEvent.class).isEmpty());
        assertTrue(codec.read("[]", ProgressEvent.class).isEmpty(), "类型不匹配也应降级");
        assertTrue(codec.read(new byte[0], ProgressEvent.class).isEmpty());
    }

    @Test
    @DisplayName("字节数组入参：Redis 消息体走的就是这条路")
    void byteArrayInputIsSupported() {
        byte[] body = codec.write(ProgressEvent.taskMetrics(5L, "{\"llmCalls\":2}")).getBytes();

        Optional<ProgressEvent> parsed = codec.read(body, ProgressEvent.class);

        assertTrue(parsed.isPresent());
        assertEquals(5L, parsed.get().taskId());
    }

    // ------------------------------------------------------------------
    // 写：稳定
    // ------------------------------------------------------------------

    @Test
    @DisplayName("写 null 返回 null，不抛异常")
    void writingNullIsHarmless() {
        assertNull(codec.write(null));
    }

    @Test
    @DisplayName("普通 Map 的写入保持不变 —— 本类不改变基础序列化行为")
    void plainDataIsUnaffected() {
        assertEquals("{\"a\":1}", codec.write(Map.of("a", 1)));
    }

    @Test
    @DisplayName("defaultMapper 与容器装配的实例行为一致 —— 测试与生产不能各用一套配置")
    void defaultMapperMatchesInstanceBehavior() throws Exception {
        ProgressEvent event = ProgressEvent.taskStatus(2L, "PENDING", "排队中");

        assertEquals(codec.write(event), JsonCodec.defaultMapper().writeValueAsString(event));
    }

    @Test
    @DisplayName("逃生口返回同一个 mapper 实例，而不是每次新建")
    void mapperIsTheSameInstance() {
        assertSame(codec.mapper(), codec.mapper(),
                "每次调用都新建 mapper 会悄悄吃掉性能：mapper 很重，且会丢掉已编译的序列化器缓存");
    }

    @Test
    @DisplayName("Instant 的读写精度：秒以下不丢")
    void instantPrecisionIsPreserved() {
        Instant precise = Instant.ofEpochMilli(1_700_000_000_123L);
        ProgressEvent event = new ProgressEvent(1L, null, null, "task_status", "RUNNING", 0, "", precise);

        ProgressEvent restored = codec.read(codec.write(event), ProgressEvent.class).orElseThrow();

        assertEquals(precise, restored.at());
    }
}
