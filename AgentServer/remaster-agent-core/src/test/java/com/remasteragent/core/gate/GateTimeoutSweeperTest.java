package com.remasteragent.core.gate;

import com.remasteragent.common.domain.GateStatus;
import com.remasteragent.common.domain.HumanGate;
import com.remasteragent.common.domain.NodeStatus;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.core.progress.ProgressEvent;
import com.remasteragent.core.progress.ProgressPublisher;
import com.remasteragent.core.store.InMemoryTaskStore;
import com.remasteragent.core.store.TaskStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 门禁超时回收的单测。
 *
 * <p>这个类要钉住的核心判断只有一句：<b>「超过阈值仍无人处理的 PENDING 门禁 → 判失败」</b>，
 * 但它有三处极易写错、且错了不会报错的边界，都在这里被固定下来：
 * <ol>
 *   <li><b>默认不生效。</b> 超时配 0 表示「永不超时」—— 门禁的语义就是等人，
 *       而人在开会、在睡觉。一个会自动把等人中的任务判死的默认值，坏处远大于好处。</li>
 *   <li><b>「已被处理过」必须立刻放手。</b> 扫到与处理之间，人可能刚好点了批准。
 *       此时若照样判失败，就是把一个刚刚被批准的任务覆盖成失败 —— 本类专门测这条竞态。</li>
 *   <li><b>只处理此刻真的挡路的门。</b> 历史上已批过/驳过的门哪怕 created_at 很老，
 *       也不能被再次动作。</li>
 * </ol>
 */
class GateTimeoutSweeperTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(30);

    // ==================================================================
    // 1. 默认关闭
    // ==================================================================

    @Test
    @DisplayName("超时配 0（默认）：永不生效，超期门禁也一动不动")
    void disabledByDefault() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = waitingOnGate(store);

        GateTimeoutSweeper sweeper = sweeper(store, Duration.ZERO,
                Clock.offset(Clock.systemUTC(), Duration.ofDays(30)));

        assertEquals(new GateTimeoutSweeper.SweepReport(0, 0), sweeper.sweep());

        assertEquals(TaskStatus.WAITING_HUMAN, store.findTask(taskId).orElseThrow().status(),
                "默认必须什么都不做：等人 ≠ 失败");
        assertTrue(store.findOpenGate(taskId).isPresent());
    }

    // ==================================================================
    // 2. 超期 → 判失败
    // ==================================================================

    @Test
    @DisplayName("门禁超期未处理：门禁判 REJECTED（审批人 system）、节点判失败、任务判失败")
    void overdueGateFailsTask() {
        RecordingPublisher publisher = new RecordingPublisher();
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = waitingOnGate(store);
        long gateId = store.findOpenGate(taskId).orElseThrow().id();

        GateTimeoutSweeper sweeper = new GateTimeoutSweeper(TIMEOUT, Duration.ofMinutes(5), store,
                publisher, Clock.offset(Clock.systemUTC(), Duration.ofHours(2)));

        GateTimeoutSweeper.SweepReport report = sweeper.sweep();

        assertEquals(1, report.scanned());
        assertEquals(1, report.expired());

        HumanGate gate = store.findGate(gateId).orElseThrow();
        assertEquals(GateStatus.REJECTED, gate.status());
        assertEquals("system", gate.reviewer(),
                "审批人要写清楚『不是你批的』—— 留 null 会让人以为没人处理过");
        assertTrue(gate.comment().contains("超时"), "失败原因要能一眼看出是超时，实际: " + gate.comment());

        assertEquals(NodeStatus.FAILED, store.statusOf(taskId, nodeKey(), 0));
        assertEquals(TaskStatus.FAILED, store.findTask(taskId).orElseThrow().status());
        assertTrue(store.findTask(taskId).orElseThrow().failReason().contains("超时"));

        assertEquals(1, publisher.events.size(), "必须推一条状态事件，否则前端会一直显示『待人工』");
        ProgressEvent event = publisher.events.get(0);
        assertEquals(taskId, event.taskId());
        assertEquals(TaskStatus.FAILED.name(), event.status());
        assertFalse(store.findOpenGate(taskId).isPresent(),
                "处理完就不该再有等待中的门 —— 否则详情页会一直挂着那张审批卡片");
    }

    @Test
    @DisplayName("还没超期：不动")
    void freshGateIsUntouched() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = waitingOnGate(store);

        // 时钟只往后拨 5 分钟，阈值是 30 分钟 → 尚未超期
        GateTimeoutSweeper sweeper = new GateTimeoutSweeper(TIMEOUT, Duration.ofMinutes(5), store,
                ProgressPublisher.NOOP, Clock.offset(Clock.systemUTC(), Duration.ofMinutes(5)));

        assertEquals(new GateTimeoutSweeper.SweepReport(0, 0), sweeper.sweep());
        assertEquals(TaskStatus.WAITING_HUMAN, store.findTask(taskId).orElseThrow().status());
    }

    @Test
    @DisplayName("历史上已批过的门（哪怕很久以前）：不会再被动作 —— 它早就不是阻塞点了")
    void alreadyDecidedGateIsNotTouched() {
        InMemoryTaskStore store = new InMemoryTaskStore();
        long taskId = waitingOnGate(store);
        long gateId = store.findOpenGate(taskId).orElseThrow().id();
        store.decideGate(gateId, GateStatus.APPROVED, "alice", "OK");

        GateTimeoutSweeper sweeper = new GateTimeoutSweeper(TIMEOUT, Duration.ofMinutes(5), store,
                ProgressPublisher.NOOP, Clock.offset(Clock.systemUTC(), Duration.ofDays(7)));

        assertEquals(new GateTimeoutSweeper.SweepReport(0, 0), sweeper.sweep());
        assertEquals(GateStatus.APPROVED, store.findGate(gateId).orElseThrow().status(),
                "已批准的门绝不能被超时逻辑改写成 REJECTED");
    }

    // ==================================================================
    // 3. 竞态：扫到与处理之间，人抢先批准了
    // ==================================================================

    @Test
    @DisplayName("竞态：处理前一刻被人批准 → 立刻放手，绝不覆盖人的决定")
    void losesRaceAgainstHumanApproval() {
        TaskStore store = mock(TaskStore.class);
        ProgressPublisher publisher = mock(ProgressPublisher.class);
        long gateId = 5L;
        long nodeId = 21L;
        long taskId = 7L;

        when(store.findOverdueGates(any())).thenReturn(List.of(
                new TaskStore.OpenGateRef(gateId, nodeId, taskId, nodeKey(),
                        Instant.now().minus(Duration.ofHours(2)))));
        // decideGate 影响 0 行 = 有人在这中间把门批了
        when(store.decideGate(eq(gateId), eq(GateStatus.REJECTED), eq("system"), anyString()))
                .thenReturn(0);

        GateTimeoutSweeper sweeper = new GateTimeoutSweeper(TIMEOUT, Duration.ofMinutes(5), store,
                publisher, Clock.systemUTC());
        GateTimeoutSweeper.SweepReport report = sweeper.sweep();

        assertEquals(1, report.scanned());
        assertEquals(0, report.expired(), "没真正处理掉，就不能记成 1");

        verify(store, never()).markNodeFailed(anyLong(), anyString(), any());
        verify(store, never()).updateTaskStatus(anyLong(), any(), any());
        verify(publisher, never()).publish(any());
    }

    // ==================================================================
    // 4. 节流与容错
    // ==================================================================

    @Test
    @DisplayName("sweepIfDue 按间隔节流：间隔内重复调用不会重复查库")
    void sweepIfDueThrottlesByInterval() {
        MutableClock clock = new MutableClock(Instant.now());
        TaskStore store = mock(TaskStore.class);
        when(store.findOverdueGates(any())).thenReturn(List.of());

        GateTimeoutSweeper sweeper = new GateTimeoutSweeper(TIMEOUT, Duration.ofMinutes(5), store,
                ProgressPublisher.NOOP, clock);

        sweeper.sweepIfDue();
        verify(store, times(1)).findOverdueGates(any());

        clock.advance(Duration.ofMinutes(1));
        sweeper.sweepIfDue();
        verify(store, times(1)).findOverdueGates(any());

        clock.advance(Duration.ofMinutes(5));
        sweeper.sweepIfDue();
        verify(store, times(2)).findOverdueGates(any());
        // 首次调用必须立即扫一次（Worker 一启动就该处理历史遗留），之后才按间隔来
    }

    @Test
    @DisplayName("查库失败：按『本轮不处理』跳过，绝不把等人中的任务误判死")
    void queryFailureIsSkippedNotGuessed() {
        TaskStore store = mock(TaskStore.class);
        when(store.findOverdueGates(any())).thenThrow(new IllegalStateException("连接池已关闭"));

        GateTimeoutSweeper sweeper = new GateTimeoutSweeper(TIMEOUT, Duration.ofMinutes(5), store,
                ProgressPublisher.NOOP, Clock.systemUTC());

        GateTimeoutSweeper.SweepReport report = sweeper.sweep();

        assertEquals(new GateTimeoutSweeper.SweepReport(0, 0), report);
        verify(store, never()).updateTaskStatus(anyLong(), any(), anyString());
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    private static String nodeKey() {
        return "gate:src/main/java/com/example/Demo.java";
    }

    /** 造一个「已挂在一道等待中门禁上」的任务：ANALYZE 成功 + GATE 挂起。 */
    private static long waitingOnGate(InMemoryTaskStore store) {
        long taskId = store.createTask("E:/demo", "src/main/java/com/example/Demo.java", 21, null);
        store.insertNode(taskId, "analyze", NodeType.ANALYZE, List.of(), 0);
        long gateNodeId = store.insertNode(taskId, nodeKey(), NodeType.GATE, List.of(), 0);
        store.insertGate(gateNodeId, "已改写 Demo.java，请确认补丁后再继续验证");
        store.updateTaskStatus(taskId, TaskStatus.WAITING_HUMAN, null);
        return taskId;
    }

    private static GateTimeoutSweeper sweeper(InMemoryTaskStore store, Duration timeout, Clock clock) {
        return new GateTimeoutSweeper(timeout, Duration.ofMinutes(5), store,
                ProgressPublisher.NOOP, clock);
    }

    /** 记录被推出去的事件，供断言。 */
    private static final class RecordingPublisher implements ProgressPublisher {
        private final List<ProgressEvent> events = new ArrayList<>();

        @Override
        public void publish(ProgressEvent event) {
            events.add(event);
        }
    }

    /**
     * 可手推的时钟 —— 节流逻辑的判据是「距上次扫描过了多久」，
     * 用真实时钟测它就得 sleep 五分钟。可注入时钟是这个判据能被确定性验证的前提。
     */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
