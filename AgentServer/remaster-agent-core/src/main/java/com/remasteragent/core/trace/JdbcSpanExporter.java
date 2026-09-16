package com.remasteragent.core.trace;

import com.remasteragent.core.codec.JsonCodec;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 把 span 写进 PostgreSQL —— 本项目对「span 往哪去」的回答。
 *
 * <h2>为什么不用 OTLP exporter</h2>
 * <p>OTLP 是标准答案，但它要求演示环境额外跑一个 collector（Jaeger / Tempo）。
 * 对一个「别人拿到仓库要能一键跑起来」的作品集项目来说，这个门槛的代价大于收益 ——
 * 而本项目<b>本来就有 PostgreSQL</b>，span 落进去立刻就能用 SQL 查，
 * 阶段 4 的成本/耗时报表也直接建立在它上面。
 *
 * <p>更重要的是：这不改变任何调用点。埋点全部走 OTel 的标准 API，
 * 想接 Jaeger 时只需在 {@code TraceConfig} 里多挂一个 OTLP 的 {@code SpanProcessor}，
 * {@code DagScheduler} 与各节点一行都不用动 —— 这是选 OTel 而不是自研埋点的全部理由。
 *
 * <h2>写库这件事绝不能把任务搞挂</h2>
 * <p>它在 OTel 的导出线程上跑，异常传出去只会得到一个静默的失败日志。
 * 所以这里所有异常都就地吞掉并返回 {@code FAILURE}：追踪是观测，
 * 观测坏了不该影响被观测的东西 —— 这个项目里已经有一条同样的先例
 * （{@code DagScheduler.publish} 里对进度推送的兜底）。
 */
public class JdbcSpanExporter implements SpanExporter {

    private static final Logger log = LoggerFactory.getLogger(JdbcSpanExporter.class);

    private static final String INSERT_SQL = """
            INSERT INTO trace_span (trace_id, span_id, parent_span_id, task_id, node_id,
                                    name, kind, status, start_at, end_at, duration_ms, attributes)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            """;

    /** span 上承载 task / node 归属的属性键，与 {@link TraceTracer} 里设置的保持一致。 */
    private static final String ATTR_TASK_ID = "task.id";
    private static final String ATTR_NODE_ID = "node.id";

    private final JdbcTemplate jdbc;
    private final JsonCodec json;

    public JdbcSpanExporter(JdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        if (spans == null || spans.isEmpty()) {
            return CompletableResultCode.ofSuccess();
        }
        try {
            List<Object[]> rows = spans.stream().map(this::toRow).toList();
            jdbc.batchUpdate(INSERT_SQL, rows);
            return CompletableResultCode.ofSuccess();
        } catch (Exception e) {
            // 表还没建（Flyway 没跑）、库暂时不可用、字段超长……全都在这里兜住。
            // 记 WARN 而不是 ERROR：追踪断掉是一件值得知道的事，但不是故障。
            log.warn("导出 {} 条 span 到 trace_span 失败（追踪数据缺失，不影响任务执行）: {}",
                    spans.size(), e.getMessage());
            return CompletableResultCode.ofFailure();
        }
    }

    @Override
    public CompletableResultCode flush() {
        // 没有本地缓冲：每批都是当场写库。返回成功是诚实的回答，不是敷衍。
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    private Object[] toRow(SpanData span) {
        long startNanos = span.getStartEpochNanos();
        long endNanos = span.getEndEpochNanos();
        Instant startAt = toInstant(startNanos);
        // endNanos 为 0 表示这个 span 还没结束就被导出（进程被杀那一类）——
        // 这时写 null 而不是写一个假的当前时间，否则报表会把它算成一段真实耗时
        Instant endAt = endNanos <= 0 ? null : toInstant(endNanos);
        Long durationMs = endAt == null ? null : TimeUnit.NANOSECONDS.toMillis(endNanos - startNanos);

        return new Object[]{
                span.getSpanContext().getTraceId(),
                span.getSpanContext().getSpanId(),
                parentSpanId(span),
                longAttribute(span, ATTR_TASK_ID),
                longAttribute(span, ATTR_NODE_ID),
                span.getName(),
                span.getKind() == null ? SpanKind.INTERNAL.name() : span.getKind().name(),
                span.getStatus().getStatusCode().name(),
                Timestamp.from(startAt),
                endAt == null ? null : Timestamp.from(endAt),
                durationMs,
                toAttributesJson(span.getAttributes())
        };
    }

    /**
     * 父 span id。
     *
     * <p>用 {@code parentSpanId} 而不是 {@code parentSpanContext.getSpanId()}：后者在
     * 「父已结束、上下文被回收」的场景下是空的。而 {@code SpanData.parentSpanId()}
     * 是导出时固化下来的字符串，不依赖任何外部状态。
     *
     * <p><b>根 span 要写成 NULL，而不是全零串。</b> OTels SDK 对没有父的 span 会给出
     * {@code "0000000000000000"}（一个长度合法、值非法的 id）。照抄进库的后果是：
     * 前端按父指针还原层级时会去找一个不存在的父，{@code TraceSpan.isRoot()} 也会误判成 false ——
     * 表现是「根节点缩进不对」，而列里的值看起来一切正常。这个坑只有跑一次真实导出才会暴露。
     */
    private static String parentSpanId(SpanData span) {
        return normalizeSpanId(span.getParentSpanId());
    }

    /** 空串与全零都视为「没有这个 id」——「长度合法、值非法」在 OTel 里是常见的表达。 */
    private static String normalizeSpanId(String spanId) {
        if (spanId == null || spanId.isBlank()) {
            return null;
        }
        for (int i = 0; i < spanId.length(); i++) {
            if (spanId.charAt(i) != '0') {
                return spanId;
            }
        }
        return null;
    }

    /** 从属性里取 id 值。取不到或不是数字时返回 null —— 不是所有 span 都属于某个节点。 */
    private static Long longAttribute(SpanData span, String key) {
        Object value = span.getAttributes().get(AttributeKey.longKey(key));
        return value instanceof Number number ? number.longValue() : null;
    }

    /**
     * 属性转 JSON。
     *
     * <p>用一个有序 Map 而不是 OTel 的 {@code Attributes} 直接序列化：后者是接口，
     * Jackson 不知道该怎么把它写出来（没有 getter 可枚举），会安静地写成 {@code {}} ——
     * 表现成「属性全是空的」，比报错更难查。
     */
    private String toAttributesJson(Attributes attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        Map<String, Object> flat = new LinkedHashMap<>();
        attributes.forEach((key, value) -> flat.put(key.getKey(), sanitize(value)));
        return json.write(flat);
    }

    /** 属性值可能是数组（OTel 支持），统一压成可 JSON 化的形态。 */
    private static Object sanitize(Object value) {
        if (value instanceof List<?> list) {
            Map<String, Object> wrapper = new HashMap<>();
            wrapper.put("values", list);
            return wrapper;
        }
        return value;
    }

    private static Instant toInstant(long epochNanos) {
        return Instant.ofEpochSecond(
                TimeUnit.NANOSECONDS.toSeconds(epochNanos),
                epochNanos % 1_000_000_000L);
    }
}
