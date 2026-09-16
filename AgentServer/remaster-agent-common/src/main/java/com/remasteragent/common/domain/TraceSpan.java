package com.remasteragent.common.domain;

import java.time.Instant;

/**
 * 一个执行阶段 —— 全链路 Trace 的最小单元。
 *
 * <p>形状与 OTel 的 {@code SpanData} 对齐，但不直接复用它的类型：那个类型活在
 * {@code opentelemetry-sdk-trace} 里，而 {@code common} 是「不依赖任何运行时」的领域层
 * （连 Spring 都不允许依赖）。让领域对象去引用 SDK 类型，会把 SDK 的版本升级成本
 * 传染到整个项目的每一层。
 *
 * <h2>为什么保留 parent_span_id 而不是靠时间戳推断层级</h2>
 * <p>并行节点的 span 在时间上会交叠，用时间戳套嵌套关系必然算错。父指针是唯一的真相，
 * 有了它，「这次任务的时间花在哪一层」才是一条能对得上的账。
 *
 * @param id           自增主键
 * @param traceId      一次任务执行的全部 span 共用（32 位十六进制）；与 {@code llm_call.trace_id} 同值，可直接 join
 * @param spanId       本 span 的 id（16 位十六进制）
 * @param parentSpanId 父 span id，任务根 span 为 null
 * @param taskId       所属迁移任务
 * @param nodeId       对应的 {@code dag_node} id，任务级 span 为空
 * @param name         span 名称，形如 {@code task} / {@code node:rewrite:com/foo/Bar.java} / {@code llm:REWRITE} / {@code sandbox:mvn}
 * @param kind         OTel SpanKind：INTERNAL / SERVER / CLIENT / PRODUCER / CONSUMER
 * @param status       OTel StatusCode：UNSET / OK / ERROR
 * @param startAt      开始时间（OTel 创建时打点，不是落库时间）
 * @param endAt        结束时间；null 表示这个 span 没走完就被杀了
 * @param durationMs   耗时毫秒，落库时算好以免报表每次做时间差
 * @param attributesJson span 属性（task.id / node.key / llm.model / llm.cost / sandbox.exit_code …）
 */
public record TraceSpan(
        Long id,
        String traceId,
        String spanId,
        String parentSpanId,
        Long taskId,
        Long nodeId,
        String name,
        String kind,
        String status,
        Instant startAt,
        Instant endAt,
        Long durationMs,
        String attributesJson
) {

    /**
     * 是否是任务根 span（整条链路的入口，parent 为空）。
     *
     * <p>「全零串」也算根：OTel SDK 对没有父的 span 会给出 {@code "0000000000000000"}
     * （长度合法、值非法）。新数据不再这样写（导出侧已规范成 NULL），
     * 但历史行里可能有 —— 判据放在这里，是为了让所有消费方对「什么算根」只有一个答案。
     */
    public boolean isRoot() {
        if (parentSpanId == null || parentSpanId.isBlank()) {
            return true;
        }
        return parentSpanId.chars().allMatch(c -> c == '0');
    }

    /**
     * 展示用的短名：把 {@code node:rewrite:com/foo/Bar.java} 这类长键压成一眼能读的形态。
     *
     * <p>放在领域对象上而不是前端模板里，是因为「怎么截断」是这条数据自身的语义，
     * 不是一个视图偏好 —— 将来报表和日志要用同一个口径。
     */
    public String shortName() {
        if (name == null) {
            return "";
        }
        int colon = name.indexOf(':');
        if (colon < 0) {
            return name;
        }
        String head = name.substring(0, colon);
        String tail = name.substring(colon + 1);
        int slash = tail.lastIndexOf('/');
        String file = slash < 0 ? tail : tail.substring(slash + 1);
        return head + ": " + file;
    }
}
