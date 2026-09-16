package com.remasteragent.core.trace;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨进程链路上下文的单测。
 *
 * <p>这一段代码的失效方式非常安静：traceparent 写错了、解析失败被悄悄吞掉，
 * 结果只是「Worker 那边自成一条 trace」—— 没有任何人收到报错，
 * 只会在某天查链路时发现前半段不见了。所以这里把注入与提取两侧都钉死。
 *
 * <p>同时钉住<b>降级</b>：一个畸形的 traceparent 只能导致「这条 trace 从 Worker 开始」，
 * 绝不能让投递或任务执行失败。观测坏了不该影响被观测的东西。
 */
class TracePropagationTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String SPAN_ID = "b7ad6b7169203331";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + SPAN_ID + "-01";

    private SdkTracerProvider provider;
    private OpenTelemetry openTelemetry;

    @BeforeEach
    void setUp() {
        provider = SdkTracerProvider.builder().build();
        openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @Test
    @DisplayName("往返：注入出来的 traceparent 能被解析回同一个上下文")
    void roundTripKeepsIdentity() {
        Span span = openTelemetry.getTracer("test").spanBuilder("upstream-request").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            String traceparent = TracePropagation.currentTraceparent();
            assertNotNull(traceparent, "有有效 span 在上下文里时必须能注入出 traceparent");
            assertTrue(traceparent.startsWith("00-" + span.getSpanContext().getTraceId()),
                    "格式必须是 W3C traceparent，实际 " + traceparent);

            Context extracted = TracePropagation.extract(traceparent);
            assertNotNull(extracted);
            assertEquals(span.getSpanContext().getTraceId(),
                    Span.fromContext(extracted).getSpanContext().getTraceId());
            assertEquals(span.getSpanContext().getSpanId(),
                    Span.fromContext(extracted).getSpanContext().getSpanId());
        } finally {
            span.end();
        }
    }

    @Test
    @DisplayName("没有有效 span 时注入返回 null —— 调用方据此不带该字段")
    void noSpanMeansNoTraceparent() {
        assertNull(TracePropagation.currentTraceparent());
    }

    @Test
    @DisplayName("解析失败一律降级为 null，绝不抛异常（追踪断了不该让任务跑不动）")
    void malformedValuesDegradeToNull() {
        assertNull(TracePropagation.extract(null));
        assertNull(TracePropagation.extract(""));
        assertNull(TracePropagation.extract("   "));
        assertNull(TracePropagation.extract("不是-traceparent"));
        assertNull(TracePropagation.extract("00-abc-def-01"), "位数不对");
        assertNull(TracePropagation.extract("ff-" + TRACE_ID + "-" + SPAN_ID + "-01"),
                "版本号 ff 是规范里明确保留的非法值");
        assertNull(TracePropagation.extract("zz-" + TRACE_ID + "-" + SPAN_ID + "-01"), "非十六进制");
    }

    @Test
    @DisplayName("格式合法但 id 全零：必须返回 null，不能放行一条挂在无效父节点下的孤立 span")
    void allZeroIdsAreRejected() {
        String zeroTrace = "00-" + "0".repeat(32) + "-" + "0".repeat(16) + "-01";
        assertNull(TracePropagation.extract(zeroTrace),
                "解析不抛异常 ≠ 拿到了有效上下文；放行它会让排查时以为链路断了，其实是脏数据被放进来");
    }

    @Test
    @DisplayName("字段名就是 W3C 的标准名，不换成自造名")
    void fieldNameIsStandard() {
        assertEquals("traceparent", TracePropagation.TRACEPARENT);
        assertNotEquals("trace_parent", TracePropagation.TRACEPARENT);
        assertNotEquals("X-Trace-Id", TracePropagation.TRACEPARENT);
    }

    @Test
    @DisplayName("给定 traceparent 解析出的上下文，带的就是上游那个 span —— 它是 Worker 侧 span 的父")
    void extractedContextCarriesUpstreamSpan() {
        Context parent = TracePropagation.extract(TRACEPARENT);
        assertNotNull(parent);

        Span upstream = Span.fromContext(parent);
        assertEquals(TRACE_ID, upstream.getSpanContext().getTraceId());
        assertEquals(SPAN_ID, upstream.getSpanContext().getSpanId());

        // 用它作父起一个 span：trace id 必须沿用，而不是另起一条 ——
        // 这就是「API 接单那一段和 Worker 干活那一段是同一条链路」的全部依据
        Span child = openTelemetry.getTracer("test").spanBuilder("worker-side-root")
                .setParent(parent).startSpan();
        try {
            assertEquals(TRACE_ID, child.getSpanContext().getTraceId(),
                    "丢了它，Worker 侧会自成一条孤立 trace，且不会报任何错");
        } finally {
            child.end();
        }
    }
}
