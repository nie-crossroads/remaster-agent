package com.remasteragent.core.progress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProgressEvent} 的 JSON 形状契约测试。
 *
 * <h2>为什么会有这个测试</h2>
 * <p>它不是预防性写的，是被一个真实现象逼出来的：加了 {@code isHeartbeat()} 这个
 * {@code boolean} 派生方法之后，SSE 报文里凭空多出一个 {@code "heartbeat":false} ——
 * Jackson 把这个 {@code is} 开头的方法当成了 getter 属性。这与项目里
 * {@code VerifyResult.isGreen()} 导致指标静默清零是<b>同一个坑</b>，
 * 只不过这次落在一个跨进程传输的报文上：
 *
 * <ul>
 *   <li>ProgressEvent 要经 Redis Pub/Sub 从 Worker 传到 API，是两进程之间的契约；</li>
 *   <li>多出来的字段会被原样推给浏览器，前端类型里根本没声明它；</li>
 *   <li>它还可能被写进未来的 checkpoint / 事件归档，成为读不回去的历史数据。</li>
 * </ul>
 *
 * <p>用<b>严格</b> ObjectMapper（默认就开着 {@code FAIL_ON_UNKNOWN_PROPERTIES}）来测，
 * 等于把「报文形状必须恰好等于记录字段」这条契约钉死：谁再加一个派生 getter，
 * 这里立刻变红，而不是等到某天有人发现报文里多了一堆没人认识的键。
 */
class ProgressEventJsonShapeTest {

    /**
     * 与生产 {@code JsonCodec.defaultMapper()} <b>同配置，只差一个开关</b>：
     * 这里故意不关 {@code FAIL_ON_UNKNOWN_PROPERTIES}。
     *
     * <p>不能图省事直接用它——生产配置是刻意对未知字段宽松的（要能读旧版本写下的数据），
     * 拿它来测形状等于测了个寂寞：多出多少字段都不会报错。也不能用裸 {@code new ObjectMapper()}，
     * 它不认识 {@code Instant}，连序列化这一步都过不去。
     */
    private final ObjectMapper strict = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    @DisplayName("普通进度事件的 JSON 恰好是 8 个字段，派生方法不得混入")
    void progressEventHasExactlyTheRecordComponents() throws Exception {
        ProgressEvent event = ProgressEvent.nodeStatus(7L, 12L, "rewrite", "RUNNING", 1, "第 2 轮");

        JsonNode node = strict.readTree(strict.writeValueAsString(event));

        assertEquals(8, node.size(), "ProgressEvent 必须恰好是 8 个记录字段，实际: " + node);
        assertFalse(node.has("heartbeat"),
                "isHeartbeat() 是派生方法，标了 @JsonIgnore 才不会被序列化 —— 实测漏标过");
        assertTrue(node.has("type") && node.has("status") && node.has("attempt"));
    }

    @Test
    @DisplayName("严格反序列化能往返：说明报文里没有多余字段")
    void strictRoundTrip() throws Exception {
        ProgressEvent event = ProgressEvent.taskStatus(3L, "SUCCEEDED", "任务完成");

        ProgressEvent restored = strict.readValue(strict.writeValueAsString(event), ProgressEvent.class);

        assertEquals(event, restored);
    }

    @Test
    @DisplayName("心跳的 JSON 形状与普通事件一致（同一个记录，不该有两套形状）")
    void heartbeatHasTheSameShape() throws Exception {
        JsonNode node = strict.readTree(strict.writeValueAsString(ProgressEvent.heartbeat()));

        assertEquals(8, node.size());
        assertEquals(ProgressEvent.TYPE_HEARTBEAT, node.get("type").asText());
        assertEquals(ProgressEvent.NO_TASK, node.get("taskId").asLong());
    }

    @Test
    @DisplayName("@JsonIgnoreProperties 兜底：多出未知字段的报文仍可读（跨版本兼容）")
    void unknownPropertiesAreTolerated() {
        // 模拟「老版本发来的报文里带着 heartbeat 字段」—— 这正是修复前线上真实传输的形状
        String legacy = """
                {"taskId":1,"nodeId":null,"nodeKey":null,"type":"task_status",
                 "status":"RUNNING","attempt":0,"message":"msg","at":"2026-09-14T07:14:37Z",
                 "heartbeat":false}
                """;

        ProgressEvent restored = new com.remasteragent.core.codec.JsonCodec()
                .read(legacy, ProgressEvent.class)
                .orElseThrow();

        assertEquals("RUNNING", restored.status(),
                "多一个字段就整条事件读不出来，代价是「进度永久卡住」，比忽略一个字段大得多");
    }
}
