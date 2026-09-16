package com.remasteragent.core.trace;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;

import java.util.Map;

/**
 * 让 trace 跨进程 —— 把父上下文塞进队列消息、再把它取回来。
 *
 * <h2>为什么这件事必须做，不能省</h2>
 * <p>本项目的形态是：API 进程接单 → Redis → Worker 进程执行。两个进程<b>没有共享内存</b>，
 * OTel 默认的上下文载体是 ThreadLocal，出了进程就没了。不做这一步，Worker 侧只会得到
 * 一条孤立的新 trace，「全链路」这四个字就是假的 —— 而更糟的是它<b>不会报错</b>，
 * 只是查的时候发现「API 的那一段不见了」。
 *
 * <h2>为什么用 traceparent 头而不是自己编一个 ID</h2>
 * <p>{@code traceparent} 是 W3C 标准格式（{@code 00-<traceId>-<spanId>-<flags>}）。
 * 用它意味着：将来任何标准工具（Jaeger、Tempo、其它语言的 SDK）都能读得懂这条链路，
 * 不需要为「本项目的私有格式」写适配。自研一个「traceId:spanId」字符串当然更简单，
 * 但它把项目锁死在自己的实现里 —— 而选 OTel 的全部意义就是不被锁死。
 *
 * <h2>失败一律降级，绝不抛出</h2>
 * <p>追踪是观测，不是业务。一个畸形的 traceparent（旧版本消息、手工构造、被截断）
 * 只应该导致「这条 trace 从 Worker 开始」，不应该让任务执行失败。这里每个入口都吞掉异常 ——
 * 但只在<b>解析</b>侧吞，序列化侧的问题会被日志暴露出来。
 */
public final class TracePropagation {

    /** 传输字段名。用 W3C 标准名，不换成 {@code trace_parent} 这类自造名。 */
    public static final String TRACEPARENT = "traceparent";

    private static final W3CTraceContextPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();

    /** 读取方向：从 Map（Redis Stream 的字段表）里取 traceparent。 */
    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier == null ? java.util.List.of() : carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    /** 写入方向：把当前上下文写进 Map 的某个键。 */
    private static final TextMapSetter<Map<String, String>> MAP_SETTER =
            (carrier, key, value) -> {
                if (carrier != null) {
                    carrier.put(key, value);
                }
            };

    private TracePropagation() {
    }

    /**
     * 取「当前 span 的 traceparent」—— API 进程投递任务时调用，把父上下文交给 Worker。
     *
     * @return 形如 {@code 00-<32位traceId>-<16位spanId>-01}；当前没有有效 span 时返回 null
     *         （调用方据此不带该字段，Worker 侧自然自起一条 trace）
     */
    public static String currentTraceparent() {
        try {
            Map<String, String> carrier = new java.util.HashMap<>();
            PROPAGATOR.inject(Context.current(), carrier, MAP_SETTER);
            String value = carrier.get(TRACEPARENT);
            return value == null || value.isBlank() ? null : value;
        } catch (Exception e) {
            // 注入失败极罕见（当前上下文没有 span 时会直接不写），但不能让它影响投递
            return null;
        }
    }

    /**
     * 把 traceparent 还原成父上下文，供 {@code SpanBuilder.setParent} 使用。
     *
     * @return 解析成功且确实带有效 span 时返回上下文；否则返回 {@code null}，
     *         调用方应据此让新 span 自成一条 trace
     */
    public static Context extract(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return null;
        }
        try {
            Context extracted = PROPAGATOR.extract(
                    Context.root(), Map.of(TRACEPARENT, traceparent), MAP_GETTER);
            // 「解析没抛异常」不等于「拿到了有效上下文」：一个格式合法但 id 全零的 traceparent
            // 会安静地返回一个空的 SpanContext。不校验它就 setParent，等于制造一条
            // 挂在无效父节点下的孤立 span —— 排查时会以为追踪链路断了，其实是这里放行了脏数据。
            return io.opentelemetry.api.trace.Span.fromContext(extracted).getSpanContext().isValid()
                    ? extracted : null;
        } catch (Exception e) {
            return null;
        }
    }
}
