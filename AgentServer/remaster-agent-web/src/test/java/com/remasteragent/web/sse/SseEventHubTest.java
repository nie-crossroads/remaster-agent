package com.remasteragent.web.sse;

import com.remasteragent.core.codec.JsonCodec;
import com.remasteragent.core.progress.ProgressEvent;
import com.remasteragent.core.progress.ProgressEventSubscriber;
import com.remasteragent.web.api.NotFoundException;
import com.remasteragent.web.api.TaskQueryService;
import com.remasteragent.web.api.dto.TaskDetailView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE 事件中枢的单测。
 *
 * <h2>覆盖范围与边界（写清楚比假装覆盖了更重要）</h2>
 * <p><b>覆盖</b>：连接登记与回收、快照的产出时机、按 taskId 路由、
 * 快照失败时不留死连接、销毁时清理订阅。这些都是纯记账逻辑，可以完全确定性地验证。
 *
 * <p><b>不覆盖</b>：SSE 报文的具体字节，以及「浏览器断开后回调是否触发」。
 * {@code SseEmitter} 在没有 HTTP 响应对象时会把数据缓冲在内部，回调也要等框架
 * 初始化响应之后才会触发 —— 所以单测既拿不到最终写入的报文，也无法自然地触发
 * {@code onCompletion}。要验证这些得用 MockMvc 异步派发，而那需要让流「结束」才能取到响应体，
 * 对一个设计成长期不关闭的连接来说会引入时序不确定性。
 * 用一个会随机变红的测试换取这点覆盖率，不划算 —— 报文正确性留给端到端验收
 * （见 {@code examples/legacy-demo} 的验收清单）。
 *
 * <p>这正是「快照 + 增量」设计的价值所在：即使报文层出错，客户端重连就能拿到正确状态，
 * 而账目层的错误（比如连接没回收）才是会累积成故障的那类。
 */
class SseEventHubTest {

    private final List<Consumer<ProgressEvent>> listeners = new CopyOnWriteArrayList<>();
    private final List<Long> snapshotRequests = new ArrayList<>();
    private final Set<Long> missingTasks = new HashSet<>();

    /** 假订阅器：把处理器收下来，测试里手动投递事件。 */
    private final ProgressEventSubscriber subscriber = listener -> {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    };

    private SseEventHub hub;

    @BeforeEach
    void setUp() {
        listeners.clear();
        snapshotRequests.clear();
        missingTasks.clear();
        hub = new SseEventHub(subscriber, fakeQueryService(), new JsonCodec());
        hub.subscribeToProgressEvents();
    }

    @Test
    @DisplayName("建立连接时立即产出快照（否则连接前发生的事永远补不回来）")
    void openingConnectionProducesSnapshot() {
        hub.open(7L);

        assertEquals(List.of(7L), snapshotRequests, "每次连接都必须请求一次当前状态作为快照");
        assertEquals(1, hub.connectionCount());
    }

    @Test
    @DisplayName("快照生成失败（任务不存在）时不留下死连接")
    void failedSnapshotDoesNotLeakConnection() {
        missingTasks.add(42L);

        SseEmitter emitter = hub.open(42L);

        assertEquals(List.of(42L), snapshotRequests);
        assertEquals(0, hub.connectionCount(),
                "发不出快照的连接已经废了，留在列表里只会一直被写、一直被吞异常");
        assertDoesNotThrow(emitter::complete);
    }

    @Test
    @DisplayName("事件按 taskId 路由：没有订阅者的任务不会引发异常")
    void eventForUnwatchedTaskIsHarmless() {
        hub.open(1L);

        assertDoesNotThrow(() -> publish(ProgressEvent.taskStatus(999L, "RUNNING", "别的任务")));
        assertEquals(1, hub.connectionCount(), "无关任务的事件不应影响已有连接");
    }

    @Test
    @DisplayName("一个任务的事件不会误伤另一个任务的连接")
    void eventsDoNotLeakAcrossTasks() {
        hub.open(1L);
        hub.open(2L);
        assertEquals(2, hub.connectionCount());

        publish(ProgressEvent.nodeStatus(1L, 10L, "rewrite", "SUCCEEDED", 0, "完成"));
        publish(ProgressEvent.taskStatus(1L, "SUCCEEDED", "任务完成"));

        assertEquals(2, hub.connectionCount());
    }

    @Test
    @DisplayName("同一任务的多条连接都会被推送（两个人同时看同一个任务）")
    void multipleConnectionsForSameTask() {
        hub.open(5L);
        hub.open(5L);
        assertEquals(2, hub.connectionCount());

        assertDoesNotThrow(() -> publish(ProgressEvent.taskStatus(5L, "RUNNING", "进行中")));

        assertEquals(2, hub.connectionCount());
    }

    @Test
    @DisplayName("进度事件必须能被序列化（Instant 需要 JavaTimeModule）")
    void progressEventIsSerializable() {
        // 这条用例守着一个真实发生过的生产故障：ProgressEvent 带 Instant，而裸的
        // new ObjectMapper() 不认识 java.time。API 进程里 Spring Boot 会自动配好 mapper，
        // 所以本地一切正常；但 Worker 进程刻意不带 web starter，那里连 ObjectMapper Bean 都没有。
        // 后果是任务全绿、日志干净，只有页面永远不动 —— 因为它被
        // 「进度推送失败不影响任务」的兜底 catch 吞掉了。
        // 所以这里必须用生产同一个 JsonCodec 来验，而不是在测试里手搓一个配置不同的 mapper：
        // 手搓的 mapper 一旦和生产不一致，这条用例就变成在自证清白。
        JsonCodec codec = new JsonCodec();
        ProgressEvent event = ProgressEvent.nodeStatus(1L, 2L, "rewrite", "RUNNING", 1, "第 2 轮");

        String payload = codec.write(event);
        ProgressEvent restored = codec.read(payload, ProgressEvent.class).orElseThrow();

        assertEquals(event, restored, "进度事件必须能往返 —— 它要跨进程传输");
        assertTrue(payload.contains("T") && payload.contains("Z"),
                "Instant 应序列化为 ISO-8601 字符串而不是数字时间戳，实际: " + payload);
    }

    @Test
    @DisplayName("心跳：无连接时是空操作，有连接时不应抛异常")
    void heartbeatIsSafe() {
        assertDoesNotThrow(() -> hub.heartbeat());

        hub.open(3L);
        assertDoesNotThrow(() -> hub.heartbeat());
        assertEquals(1, hub.connectionCount());
    }

    @Test
    @DisplayName("销毁时取消订阅并关闭所有连接")
    void destroyCleansUp() {
        hub.open(1L);
        hub.open(2L);
        assertEquals(1, listeners.size(), "只应注册一个全局订阅，而不是每个连接注册一个");

        hub.destroy();

        assertEquals(0, hub.connectionCount());
        assertEquals(0, listeners.size(), "订阅必须取消，否则处理器会随每次建连累积");
    }

    @Test
    @DisplayName("没有连接时不登记任何东西（快照只在真正有人看的时候才查）")
    void noConnectionsMeansNoWork() {
        assertEquals(0, hub.connectionCount());
        assertTrue(snapshotRequests.isEmpty());
    }

    // ------------------------------------------------------------------
    // 最近事件缓冲（刷新页面后「最近事件流」还能有数据）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("有人观看时事件进缓冲，重连（刷新页面）时历史仍在")
    void recentEventsSurviveReconnect() {
        hub.open(7L);
        publish(ProgressEvent.nodeStatus(7L, 10L, "rewrite", "RUNNING", 0, "第 1 轮"));
        publish(ProgressEvent.nodeStatus(7L, 10L, "rewrite", "SUCCEEDED", 0, "完成"));

        assertEquals(2, hub.bufferedEventCount(7L));

        // 刷新页面 = 旧连接断开 + 新连接建立。缓冲不随连接消失而清空，
        // 否则「刷新后事件流一片空白」这个缺陷就会以另一种形式回来。
        hub.open(7L);

        assertEquals(2, hub.bufferedEventCount(7L), "补发的历史必须熬过一次重连");
    }

    @Test
    @DisplayName("无人观看的任务不占缓冲（避免「看过的每个任务」都常驻一份）")
    void eventsWithoutViewerAreNotBuffered() {
        publish(ProgressEvent.nodeStatus(999L, 1L, "rewrite", "RUNNING", 0, "没人看"));

        assertEquals(0, hub.bufferedEventCount(999L),
                "没人看就不该记 —— 权威状态在库里，缓冲只为「刷新后还能看到过程」而存在");
    }

    @Test
    @DisplayName("缓冲有上限：超出后丢最旧的，不会随任务时长无限增长")
    void recentEventsAreBounded() {
        hub.open(7L);
        int overflow = 60;
        for (int i = 0; i < overflow; i++) {
            publish(ProgressEvent.nodeStatus(7L, 10L, "rewrite", "RUNNING", i, "第 " + i + " 条"));
        }

        assertEquals(50, hub.bufferedEventCount(7L),
                "上限必须生效：一次长任务的事件量远大于界面上能展示的条数");
    }

    @Test
    @DisplayName("不同任务的缓冲互不干扰")
    void buffersAreIsolatedPerTask() {
        hub.open(1L);
        hub.open(2L);
        publish(ProgressEvent.nodeStatus(1L, 10L, "rewrite", "RUNNING", 0, "任务 1"));
        publish(ProgressEvent.nodeStatus(2L, 20L, "rewrite", "RUNNING", 0, "任务 2"));
        publish(ProgressEvent.nodeStatus(2L, 20L, "verify", "RUNNING", 0, "任务 2 又一条"));

        assertEquals(1, hub.bufferedEventCount(1L));
        assertEquals(2, hub.bufferedEventCount(2L));
    }

    @Test
    @DisplayName("销毁时缓冲一并释放")
    void destroyClearsBuffers() {
        hub.open(7L);
        publish(ProgressEvent.taskStatus(7L, "RUNNING", "进行中"));
        assertEquals(1, hub.bufferedEventCount(7L));

        hub.destroy();

        assertEquals(0, hub.bufferedEventCount(7L));
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    /** 手动把事件投给已注册的处理器，等价于 Redis 订阅线程收到消息。 */
    private void publish(ProgressEvent event) {
        listeners.forEach(listener -> listener.accept(event));
    }

    private TaskQueryService fakeQueryService() {
        return new TaskQueryService(null, null) {
            @Override
            public TaskDetailView taskDetail(long taskId) {
                snapshotRequests.add(taskId);
                if (missingTasks.contains(taskId)) {
                    throw new NotFoundException("任务不存在: " + taskId);
                }
                // 末三位 plan=null / gate=null / writeBack=null：本用例只验「快照先发、增量后发」的顺序，不关心内容
                return new TaskDetailView(null, List.of(), List.of(), null, null, null, null);
            }
        };
    }
}
