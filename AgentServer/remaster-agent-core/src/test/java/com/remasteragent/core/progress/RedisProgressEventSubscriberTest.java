package com.remasteragent.core.progress;

import com.remasteragent.core.codec.JsonCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进度订阅者「收到一条消息之后做什么」的单测。
 *
 * <h2>覆盖范围与边界（写清楚比假装覆盖了更重要）</h2>
 * <p><b>覆盖</b>：心跳不派发、坏载荷不派发但链路仍算存活、正常事件派发给订阅者、
 * 链路时间戳的推进与「未订阅即未确认」的语义、销毁空订阅时不抛异常。
 * 这些都是确定性逻辑，毫秒级跑完，不需要 Redis。
 *
 * <p><b>不覆盖</b>：真正的连接建立与重建（{@code subscribe()} / {@code recreateSubscription()}）。
 * 它们要连一台真 Redis，属于端到端验收 —— 用假连接工厂去测只会测出「测试替身的行为」，
 * 换不来任何对真实故障的保证。这也正是把「登记订阅者」与「建立连接」拆开的原因：
 * 最需要被测的派发语义因此可以完全离线验证。
 */
class RedisProgressEventSubscriberTest {

    private static final byte[] CHANNEL = ProgressChannels.PROGRESS_EVENTS.getBytes(StandardCharsets.UTF_8);

    private final JsonCodec json = new JsonCodec();
    private final List<ProgressEvent> received = new ArrayList<>();

    /**
     * 连接工厂传 {@code null}：本用例只走 {@code register()} + {@code onMessage()}，
     * 两者都不碰连接（真正建连接的是没被调用的 {@code subscribe()}）。
     */
    private RedisProgressEventSubscriber subscriber() {
        return new RedisProgressEventSubscriber(null, json);
    }

    private Message raw(String payload) {
        return new DefaultMessage(CHANNEL, payload.getBytes(StandardCharsets.UTF_8));
    }

    private void deliver(RedisProgressEventSubscriber subscriber, ProgressEvent event) {
        String payload = json.write(event);
        assertNotNull(payload, "事件必须能序列化，否则这条用例本身就没在测真东西");
        subscriber.onMessage(raw(payload), null);
    }

    @Test
    @DisplayName("心跳只刷新链路时间戳，绝不派发给订阅者")
    void heartbeatIsNeverDispatched() {
        RedisProgressEventSubscriber subscriber = subscriber();
        subscriber.register(received::add);

        long before = subscriber.lastSignalAt();
        deliver(subscriber, ProgressEvent.heartbeat());

        assertTrue(received.isEmpty(),
                "心跳不是任务进度：派发出去会让前端事件流里凭空多出一条无意义的记录");
        assertTrue(subscriber.lastSignalAt() > before,
                "但它必须把「链路有流量」这件事记下来 —— 否则看门狗永远不知道自己聋没聋");
    }

    @Test
    @DisplayName("正常事件仍然照常派发（心跳拦截没有误伤业务事件）")
    void normalEventsAreStillDispatched() {
        RedisProgressEventSubscriber subscriber = subscriber();
        subscriber.register(received::add);

        deliver(subscriber, ProgressEvent.taskStatus(7L, "RUNNING", "任务开始执行"));
        deliver(subscriber, ProgressEvent.nodeStatus(7L, 12L, "rewrite", "SUCCEEDED", 0, "改写完成"));

        assertEquals(2, received.size());
        assertEquals(ProgressEvent.TYPE_TASK_STATUS, received.get(0).type());
        assertEquals("rewrite", received.get(1).nodeKey());
    }

    @Test
    @DisplayName("多个订阅者都能收到同一条事件")
    void everySubscriberGetsTheEvent() {
        RedisProgressEventSubscriber subscriber = subscriber();
        List<ProgressEvent> other = new ArrayList<>();
        subscriber.register(received::add);
        subscriber.register(other::add);

        deliver(subscriber, ProgressEvent.taskStatus(1L, "SUCCEEDED", "任务完成"));

        assertEquals(1, received.size());
        assertEquals(1, other.size(), "一个订阅者不该把另一个挤掉");
    }

    @Test
    @DisplayName("取消登记之后不再收到事件")
    void unregisteredSubscriberStopsReceiving() throws Exception {
        RedisProgressEventSubscriber subscriber = subscriber();
        AutoCloseable registration = subscriber.register(received::add);

        deliver(subscriber, ProgressEvent.taskStatus(1L, "RUNNING", "第一条"));
        registration.close();
        deliver(subscriber, ProgressEvent.taskStatus(1L, "SUCCEEDED", "第二条"));

        assertEquals(1, received.size(), "取消登记必须真的生效，否则处理器会随重连不断累积");
    }

    @Test
    @DisplayName("解析不了的载荷：不派发、不抛异常，但链路依然算存活")
    void unparseablePayloadStillProvesLinkIsAlive() {
        RedisProgressEventSubscriber subscriber = subscriber();
        subscriber.register(received::add);

        long before = subscriber.lastSignalAt();
        subscriber.onMessage(raw("{ 这不是合法 JSON"), null);

        assertTrue(received.isEmpty(), "坏载荷不能污染订阅者");
        assertTrue(subscriber.lastSignalAt() > before,
                "收到过东西就证明连接是通的 —— 拿「消息不合法」去判链路已死会引发无谓重建");
    }

    @Test
    @DisplayName("从未收到消息时链路时间戳为 0，且「已被证明活着」不成立")
    void freshSubscriberHasNoSignal() {
        RedisProgressEventSubscriber subscriber = subscriber();

        assertEquals(0L, subscriber.lastSignalAt(),
                "0 是「从未订阅」的哨兵值：看门狗据此避免把「没人订阅」误判成「链路死了」");
        assertFalse(subscriber.isConfirmedAlive(),
                "还没订阅就不该声称链路是活的 —— 刚重建完的订阅同样如此，要等下一条消息");
    }

    @Test
    @DisplayName("销毁一个从未订阅过的实例是空操作")
    void destroyingFreshInstanceIsNoOp() {
        RedisProgressEventSubscriber subscriber = subscriber();
        subscriber.destroy();

        assertEquals(0L, subscriber.lastSignalAt());
    }
}
