package com.remasteragent.core.codec;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 编排内核的 JSON 编解码器 —— <b>core 必须自带这一套，不能借用 Spring Boot 的。</b>
 *
 * <h2>为什么不能依赖注入进来的 ObjectMapper（这是踩出来的）</h2>
 * <p>API 进程里 Spring Boot 会自动配好一个 {@code ObjectMapper}，一切正常；
 * 于是很容易写出「注入 ObjectMapper 就完事」的代码。但本项目的 Worker 进程
 * <b>刻意不带 {@code spring-boot-starter-web}</b>，于是：
 *
 * <ul>
 *   <li>{@code Jackson2ObjectMapperBuilder} 位于 {@code spring-web}
 *       （{@code org.springframework.http.converter.json}），类不存在 → Spring Boot 的
 *       {@code JacksonObjectMapperBuilderConfiguration} / {@code JacksonObjectMapperConfiguration}
 *       全部因 {@code @ConditionalOnClass} 不成立而退化，<b>整个容器里一个 ObjectMapper 都没有</b>，
 *       {@code DagScheduler} 直接启动失败。</li>
 *   <li>即使有人补一个裸 {@code new ObjectMapper()}，它不认识 {@code java.time} 类型：
 *       {@link com.remasteragent.core.progress.ProgressEvent} 带 {@code Instant}，
 *       发布进度事件时会抛 {@code InvalidDefinitionException}。而这个异常被
 *       「进度推送失败不影响任务」的兜底 catch 吞掉 —— 表现是<b>任务全绿、但前端永远不动</b>，
 *       是那种能上线、能自测通过、只有真去点页面才发现的缺陷。</li>
 * </ul>
 *
 * <p>所以结论不是「给 Worker 也加个 web 依赖」，而是：<b>编排层用到的 JSON 方言必须由编排层自己定义。</b>
 * 两个进程跑的是同一份 core，用同一个编解码器，checkpoint 的读写格式才不会因为「谁启动的」
 * 而不同 —— 这一点对于「进程被杀后由另一个进程续跑」来说是硬要求。
 *
 * <h2>三项刻意配置</h2>
 * <ol>
 *   <li><b>注册 {@link JavaTimeModule} 且关掉时间戳序列化</b>：{@code Instant} 写成
 *       ISO-8601 字符串。数字时间戳在跨语言、跨版本、人肉看日志时都是负资产，
 *       而这个量级的数据根本不缺那点体积。</li>
 *   <li><b>忽略未知属性</b>：checkpoint 是长期存在 {@code dag_node.result} 里的历史数据，
 *       代码先于数据演进。加了字段的旧记录、删了字段的新代码，都必须还能读 ——
 *       读不动就意味着「任务卡在断点再也续不上」，代价远大于忽略一个字段。
 *       注意这条只是<b>读</b>宽松；写的形状由 {@code CheckpointJsonShapeTest} 钉死。</li>
 *   <li><b>失败返回 null / empty 而不抛异常</b>：序列化失败不应该让任务崩掉，
 *       它只应该让「这一条记录」缺失，由调用方决定怎么降级。</li>
 * </ol>
 *
 * <p>关于「写宽松还是写严格」的取舍：写必须严格（形状固定，测试钉住），
 * 读必须宽松（容忍历史数据）。把两者都做成宽松，等于放弃了格式契约；
 * 都做成严格，则每次结构调整都会让存量数据不可读。
 */
@Component
public class JsonCodec {

    private static final Logger log = LoggerFactory.getLogger(JsonCodec.class);

    private final ObjectMapper mapper;

    public JsonCodec() {
        this(defaultMapper());
    }

    JsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 生产用配置。公开出来是为了让测试能拿到「和生产完全一致」的 mapper ——
     * 测试里手搓一个配置不同的 mapper，就等于在测另一个系统。
     */
    public static ObjectMapper defaultMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /** 序列化为 JSON 字符串；失败返回 {@code null} 并告警，绝不抛出。 */
    public String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("JSON 序列化失败，该字段将降级为 null: {}", value.getClass().getName(), e);
            return null;
        }
    }

    /** 反序列化；输入为空或格式不符时返回 {@link Optional#empty()}。 */
    public <T> Optional<T> read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(mapper.readValue(json, type));
        } catch (Exception e) {
            log.warn("JSON 反序列化失败，已降级为空（历史数据或版本不一致）: {}", type.getSimpleName(), e);
            return Optional.empty();
        }
    }

    /** 反序列化（字节数组形式，供 Redis 消息体等场景使用）。 */
    public <T> Optional<T> read(byte[] json, Class<T> type) {
        if (json == null || json.length == 0) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(mapper.readValue(json, type));
        } catch (Exception e) {
            log.warn("JSON 反序列化失败，已降级为空: {}", type.getSimpleName(), e);
            return Optional.empty();
        }
    }

    /** 逃生口：需要 Jackson 高级特性时用。不要在业务代码里拿它做常规读写。 */
    public ObjectMapper mapper() {
        return mapper;
    }
}
