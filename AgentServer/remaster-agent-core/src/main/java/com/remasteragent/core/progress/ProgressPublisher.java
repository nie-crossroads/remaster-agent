package com.remasteragent.core.progress;

/**
 * 进度发布口。
 *
 * <p>core 只定义「怎么发」，不关心「发到哪」。Worker 进程里的实现把事件投到 Redis Pub/Sub，
 * API 进程再订阅出来推给 SSE —— 但 core 不需要知道这些，它甚至不需要知道 Redis 的存在。
 *
 * <p>这也是能在单测里跑调度器的前提之一：塞一个把事件收集到 List 的实现，
 * 就能断言「节点状态是按什么顺序变化的」。
 */
public interface ProgressPublisher {

    void publish(ProgressEvent event);

    /** 什么都不做的实现，用于单测与不需要进度的场景。 */
    ProgressPublisher NOOP = event -> {
    };
}
