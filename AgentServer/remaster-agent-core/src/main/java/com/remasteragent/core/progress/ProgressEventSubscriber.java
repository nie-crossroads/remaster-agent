package com.remasteragent.core.progress;

import java.util.function.Consumer;

/**
 * 进度事件订阅口 —— API 进程侧使用。
 *
 * <p>与 {@link ProgressPublisher} 成对：Worker 发、API 收。之所以要有这个接口而不是
 * 让 Controller 直接摸 Redis，是为了让「SSE 推送」这段逻辑可以用一个假订阅器单测 ——
 * 否则验证「事件来了会不会正确推给对应任务的连接」就必须起一个 Redis。
 */
public interface ProgressEventSubscriber {

    /**
     * 注册一个处理器。
     *
     * @param listener 收到事件时回调。<b>实现方必须保证回调抛异常不会影响其它处理器</b>，
     *                 也不能让异常打断订阅循环
     * @return 取消注册的句柄。SSE 连接关闭时必须调用，否则处理器列表只增不减，
     *         形成缓慢的内存泄漏
     */
    AutoCloseable subscribe(Consumer<ProgressEvent> listener);
}
