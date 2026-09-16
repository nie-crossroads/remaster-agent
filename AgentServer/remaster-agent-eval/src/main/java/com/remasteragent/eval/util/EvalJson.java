package com.remasteragent.eval.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.UncheckedIOException;

/**
 * 评测 harness 用到的两个 JSON 映射器。
 *
 * <h2>为什么必须宽松（{@code FAIL_ON_UNKNOWN_PROPERTIES = false}）</h2>
 * <p>这个项目已经在这件事上栽过一次：{@code TaskMetrics} 上那些<b>派生方法</b>
 * （{@code compilePassRate()} / {@code testPassRate()}）会被 Jackson 当成 getter 序列化出去，
 * 于是「序列化产物」与「反序列化目标」字段不一致，严格模式下直接抛异常 ——
 * 而那异常被上游 catch 吞掉之后，表现为「任务明明成功，指标却全是 0」。
 *
 * <p>harness 消费的是 API 的响应，而 API 的 {@code MetricsView} 比 {@code TaskMetrics}
 * 多带几个展示用字段（比率、{@code retried}）。宽松模式让「API 想加字段」不会打破 harness；
 * 代价是「API 改字段名」会静默变成 0 —— 这条由
 * {@code MetricsShapeGuard} 在解析时显式校验必需键来兜住，而不是靠严格模式。
 */
public final class EvalJson {

    /** API 响应与结果文件用的映射器。 */
    public static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** catalog.yaml 用的映射器。 */
    public static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private EvalJson() {
    }

    /**
     * 序列化，把受检异常包成 {@link UncheckedIOException}。
     *
     * <p>harness 是命令行工具，调用点遍布在 lambda 与循环里，
     * 让每处都 {@code throws JsonProcessingException} 只会污染签名。
     */
    public static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("序列化失败: " + value.getClass().getSimpleName(), e);
        }
    }

    /** 反序列化成指定类型。 */
    public static <T> T read(String text, TypeReference<T> type) {
        try {
            return JSON.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("解析 JSON 失败", e);
        }
    }
}
