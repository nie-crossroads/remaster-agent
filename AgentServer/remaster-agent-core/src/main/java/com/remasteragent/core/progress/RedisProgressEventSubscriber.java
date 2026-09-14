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
public class RedisProgressEventSubscriber implements ProgressEventSubscriber, ProgressLink, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RedisProgressEventSubscriber.class);

    /** 订阅断开后的重连间隔。 */
    private static final long RECOVERY_INTERVAL_MS = 5_000L;

    private final RedisConnectionFactory connectionFactory;
    private final JsonCodec json;
    private final List<Consumer<ProgressEvent>> listeners = new CopyOnWriteArrayList<>();

    private volatile RedisMessageListenerContainer container;

    /** 当前这条订阅的建立时刻；0 表示从未订阅过。用于判断「链路有多久没有确认过还活着」。 */
    private volatile long subscribedAt;

    /** 最后一条从频道上收到的消息时刻（任何消息都算，包括心跳与解析不了的载荷）。 */
    private volatile long lastMessageAt;

    public RedisProgressEventSubscriber(RedisConnectionFactory connectionFactory, JsonCodec json) {
        this.connectionFactory = connectionFactory;
        this.json = json;
    }

    @Override
    public AutoCloseable subscribe(Consumer<ProgressEvent> listener) {
        AutoCloseable registration = register(listener);
        ensureContainer();
        return registration;
    }

    /**
     * 登记一个订阅者 —— 与「建立 Redis 连接」刻意分开。
     *
     * <p>两件事本来就不同：一个是「谁想收事件」，一个是「链路建起来没有」。
     * 分开之后，派发语义（心跳不派发、坏载荷不派发、正常事件派发给所有人）
     * 就能在<b>完全不连 Redis</b> 的情况下被确定性验证 —— 而这正是这条链路最需要被测的部分，
     * 因为它在生产里出问题时一声不响。
     */
    AutoCloseable register(Consumer<ProgressEvent> listener) {
        listeners.add(listener);
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
            // 两条时间戳都在这里重置：新订阅刚建立时链路是「尚未确认」而不是「已确认」，
            // 把 subscribedAt 作为兜底信号，可以避免刚重建完就被看门狗立刻判死（15 秒内必有心跳回来）。
            this.subscribedAt = System.currentTimeMillis();
            this.lastMessageAt = 0L;
            log.info("已订阅进度事件频道 {}", ProgressChannels.PROGRESS_EVENTS);
        }
    }

    /**
     * 收到频道消息。
     *
     * <p><b>顺序很重要</b>：先把「收到过东西」这件事记下来，再谈内容是否解析得动。
     * 链路的死活由「有没有流量」决定，而不是由「这条消息合不合法」决定 ——
     * 一条解析不了的脏载荷同样证明连接是通的，把它当成链路已死而重建，纯属自找抖动。
     *
     * <p>包级可见而不是 private：单测要直接投递一条消息来验证「心跳不派发、正常事件才派发」，
     * 而那条路径完全不需要真的连 Redis。
     */
    void onMessage(Message message, byte[] pattern) {
        lastMessageAt = System.currentTimeMillis();

        ProgressEvent event = json.read(message.getBody(), ProgressEvent.class).orElse(null);
        if (event == null) {
            log.warn("无法解析进度事件，已丢弃（但链路仍视为存活）");
            return;
        }

        // 心跳只用来确认这条订阅链路还活着（见 ProgressEvent.TYPE_HEARTBEAT），
        // 必须在派发之前拦掉：它不是任务进度，漏给 SSE 前端会在事件流里多出一条无意义的记录。
        if (event.isHeartbeat()) {
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
    public long lastSignalAt() {
        return Math.max(subscribedAt, lastMessageAt);
    }

    @Override
    public boolean isConfirmedAlive() {
        // 重建订阅时会把 lastMessageAt 清零，所以「还没收到过消息」天然判为未确认 ——
        // 重建只是把链路重新接上，接上之后通不通要等下一条消息说了算。
        return subscribedAt > 0L && lastMessageAt >= subscribedAt;
    }

    /**
     * 拆掉当前订阅并重建 —— 从「连接已死但没人知道」里恢复的唯一手段。
     *
     * <p>{@code listeners} 是刻意保留的：它装的是「谁想收事件」，与「这条订阅还活着吗」无关。
     * 重建订阅不该让上层重新注册一遍回调。
     */
    @Override
    public synchronized void recreateSubscription() {
        RedisMessageListenerContainer current = container;
        if (current == null) {
            // 从未订阅过就没什么可重建的；硬建一条反而是凭空多出一个连接
            return;
        }
        container = null;
        try {
            current.destroy();
        } catch (Exception e) {
            // 旧容器销毁失败不影响新容器建立：它已经是个死连接了，能扔多远扔多远
            log.warn("销毁失效的进度订阅容器时出错，继续重建", e);
        }
        ensureContainer();
    }

    @Override
    public void destroy() {
        RedisMessageListenerContainer current = container;
        if (current == null) {
            return;
        }
        container = null;
        subscribedAt = 0L;
        lastMessageAt = 0L;
        try {
            current.destroy();
        } catch (Exception e) {
            // 关闭阶段的异常不值得让整个上下文启动失败，记下来即可
            log.warn("关闭进度事件订阅容器时出错", e);
        }
    }
}
