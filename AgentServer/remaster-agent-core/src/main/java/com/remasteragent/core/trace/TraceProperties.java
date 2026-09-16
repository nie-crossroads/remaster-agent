package com.remasteragent.core.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 全链路 Trace 配置。
 *
 * @param enabled      是否开启埋点。<b>默认 true</b>。关掉之后 {@code TraceTracer.NOOP} 会被装配，
 *                     所有埋点调用变成空操作，{@code llm_call.trace_id} 落 null ——
 *                     行为与「埋点尚未存在」完全一致，不需要改任何调用代码。
 * @param serviceName  服务名，写进 OTel Resource。双进程部署时两个进程各配一个（api / worker），
 *                     否则「这条 span 是 API 产生的还是 Worker 产生的」在导出的数据里分不出来 ——
 *                     而这两个进程的 span 语义完全不同（HTTP 接单 vs 真正干活）。
 * @param maxBatchSize 单个导出批次的最大 span 数。取 512：一次任务产生的 span 是「节点数 × 3」
 *                     量级（几十条），512 足够让绝大多数批次一次装完，又不会让异常情况下
 *                     单次 INSERT 过大。
 * @param exportDelay  批量导出的攒批间隔。默认 2 秒（OTel 默认是 5 秒）——
 *                     验收时的动作是「跑完任务 → 打开页面看链路」，5 秒的延迟会让人以为埋点没生效，
 *                     进而去查一个根本不存在的问题。
 */
@ConfigurationProperties(prefix = "remaster.trace")
public record TraceProperties(
        Boolean enabled,
        String serviceName,
        Integer maxBatchSize,
        Duration exportDelay
) {

    private static final String DEFAULT_SERVICE_NAME = "remaster-agent";
    private static final int DEFAULT_MAX_BATCH_SIZE = 512;
    private static final Duration DEFAULT_EXPORT_DELAY = Duration.ofSeconds(2);

    public TraceProperties {
        enabled = enabled == null || enabled;
        serviceName = (serviceName == null || serviceName.isBlank())
                ? DEFAULT_SERVICE_NAME : serviceName;
        maxBatchSize = maxBatchSize == null || maxBatchSize < 1
                ? DEFAULT_MAX_BATCH_SIZE : maxBatchSize;
        exportDelay = (exportDelay == null || exportDelay.isZero() || exportDelay.isNegative())
                ? DEFAULT_EXPORT_DELAY : exportDelay;
    }
}
