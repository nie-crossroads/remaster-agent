package com.remasteragent.core.trace;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 埋点入口的单测 —— 钉住 span 的<b>形状</b>，而不是「代码里有没有调 setAttribute」。
 *
 * <h2>为什么这些断言值得写</h2>
 * <p>埋点最典型的失效方式不是抛异常，而是<b>静默变形</b>：父子关系丢了、名字改了、
 * 状态码混了。它们的共同后果是「链路图上看不出来」，而代码看起来完全正常。
 * 本项目的报表（阶段 4 的耗时/成本）直接建立在这套形状上，
 * 所以形状本身必须是一条被测试守住的契约。
 *
 * <p>另外两条具体契约在这里被钉死：
 * <ol>
 *   <li><b>span 必须恰好 end 一次。</b> 建了不 end，它永远不被导出（表现成「埋点没生效」）；
 *       end 两次会重复导出。所以这里断言「导出条数 == 期望条数」而不只是「存在某条」。</li>
 *   <li><b>跨进程的父子关系靠 traceparent，不靠时间戳。</b> 用给定的 traceparent 起任务 span，
 *       它必须落在<b>那条</b> trace 下面 —— 这就是「全链路」四个字能不能兑现的地方。</li>
 * </ol>
 */
class TraceTracerTest {

    private CollectingSpanExporter exporter;
    private SdkTracerProvider provider;
    private TraceTracer tracer;

    @BeforeEach
    void setUp() {
        exporter = new CollectingSpanExporter();
        provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        tracer = new TraceTracer(openTelemetry);
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    // ==================================================================
    // 任务 span
    // ==================================================================

    @Test
    @DisplayName("任务 span：名字、归属属性、根地位都对")
    void taskSpanCarriesIdentity() {
        Span span = tracer.startTask(7L, "E:/demo", "src/Demo.java", 21, null);
        TraceTracer.endOk(span);

        SpanData data = exporter.single("task");
        assertEquals(7L, data.getAttributes().get(AttributeKey.longKey("task.id")));
        assertEquals("E:/demo", data.getAttributes().get(AttributeKey.stringKey("task.project_root")));
        assertEquals("src/Demo.java", data.getAttributes().get(AttributeKey.stringKey("task.entry_file")));
        assertEquals(21L, data.getAttributes().get(AttributeKey.longKey("task.target_jdk")));
        assertTrue(data.getParentSpanId() == null || data.getParentSpanId().isEmpty()
                        || data.getParentSpanId().chars().allMatch(c -> c == '0'),
                "没有上游 traceparent 时它是根 span（父 id 为空或全零）");
        assertEquals(StatusCode.OK, data.getStatus().getStatusCode());
    }

    @Test
    @DisplayName("给一个 traceparent：任务 span 落在【那条】trace 下面（跨进程链路的落点）")
    void taskSpanJoinsUpstreamTrace() {
        String upstreamTraceId = "0af7651916cd43dd8448eb211c80319c";
        String upstreamSpanId = "b7ad6b7169203331";
        String traceparent = "00-" + upstreamTraceId + "-" + upstreamSpanId + "-01";

        Span span = tracer.startTask(7L, "E:/demo", "src/Demo.java", 21, traceparent);
        TraceTracer.endOk(span);

        SpanData data = exporter.single("task");
        assertEquals(upstreamTraceId, data.getTraceId(), "必须复用上游 trace id，而不是自起一条");
        assertEquals(upstreamSpanId, data.getParentSpanId(), "上游 span 必须是它的父");
    }

    @Test
    @DisplayName("traceparent 畸形：自起一条 trace，绝不抛异常（追踪断了不该让任务跑不动）")
    void malformedTraceparentDegradesToNewTrace() {
        Span span = tracer.startTask(7L, "E:/demo", "src/Demo.java", 21, "不是-traceparent");
        TraceTracer.endOk(span);

        SpanData data = exporter.single("task");
        assertFalse(data.getTraceId().equals("00000000000000000000000000000000"),
                "降级不等于作废：应该拿到一条自己的有效 trace");
        assertEquals(32, data.getTraceId().length());
    }

    // ==================================================================
    // 子 span 的父子关系
    // ==================================================================

    @Test
    @DisplayName("节点/模型/沙箱 span 都挂在当前上下文（任务）下面，共用同一个 trace id")
    void childSpansNestUnderTask() {
        Span task = tracer.startTask(7L, "E:/demo", "src/Demo.java", 21, null);
        try (Scope ignored = task.makeCurrent()) {
            Span node = tracer.startNode(7L, 42L, "rewrite:src/Demo.java", "REWRITE", 0);
            try (Scope nodeScope = node.makeCurrent()) {
                Span llm = tracer.startLlm(7L, 42L, "REWRITE");
                TraceTracer.endOk(llm);
                Span sandbox = tracer.startSandbox(7L, 42L, "mvn -o test");
                TraceTracer.endOk(sandbox);
            }
            TraceTracer.endOk(node);
        }
        TraceTracer.endOk(task);

        SpanData taskData = exporter.single("task");
        SpanData nodeData = exporter.single("node:rewrite:src/Demo.java");
        SpanData llmData = exporter.single("llm:REWRITE");
        SpanData sandboxData = exporter.single("sandbox:mvn");

        assertEquals(taskData.getTraceId(), nodeData.getTraceId());
        assertEquals(taskData.getSpanId(), nodeData.getParentSpanId(), "节点挂在任务下");
        assertEquals(nodeData.getSpanId(), llmData.getParentSpanId(), "模型调用挂在节点下");
        assertEquals(nodeData.getSpanId(), sandboxData.getParentSpanId(), "沙箱执行挂在节点下");
        assertEquals(4, exporter.spans().size(), "恰好 4 条 —— 多一条意味着某个 span 被 end 了两次");
    }

    @Test
    @DisplayName("非记录型 span（埋点关掉时就是这个样子）：currentTraceId 为 null，端到端不炸")
    void noopProviderYieldsNoTraceId() {
        Span span = TraceTracer.NOOP.startTask(7L, "E:/demo", "src/Demo.java", 21, null);
        try (Scope ignored = span.makeCurrent()) {
            assertNull(TraceTracer.currentTraceId(),
                    "采样关闭时它必须为空 —— llm_call.trace_id 就是靠这个判定该不该落值");
            assertNull(TraceTracer.currentSpanId());
        }
        // 空实现上的所有操作都必须安全，包括这些 end*
        TraceTracer.endOk(span);
        TraceTracer.endError(span, "随便");
        TraceTracer.endUnset(span);
        TraceTracer.endException(span, new IllegalStateException("随便"));
    }

    @Test
    @DisplayName("end* 是【恰好一次】的出口：null 安全，状态码按语义落")
    void endMethodsSetStatusAndTolerateNull() {
        TraceTracer.endOk(null);
        TraceTracer.endError(null, "x");
        TraceTracer.endUnset(null);
        TraceTracer.endException(null, new IllegalStateException("x"));

        Span ok = tracer.startApi("POST", "/api/tasks");
        TraceTracer.endOk(ok);
        Span error = tracer.startApi("POST", "/api/tasks/9/cancel");
        TraceTracer.endError(error, "任务已是终态");
        Span suspended = tracer.startNode(1L, 2L, "gate:src/Demo.java", "GATE", 0);
        TraceTracer.endUnset(suspended);
        Span boom = tracer.startNode(1L, 3L, "verify:src/Demo.java", "VERIFY", 0);
        TraceTracer.endException(boom, new IllegalStateException("沙箱起不来"));

        assertEquals(StatusCode.OK, exporter.single("api:POST /api/tasks").getStatus().getStatusCode());
        assertEquals(StatusCode.ERROR,
                exporter.single("api:POST /api/tasks/9/cancel").getStatus().getStatusCode());
        assertEquals(StatusCode.UNSET,
                exporter.single("node:gate:src/Demo.java").getStatus().getStatusCode(),
                "GATE 挂起走 endUnset：既非成功也非失败，硬塞进任何一档都会让出错率失真");
        assertEquals(StatusCode.ERROR,
                exporter.single("node:verify:src/Demo.java").getStatus().getStatusCode());
        assertEquals(4, exporter.spans().size(), "恰好 4 条 —— 每个 span 只 end 了一次");
    }

    @Test
    @DisplayName("endError 与 endException 都是 ERROR，但只有异常会记下堆栈事件")
    void onlyExceptionRecordsStackTrace() {
        Span business = tracer.startNode(1L, 2L, "verify:a.java", "VERIFY", 0);
        TraceTracer.endError(business, "编译不过");
        Span system = tracer.startNode(1L, 3L, "verify:b.java", "VERIFY", 0);
        TraceTracer.endException(system, new IllegalStateException("连接池已关闭"));

        assertEquals(0, exporter.single("node:verify:a.java").getEvents().size(),
                "业务失败（编译不过）不该记堆栈 —— 那是正常路径的一部分");
        assertEquals(1, exporter.single("node:verify:b.java").getEvents().size(),
                "系统故障要把堆栈带进 span，否则只剩一句没有上下文的 message");
    }

    @Test
    @DisplayName("currentTraceId 只在有效 span 内部才给值")
    void currentTraceIdIsEmptyOutsideSpan() {
        assertNull(TraceTracer.currentTraceId(), "不在任何 span 里时就该是 null");
        Span span = tracer.startTask(7L, "E:/demo", "src/Demo.java", 21, null);
        try (Scope ignored = span.makeCurrent()) {
            assertEquals(span.getSpanContext().getTraceId(), TraceTracer.currentTraceId());
            assertEquals(span.getSpanContext().getSpanId(), TraceTracer.currentSpanId());
        }
        TraceTracer.endOk(span);
    }

    @Test
    @DisplayName("沙箱 span 的名字只取命令第一个词 —— 完整命令行会毁掉时间轴的可读性")
    void sandboxSpanNameUsesFirstWordOnly() {
        Span span = tracer.startSandbox(7L, 42L,
                "mvn -o -B -Dfile.encoding=UTF-8 test org.jacoco:jacoco-maven-plugin:report");
        TraceTracer.endOk(span);

        SpanData data = exporter.single("sandbox:mvn");
        assertEquals("mvn -o -B -Dfile.encoding=UTF-8 test org.jacoco:jacoco-maven-plugin:report",
                data.getAttributes().get(AttributeKey.stringKey("sandbox.command")),
                "完整命令留在属性里，供排查时看");
        assertTrue(data.getName().length() < 40, "名字必须短：每次 SET 属性都读它，长名字会毁掉时间轴");
    }
}
