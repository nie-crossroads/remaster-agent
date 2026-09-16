package com.remasteragent.core.trace;

import com.remasteragent.core.codec.JsonCodec;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * span 落库出口的单测 —— 钉住<b>行形状</b>与<b>失败不影响任务</b>这两件事。
 *
 * <h2>为什么「行形状」值得单独测</h2>
 * <p>{@code trace_span} 是阶段 4 报表的唯一数据源。列错位（比如把 node_id 写进 task_id）
 * 不会报任何错，只会让报表按任务聚合时把耗时刻归到同一个桶里 ——
 * 而发现的时候已经跑了几十个任务，数据是脏的。所以这里把每一列都断言一遍。
 *
 * <h2>为什么「失败不影响任务」也必须测</h2>
 * <p>它在 OTel 的导出路径上跑。异常传出去的后果是导出线程静默死掉或疯狂重试，
 * 而这两种表现都不会有人立刻发现。本类用「表还没建」这个最真实的场景把它钉住。
 *
 * <p>用 {@code SimpleSpanProcessor} 而不是 {@code BatchSpanProcessor}：
 * 前者在 {@code end()} 的调用线程上当场导出，断言是确定的；
 * 后者要等一个攒批延迟，测试会开始随机变红。
 */
class JdbcSpanExporterTest {

    private JdbcTemplate jdbc;
    private JdbcSpanExporter exporter;
    private SdkTracerProvider provider;
    private TraceTracer tracer;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.batchUpdate(anyString(), anyList())).thenReturn(new int[]{1});
        exporter = new JdbcSpanExporter(jdbc, new JsonCodec());
        provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracer = new TraceTracer(io.opentelemetry.sdk.OpenTelemetrySdk.builder()
                .setTracerProvider(provider).build());
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    // ==================================================================
    // 行形状
    // ==================================================================

    @Test
    @DisplayName("任务 span 落库：归属列与状态列逐一对上，父 id 为空")
    void taskSpanRowShape() {
        Span span = tracer.startTask(7L, "E:/demo", "src/Demo.java", 21, null);
        TraceTracer.endOk(span);

        List<Object[]> rows = capturedRows();
        assertEquals(1, rows.size());
        Object[] row = rows.get(0);

        assertEquals(32, String.valueOf(row[0]).length(), "trace_id 是 32 位十六进制");
        assertEquals(16, String.valueOf(row[1]).length(), "span_id 是 16 位十六进制");
        assertNull(row[2], "根 span 的父 id 必须是 NULL —— OTel 给的是全零串，照抄进库"
                + "会让前端按父指针还原层级时去找一个不存在的父，而列里的值看起来一切正常");
        assertEquals(7L, row[3], "task_id 从 span 属性里取");
        assertNull(row[4], "任务级 span 不属于任何节点");
        assertEquals("task", row[5]);
        assertEquals("INTERNAL", row[6]);
        assertEquals("OK", row[7]);
        assertNotNull(row[8], "开始时间是 OTel 打点的时刻");
        assertNotNull(row[9], "结束时间");
        assertTrue(((Number) row[10]).longValue() >= 0, "耗时不能为负");
        assertTrue(String.valueOf(row[11]).contains("task.project_root"),
                "属性 JSON 要带上归属信息，实际 " + row[11]);
    }

    @Test
    @DisplayName("节点 span 落库：node_id / task_id 各就各位（错位不会报错，只会让报表归错组）")
    void nodeSpanRowShape() {
        Span task = tracer.startTask(7L, "E:/demo", "src/Demo.java", 21, null);
        String taskSpanId;
        try (Scope ignored = task.makeCurrent()) {
            Span node = tracer.startNode(7L, 42L, "verify:src/Demo.java", "VERIFY", 1);
            TraceTracer.endError(node, "编译不过");
            taskSpanId = task.getSpanContext().getSpanId();
        }
        TraceTracer.endOk(task);

        Map<String, Object[]> byName = capturedRows().stream()
                .collect(Collectors.toMap(r -> String.valueOf(r[5]), r -> r));

        Object[] nodeRow = byName.get("node:verify:src/Demo.java");
        assertEquals(7L, nodeRow[3], "task_id");
        assertEquals(42L, nodeRow[4], "node_id 必须落在 node 列 —— 写进 task 列会静默毁掉按任务聚合");
        assertEquals(taskSpanId, nodeRow[2], "父 id 是包含它的那个任务 span");
        assertEquals("ERROR", nodeRow[7]);
        assertTrue(String.valueOf(nodeRow[11]).contains("node.attempt"),
                "attempt 必须落进属性 —— 回退重写会产生同名节点，报表要靠它区分轮次");
        assertTrue(String.valueOf(nodeRow[11]).contains("\"node.attempt\":1"),
                "属性要保留数值类型；写成字符串会让聚合查询静默失配。实际: " + nodeRow[11]);
    }

    // ==================================================================
    // 失败处理
    // ==================================================================

    @Test
    @DisplayName("空批次：直接成功返回，连库都不碰")
    void emptyBatchSkipsDatabase() {
        CompletableResultCode result = exporter.export(List.of());

        assertTrue(result.isSuccess());
        verify(jdbc, never()).batchUpdate(anyString(), anyList());
    }

    @Test
    @DisplayName("表还没建（Flyway 没跑）：吞掉异常返回 FAILURE，绝不抛到导出线程上")
    void databaseFailureIsReportedNotThrown() {
        when(jdbc.batchUpdate(anyString(), anyList()))
                .thenThrow(new BadSqlGrammarException("write",
                        "INSERT INTO trace_span",
                        new SQLException("relation \"trace_span\" does not exist")));

        List<SpanData> spans = sampleSpans();

        CompletableResultCode result = assertDoesNotThrow(() -> exporter.export(spans),
                "抛出去会让导出线程静默死掉或疯狂重试，而这两种表现都不会有人立刻发现");
        assertFalse(result.isSuccess(), "失败要如实返回 FAILURE，不能假装成功");
    }

    @Test
    @DisplayName("flush / shutdown 都返回成功：没有本地缓冲，这是诚实的回答")
    void flushAndShutdownAreNoOps() {
        assertTrue(exporter.flush().isSuccess());
        assertTrue(exporter.shutdown().isSuccess());
    }

    // ------------------------------------------------------------------

    /** 造几条真实的 SpanData（用另一个收集型 exporter 取出来），供「导出失败」用例直接喂给被测对象。 */
    private List<SpanData> sampleSpans() {
        CollectingSpanExporter collector = new CollectingSpanExporter();
        try (SdkTracerProvider collectingProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(collector))
                .build()) {
            TraceTracer collectingTracer = new TraceTracer(
                    io.opentelemetry.sdk.OpenTelemetrySdk.builder()
                            .setTracerProvider(collectingProvider).build());
            TraceTracer.endOk(collectingTracer.startTask(7L, "E:/demo", "src/Demo.java", 21, null));
            return collector.spans();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> capturedRows() {
        ArgumentCaptor<List<Object[]>> captor = ArgumentCaptor.forClass(List.class);
        // 每个 span 结束都会单独导出一次（SimpleSpanProcessor 的行为），所以要收集全部批次再摊平。
        // 用 verify(...) 的严格一次会误报成「导出没发生」，而真正的问题在这个测试辅助里。
        verify(jdbc, atLeastOnce()).batchUpdate(anyString(), captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream).toList();
    }
}
