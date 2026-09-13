package com.remasteragent.core.progress;

/**
 * 进度事件使用的 Redis 频道名。
 *
 * <p>单独拎出来是为了让发布端（Worker）与订阅端（API）引用同一个常量。
 * 两边各写一份字面量是这类跨进程通信里最典型的静默故障源：
 * 名字对不上时不会报任何错，只是「连上了但永远收不到消息」。
 */
public final class ProgressChannels {

    /** Worker 发布进度事件、API 订阅的频道。 */
    public static final String PROGRESS_EVENTS = "remaster:progress";

    private ProgressChannels() {
    }
}
