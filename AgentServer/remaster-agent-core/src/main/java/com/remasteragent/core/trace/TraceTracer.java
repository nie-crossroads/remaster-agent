package com.remasteragent.core.trace;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

/**
 * 全链路 Trace 的埋点入口 —— 全项目只有这一个地方建 span。
 *
 * <h2>为什么集中在一个类里</h2>
 * <p>span 命名、属性键、父子关系这些约定一旦散落在调用点，就会出现
 * 「同一个语义有两个名字」（{@code llm} 与 {@code llm_call}）这种问题 —— 而报表按名字聚合，
 * 名字一分叉，数据就悄悄少了一半，且没有任何报错。集中定义让这套约定只有一个地方能被改错。
 *
 * <h2>为什么允许 {@link #NOOP} 实例</h2>
 * <p>编排层的单测刻意不依赖任何基础设施：不连库、不调模型、不跑 Maven。如果建 span 需要一个
 * 真实可用的 {@code OpenTelemetry}，那所有 {@code DagScheduler} 的单测都得先起一套 SDK。
 * {@link #NOOP} 走的是 OTel 官方的空实现（{@code OpenTelemetry.noop()}），
 * 它返回的 span 全是无操作对象，{@code setAttribute} / {@code end} 都安全可调 ——
 * 于是「埋点关掉」和「埋点开着但没配 exporter」在代码路径上完全一致。
 *
 * <h2>与「跨进程」的关系</h2>
 * <p>一个任务从 API 进程接单、到 Worker 进程执行，中间隔着 Redis。要让 trace 真的贯通，
 * 必须把父 span 的上下文序列化过去 —— 那是 {@link TracePropagation} 的职责，
 * 本类只负责「拿到父上下文之后怎么用」。
 */
public final class TraceTracer {

    /** 无操作实例：埋点未启用，或纯单测场景。所有方法都可安全调用。 */
    public static final TraceTracer NOOP = new TraceTracer(OpenTelemetry.noop());

    /**
     * Tracer 的 scope 名。
     *
     * <p>用包名而不是「remaster」这类简称：将来若接入别的 instrumentation，scope 名是区分
     * 「这个 span 是谁造的」的唯一依据，简短的名字最容易撞车。
     */
    private static final String INSTRUMENTATION_SCOPE = "com.remasteragent.core";

    private final Tracer tracer;

    public TraceTracer(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
    }

    /**
     * 任务根 span —— 一次任务执行的全部耗时都落在它下面。
     *
     * <p>它是<b>整条链路的根</b>（除非从队列消息里拿到了 API 侧的父上下文）。
     * 之所以宁可让 Worker 起根、也不让 API 的 HTTP 请求当根：真正耗时的部分是
     * 「沙箱跑 Maven」和「模型调用」，它们全在 Worker 侧，挂在 HTTP 请求下面会让
     * 那条 trace 的时间轴被一个早已返回的请求撑得毫无意义。
     *
     * @param parentTraceparent 由 API 侧通过队列消息带过来的 W3C {@code traceparent}；
     *                          为 null 或解析失败时，本 span 自成一条 trace（不报错 ——
     *                          追踪链路断了不该让任务跑不动）
     */
    public Span startTask(long taskId, String projectRoot, String entryFile, int targetJdk,
                          String parentTraceparent) {
        SpanBuilder builder = tracer.spanBuilder("task")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("task.id", taskId)
                .setAttribute("task.project_root", nullToEmpty(projectRoot))
                .setAttribute("task.entry_file", nullToEmpty(entryFile))
                .setAttribute("task.target_jdk", (long) targetJdk);

        Context parent = TracePropagation.extract(parentTraceparent);
        if (parent != null) {
            builder.setParent(parent);
        }
        return builder.startSpan();
    }

    /**
     * API 侧请求 span —— 链路真正开始的地方。
     *
     * <p>本项目<b>没有引 servlet 侧的自动埋点</b>（那是 {@code opentelemetry-instrumentation-web}
     * 的活儿，会连带拉进一整条 instrumentation 依赖链）。而这个 span 恰恰不能省：
     * 投递任务的那个 HTTP 请求是「这次任务的父」—— 没有它，{@code traceparent} 就无从产生，
     * 队列消息里那个字段永远为空，跨进程 trace 就只是代码好看。</p>
     *
     * <p>它默认<b>不会</b>被标成功或失败：{@link #endOk} 由调用方在 handler 正常返回时调用。
     * 一个只接了单、几毫秒就返回的请求，标 OK 是准确的 —— 它确实成功了，
     * 后面几十分钟是 Worker 的事，落在它的子 span 上。</p>
     */
    public Span startApi(String method, String path) {
        return tracer.spanBuilder("api:" + nullToEmpty(method) + " " + nullToEmpty(path))
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("http.method", nullToEmpty(method))
                .setAttribute("http.route", nullToEmpty(path))
                .startSpan();
    }

    /**
     * 节点 span —— 一个 DAG 节点的完整执行。
     *
     * <p>这是整条链路里最整齐的一层：{@code task} 下挂若干 {@code node}，
     * 每个 node 下面再挂它自己的模型调用与沙箱执行。有了这一层，
     * 「这个任务的时间到底花在分析、改写还是验证上」才是一条能直接聚合出来的账。
     *
     * <p>名字里带 {@code attempt}：回退重写会让<b>同一个节点键</b>出现多轮，
     * 三轮 {@code verify:xxx.java} 混在一条时间轴上根本分不出谁是谁。
     */
    public Span startNode(long taskId, long nodeId, String nodeKey, String nodeType, int attempt) {
        return tracer.spanBuilder("node:" + nullToEmpty(nodeKey))
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("task.id", taskId)
                .setAttribute("node.id", nodeId)
                .setAttribute("node.key", nullToEmpty(nodeKey))
                .setAttribute("node.type", nullToEmpty(nodeType))
                .setAttribute("node.attempt", (long) attempt)
                .startSpan();
    }

    /**
     * 模型调用 span —— 一次 LLM 请求（含它内部的 HTTP 重试）。
     *
     * <p>模型名先不设，由调用方在拿到响应后补 {@code llm.model}：请求发出前我们只知道
     * 「要调哪个用途」，实际用的模型由网关决定（多模型路由下可能与配置不同）。
     * 提前写一个猜测值，比留空更糟 —— 报表会把它当事实。
     */
    public Span startLlm(long taskId, long nodeId, String purpose) {
        return tracer.spanBuilder("llm:" + nullToEmpty(purpose))
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("task.id", taskId)
                .setAttribute("node.id", nodeId)
                .setAttribute("llm.purpose", nullToEmpty(purpose))
                .startSpan();
    }

    /** 沙箱执行 span —— 一次 {@code mvn} 子进程。 */
    public Span startSandbox(long taskId, long nodeId, String command) {
        return tracer.spanBuilder("sandbox:" + firstWord(command))
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("task.id", taskId)
                .setAttribute("node.id", nodeId)
                .setAttribute("sandbox.command", nullToEmpty(command))
                .startSpan();
    }

    /**
     * 结束一个 span 并标记为成功。
     *
     * <p>{@code Span.end()} 必须<b>恰好调用一次</b>：漏掉它这个 span 永远不会被导出
     * （表现成「埋点好像没生效」），调用两次则会重复导出。所以统一走这几个 {@code end*} 方法，
     * 而不是让每个调用点自己记得 {@code end()}。
     *
     * <p>分成 {@code endOk / endUnset / endError / endException} 四个而不是一个带枚举的方法：
     * 调用点是「我知道这段的结果是什么」的地方，那里最该写清楚的是结果本身，而不是
     * 「结果类型 → 状态码」的映射 —— 后者放在一处统一实现，就少一处会写错的地方。
     */
    public static void endOk(Span span) {
        if (span == null) {
            return;
        }
        span.setStatus(StatusCode.OK);
        span.end();
    }

    /**
     * 结束一个 span 但不表态（OTel 的 {@code UNSET}）。
     *
     * <p>用于「既没成功也没失败」的第三种结果 —— 典型是 GATE 挂起：
     * 它既不是 OK 也不是 ERROR，只是停在那儿等人。硬塞进任何一档都会让报表上的
     * 「出错率」变得不可信。
     */
    public static void endUnset(Span span) {
        if (span != null) {
            span.end();
        }
    }

    /** 结束一个 span 并标记为失败（业务失败，如编译不过、护栏拦截）。 */
    public static void endError(Span span, String message) {
        if (span == null) {
            return;
        }
        span.setStatus(StatusCode.ERROR, message == null ? "" : message);
        span.end();
    }

    /** 结束一个 span 并标记为异常（系统故障），同时把堆栈记进 span 事件。 */
    public static void endException(Span span, Throwable error) {
        if (span == null) {
            return;
        }
        if (error != null) {
            span.setStatus(StatusCode.ERROR, error.getMessage() == null ? "" : error.getMessage());
            span.recordException(error);
        } else {
            span.setStatus(StatusCode.ERROR);
        }
        span.end();
    }

    /**
     * 当前上下文里的 trace id，没有则返回 {@code null}。
     *
     * <p>给「只有一条记录、不需要建 span」的场景用 —— 典型是写 {@code llm_call.trace_id}：
     * 那一行本来就落在模型调用 span 的内部，再为它单独建一个 span 是重复的。
     */
    public static String currentTraceId() {
        SpanContext context = Span.current().getSpanContext();
        return context.isValid() ? context.getTraceId() : null;
    }

    /** 当前上下文里的 span id，没有则返回 {@code null}。 */
    public static String currentSpanId() {
        SpanContext context = Span.current().getSpanContext();
        return context.isValid() ? context.getSpanId() : null;
    }

    /** 命令行只取第一个词做 span 名后缀 —— 完整命令可能几十个字符，当名字会毁掉时间轴的可读性。 */
    private static String firstWord(String command) {
        if (command == null || command.isBlank()) {
            return "exec";
        }
        String trimmed = command.trim();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
