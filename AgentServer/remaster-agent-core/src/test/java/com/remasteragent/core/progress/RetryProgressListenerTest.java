package com.remasteragent.core.progress;

import com.remasteragent.common.domain.NodeStatus;
import dev.langchain4j.exception.HttpException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「重试 → 进度事件」这条翻译链路的单测。
 *
 * <h2>为什么它值得单独钉</h2>
 * <p>它的产物是<b>用户在页面上的唯一判断依据</b>。上游网关偶发 524 时，一次改写会在同一次节点
 * 执行内部重试两三次、每次一到两分钟；而节点的 {@code attempt} 全程是 0，
 * 时间线上唯一的区别就是这句话 —— 它不出现，用户看到的就是「卡死」。
 *
 * <p>所以这里钉住三件事：字段是否齐全（缺一个前端就路由不到节点）、
 * {@code attempt} 是否如实透传（不能在展示层造假，前端要靠它对上日志）、
 * 以及<b>发布失败不能把任务带崩</b>。
 */
class RetryProgressListenerTest {

    @Test
    @DisplayName("还会重试时：发一条 node_status，说清「第几次 / 共几次 / 为什么」")
    void publishesRetryAttemptAsNodeStatus() {
        List<ProgressEvent> events = new ArrayList<>();
        RetryProgressListener listener = new RetryProgressListener(
                events::add, 42L, 7L, "rewrite:Demo.java", 0, "改写");

        listener.onAttemptFailed(2, 3, new HttpException(524, "error code: 524"), true);

        assertEquals(1, events.size());
        ProgressEvent event = events.get(0);
        assertEquals(42L, event.taskId());
        assertEquals(7L, event.nodeId(), "缺了节点 id 前端就无法把消息落到具体节点上");
        assertEquals("rewrite:Demo.java", event.nodeKey());
        assertEquals(ProgressEvent.TYPE_NODE_STATUS, event.type(),
                "复用 node_status：节点确实还是 RUNNING，变的只是「为什么还在跑」");
        assertEquals(NodeStatus.RUNNING.name(), event.status());
        assertEquals(0, event.attempt(), "HTTP 重试不递增节点 attempt，展示层不能在这里造假");
        assertEquals("改写第 2/3 次调用失败：HTTP 524（上游响应超时），正在重试", event.message());
    }

    @Test
    @DisplayName("不再重试时：同一句话改成「不再重试」，语义不能反")
    void marksFinalFailureAsNotRetrying() {
        List<ProgressEvent> events = new ArrayList<>();
        RetryProgressListener listener = new RetryProgressListener(
                events::add, 42L, 7L, "rewrite:Demo.java", 2, "改写");

        listener.onAttemptFailed(3, 3, new HttpException(401, "invalid api key"), false);

        ProgressEvent event = events.get(0);
        assertTrue(event.message().contains("不再重试"), "实际: " + event.message());
        assertEquals(2, event.attempt(), "节点 attempt 要透传原值（这里是第 3 轮回退重写）");
    }

    @Test
    @DisplayName("进度发布失败不能把任务带崩 —— 它只是旁路信息")
    void publishFailureIsSwallowed() {
        ProgressPublisher broken = event -> {
            throw new IllegalStateException("Redis 连接断了");
        };
        RetryProgressListener listener = new RetryProgressListener(
                broken, 42L, 7L, "rewrite:Demo.java", 0, "改写");

        assertDoesNotThrow(() -> listener.onAttemptFailed(
                1, 3, new HttpException(524, "error code: 524"), true));
    }

    @Test
    @DisplayName("没给进度发布口（null）时退化成什么都不做，而不是 NPE")
    void nullPublisherIsSafe() {
        RetryProgressListener listener = new RetryProgressListener(null, 42L, null, null, 0, "规划");

        assertDoesNotThrow(() -> listener.onAttemptFailed(
                1, 3, new HttpException(503, "unavailable"), true));
    }
}
