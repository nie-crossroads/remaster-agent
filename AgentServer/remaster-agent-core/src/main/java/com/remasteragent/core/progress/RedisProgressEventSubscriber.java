package com.remasteragent.core.progress;

import com.remasteragent.core.codec.JsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * {@link ProgressEventSubscriber} 的 Redis Pub/Sub 实现 —— 跑在 <b>API 进程</b>里。
 *
 * <h2>用 {@link RedisMessageListenerContainer} 而不是裸的 SUBSCRIBE</h2>
 * <p>裸 {@code connection.subscribe(...)} 会占死一个连接和线程，而且 Redis 重启后
 * 连接断了就再也不会恢复 —— 表现是「服务刚启动时能实时刷新，跑一阵子之后页面不动了」，
 * 且不报任何错。容器实现自带重连与订阅恢复，这个差别在长跑进程里是致命的。
 *
 * <h2>延迟到第一次订阅才建连接</h2>
 * <p>Worker 进程同样会装配这个 Bean（它依赖 core），但 Worker 永远不需要订阅进度。
 * 若在构造时就建连接，每个 Worker 都会白占一个 Redis 订阅连接。
 * 所以真正的初始化推迟到第一个 {@link #subscribe} 调用。
 */
@Component
public class RedisProgressEventSubscriber implements ProgressEventSubscriber, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RedisProgressEventSubscriber.class);

    /** 订阅断开后的重连间隔。 */
    private static final long RECOVERY_INTERVAL_MS = 5_000L;

    private final RedisConnectionFactory connectionFactory;
    private final JsonCodec json;
    private final List<Consumer<ProgressEvent>> listeners = new CopyOnWriteArrayList<>();

    private volatile RedisMessageListenerContainer container;

    public RedisProgressEventSubscriber(RedisConnectionFactory connectionFactory, JsonCodec json) {
        this.connectionFactory = connectionFactory;
        this.json = json;
    }

    @Override
    public AutoCloseable subscribe(Consumer<ProgressEvent> listener) {
        listeners.add(listener);
        ensureContainer();
        return () -> listeners.remove(listener);
    }

    private void ensureContainer() {
        if (container != null) {
            return;
        }
        synchronized (this) {
            if (container != null) {
                return;
            }
            RedisMessageListenerContainer created = new RedisMessageListenerContainer();
            created.setConnectionFactory(connectionFactory);
            created.setRecoveryInterval(RECOVERY_INTERVAL_MS);
            created.addMessageListener(this::onMessage, new ChannelTopic(ProgressChannels.PROGRESS_EVENTS));
            created.afterPropertiesSet();
            created.start();
            this.container = created;
            log.info("已订阅进度事件频道 {}", ProgressChannels.PROGRESS_EVENTS);
        }
    }

    private void onMessage(Message message, byte[] pattern) {
        // 解析不出来只丢弃这一条：进度是加速器，不是事实来源。
        // 让一条坏消息把订阅线程搞挂，代价远大于丢一条通知。
        ProgressEvent event = json.read(message.getBody(), ProgressEvent.class).orElse(null);
        if (event == null) {
            log.warn("无法解析进度事件，已丢弃");
            return;
        }

        for (Consumer<ProgressEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                // 一个订阅者出错不能影响其它订阅者（比如某个 SSE 连接已经断了）
                log.warn("进度事件处理器抛异常，已跳过该处理器", e);
            }
        }
    }

    @Override
    public void destroy() {
        RedisMessageListenerContainer current = container;
        if (current == null) {
            return;
        }
        try {
            current.destroy();
        } catch (Exception e) {
            // 关闭阶段的异常不值得让整个上下文启动失败，记下来即可
            log.warn("关闭进度事件订阅容器时出错", e);
        }
    }
}
