package com.remasteragent.core.trace;

import com.remasteragent.core.codec.JsonCodec;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 全链路 Trace 的装配。
 *
 * <h2>关掉埋点时不建线程、不判空、不改调用点</h2>
 * <p>这里<b>没有用 {@code @ConditionalOnProperty}</b> 去「有条件地创建 Bean」。
 * 那样写会让 {@link TraceTracer} 变成「可能不存在」的依赖，注入它的地方就都得处理 null ——
 * 而「埋点没开」本来就不该是业务代码需要知道的事。
 *
 * <p>做法是：provider 照常建，只是<b>不挂 {@code BatchSpanProcessor}、并把采样器设成 alwaysOff</b>。
 * 效果是精确的 —— 采样器关闭后 OTel 返回的是「非记录型 span」，它的 {@code SpanContext} 无效，
 * 于是 {@link TraceTracer#currentTraceId()} 自然返回 null、{@code llm_call.trace_id} 落空、
 * 导出线程根本不会被创建（因为压根没有 processor）。调用点一行都不用改。
 *
 * <h2>为什么不用 {@code GlobalOpenTelemetry.set()}</h2>
 * <p>本项目所有埋点都走注入的 {@link TraceTracer}，不依赖全局单例。而全局单例有个副作用：
 * 同一个 JVM 里第二次 {@code set} 会抛异常 —— 那恰好是「同时存在两个 Spring 上下文」
 * （集成测试的经典形态）时会踩到的东西。为一个用不上的便利，换一个只在测试里爆炸的坑，不值。
 */
@Configuration
@EnableConfigurationProperties(TraceProperties.class)
public class TraceConfig {

    private static final Logger log = LoggerFactory.getLogger(TraceConfig.class);

    /**
     * OTel 的资源属性键。
     *
     * <p>用字符串字面量而不是 {@code opentelemetry-semconv} 里的常量：那个 artifact
     * 只是把我们需要的几个常量字符串打包了一下，为它多引一个依赖（并且它是 alpha 版本线，
     * 升级节奏与稳定版不同）不划算。{@code service.name} 这个键由 OTel 规范钉死，不会漂移。
     */
    private static final String SERVICE_NAME_KEY = "service.name";

    /**
     * span 的落库出口。
     *
     * <p>无论埋点开不开都建这个 Bean：它只持有 {@code JdbcTemplate} 与 {@code JsonCodec} 的引用，
     * 自己不占线程、不连库。真正决定「要不要跑」的，是下面有没有把它挂到 processor 上。
     */
    @Bean
    public SpanExporter spanExporter(JdbcTemplate jdbc, JsonCodec json) {
        return new JdbcSpanExporter(jdbc, json);
    }

    /**
     * TracerProvider —— span 的采样、批量与导出都在这一层。
     *
     * <p>{@code SdkTracerProvider} 实现了 {@code Closeable}，Spring 会在容器关闭时调用它的
     * {@code close()}，这一步<b>会把攒在批次里的 span flush 出去</b>。少了它，进程退出前
     * 最后两秒的 span 会静静丢掉 —— 而验收时最常看的恰恰是「任务刚跑完那几秒」。
     */
    @Bean(destroyMethod = "close")
    public SdkTracerProvider sdkTracerProvider(TraceProperties properties, SpanExporter exporter) {
        Resource resource = Resource.getDefault().toBuilder()
                .put(SERVICE_NAME_KEY, properties.serviceName())
                .build();

        SdkTracerProvider provider;
        if (properties.enabled()) {
            provider = SdkTracerProvider.builder()
                    .setResource(resource)
                    .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                            .setMaxExportBatchSize(properties.maxBatchSize())
                            .setScheduleDelay(properties.exportDelay())
                            .build())
                    .build();
            log.info("全链路 Trace 已启用: service.name={} span→trace_span 表，攒批 {}ms / 最多 {} 条",
                    properties.serviceName(), properties.exportDelay().toMillis(),
                    properties.maxBatchSize());
        } else {
            provider = SdkTracerProvider.builder()
                    .setResource(resource)
                    .setSampler(Sampler.alwaysOff())
                    .build();
            log.info("全链路 Trace 已关闭（remaster.trace.enabled=false）: 不采样、不落库、不占线程");
        }
        return provider;
    }

    @Bean
    public OpenTelemetry openTelemetry(SdkTracerProvider provider) {
        // 传播器显式固定为 W3C Trace Context：跨进程那一段（Redis 消息里的 traceparent）
        // 用的就是它的格式。OTel 默认也是 W3C，这里写出来是为了让「谁定义了这条链路的数据格式」
        // 有一个明确的落点 —— 将来若有人想换 B3，改动点只有这一行。
        return OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
    }

    @Bean
    public TraceTracer traceTracer(OpenTelemetry openTelemetry) {
        return new TraceTracer(openTelemetry);
    }
}
