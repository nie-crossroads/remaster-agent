package com.remasteragent.core.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.InetAddress;

/**
 * 任务队列配置。
 *
 * @param streamKey             Redis Stream 的键名
 * @param consumerGroup         消费组名。同一组的多个 Worker 会竞争消费，天然完成横向扩容
 * @param consumerName          本 Worker 实例在组内的名字。
 *                              <b>必须每个实例不同</b>：Redis 的 PEL（未确认消息表）是按
 *                              「组 + 消费者名」记账的，两个实例用同一个名字会导致
 *                              A 读走的消息被记到 B 名下，双方都收不到 → 任务静默卡死
 * @param batchSize             单次最多取几条
 * @param blockMillis           阻塞读取的等待时长。太长会让停机变慢，太短会让空轮询打满 CPU
 * @param reclaimMinIdleSeconds 疑似「上一次被杀的 Worker 留下的」未确认消息的判定阈值：
 *                              空闲超过这个秒数的未确认消息会被本实例接管重跑
 */
@ConfigurationProperties(prefix = "remaster.queue")
public record QueueProperties(
        String streamKey,
        String consumerGroup,
        String consumerName,
        Integer batchSize,
        Integer blockMillis,
        Integer reclaimMinIdleSeconds
) {

    public QueueProperties {
        streamKey = text(streamKey, "remaster:tasks");
        consumerGroup = text(consumerGroup, "remaster-workers");
        consumerName = text(consumerName, defaultConsumerName());
        batchSize = batchSize == null || batchSize < 1 ? 1 : batchSize;
        blockMillis = blockMillis == null || blockMillis < 100 ? 5000 : blockMillis;
        reclaimMinIdleSeconds = reclaimMinIdleSeconds == null || reclaimMinIdleSeconds < 5
                ? 60 : reclaimMinIdleSeconds;
    }

    /**
     * 默认消费者名：主机名 + 进程号。
     *
     * <p>进程号保证同一台机器上多开不冲突，主机名保证多机部署时不冲突。
     * 进程号会被复用，但「同一主机 + 进程号同时被两个 Worker 占用」意味着前一个已经死了，
     * 那正是接管它遗留消息的场景，不构成冲突。
     */
    private static String defaultConsumerName() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "host";
        }
        return "worker-" + host.replaceAll("[^A-Za-z0-9_.-]", "_") + "-" + ProcessHandle.current().pid();
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
