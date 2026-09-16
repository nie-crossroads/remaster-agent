package com.remasteragent.web.api;

import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.GateStatus;
import com.remasteragent.common.domain.HumanGate;
import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.NodeStatus;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.core.progress.ProgressPublisher;
import com.remasteragent.core.queue.TaskQueue;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.web.api.dto.TaskTraceView;
import com.remasteragent.web.api.dto.TaskView;
import com.remasteragent.web.sse.SseEventHub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务控制接口（{@code /cancel}、{@code /retry}）与 {@code /trace} 的单测。
 *
 * <h2>被钉住的三条判断</h2>
 * <ol>
 *   <li><b>取消是协作式的。</b> RUNNING 的任务只能置标志位，<b>不能</b>把状态改成 CANCELLED ——
 *       此刻确实还有节点在跑，写成「已取消」就是撒谎。而状态一旦会说谎，
 *       它作为排查线索的价值就没了。</li>
 *   <li><b>重跑必须先清取消标志。</b> 不清的话，Worker 取到任务的第一轮循环就会看到
 *       「已请求取消」并立刻停下 —— 表现成「点了重跑，任务闪一下就又变成已取消」。</li>
 *   <li><b>「一个节点都没有」不等于不能重跑。</b> 那说明任务在铺开 DAG 之前就停了
 *       （建单后立刻取消，或在 PLAN 之前就失败）—— 重新入队后 Worker 会重新铺一遍 DAG，
 *       是能跑的。这里曾经返 409，实测挡住了最自然的一条路径：取消 → 又想跑起来。</li>
 * </ol>
 */
class TaskControllerControlTest {

    private static final long TASK_ID = 7L;
    private static final long GATE_ID = 5L;
    private static final long GATE_NODE_ID = 21L;

    private final TaskStore taskStore = mock(TaskStore.class);
    private final TaskQueue taskQueue = mock(TaskQueue.class);
    private final TaskQueryService queryService = mock(TaskQueryService.class);
    private final SseEventHub sseEventHub = mock(SseEventHub.class);
    private final ProgressPublisher progressPublisher = mock(ProgressPublisher.class);

    private final TaskController controller =
            new TaskController(taskStore, taskQueue, queryService, sseEventHub, progressPublisher);

    // ==================================================================
    // 取消
    // ==================================================================

    @Test
    @DisplayName("取消 PENDING 任务：协作标志 + 立即落 CANCELLED + 关掉等待中的门禁")
    void cancelPendingSettlesImmediately() {
        when(taskStore.findTask(TASK_ID)).thenReturn(Optional.of(task(TaskStatus.PENDING)));
        when(taskStore.findOpenGate(TASK_ID)).thenReturn(Optional.of(openGate()));
        when(taskStore.decideGate(eq(GATE_ID), eq(GateStatus.REJECTED), eq("system"), any()))
                .thenReturn(1);

        controller.cancel(TASK_ID, null);

        verify(taskStore).requestCancel(TASK_ID);
        verify(taskStore).updateTaskStatus(TASK_ID, TaskStatus.CANCELLED, "任务被人工取消");
        verify(taskStore).markNodeFailed(eq(GATE_NODE_ID), any(), any());
        verify(progressPublisher).publish(any());
    }

    @Test
    @DisplayName("取消 RUNNING 任务：只置标志位，【不】改状态 —— 此刻确实还有节点在跑")
    void cancelRunningOnlyFlags() {
        when(taskStore.findTask(TASK_ID)).thenReturn(Optional.of(task(TaskStatus.RUNNING)));
        when(queryService.taskSummary(TASK_ID)).thenReturn(summary(TaskStatus.RUNNING));

        TaskView view = controller.cancel(TASK_ID,
                new TaskController.CancelTaskRequest("跑歪了，先停"));

        verify(taskStore).requestCancel(TASK_ID);
        verify(taskStore, never()).updateTaskStatus(anyLong(), any(), any());
        assertEquals(TaskStatus.RUNNING.name(), view.status(),
                "返回的必须是真实状态：请求已发出，但任务还在跑。写成 CANCELLED 就是撒谎");
    }

    @Test
    @DisplayName("取消 WAITING_HUMAN 任务：直接落定，并把那道门记录成 REJECTED")
    void cancelWaitingHumanClosesGate() {
        when(taskStore.findTask(TASK_ID)).thenReturn(Optional.of(task(TaskStatus.WAITING_HUMAN)));
        when(taskStore.findOpenGate(TASK_ID)).thenReturn(Optional.of(openGate()));
        when(taskStore.decideGate(anyLong(), eq(GateStatus.REJECTED), eq("system"), any()))
                .thenReturn(1);

        controller.cancel(TASK_ID, null);

        verify(taskStore).updateTaskStatus(TASK_ID, TaskStatus.CANCELLED, "任务被人工取消");
        // 留着 PENDING 的门，详情页会一直挂着一张「待审批」卡片，而那个任务已经没人会去批了
        verify(taskStore).decideGate(GATE_ID, GateStatus.REJECTED, "system", "任务被人工取消");
    }

    @Test
    @DisplayName("取消已终态的任务：409，且不置任何标志位")
    void cancelTerminalConflicts() {
        when(taskStore.findTask(TASK_ID)).thenReturn(Optional.of(task(TaskStatus.SUCCEEDED)));

        assertThrows(ConflictException.class, () -> controller.cancel(TASK_ID, null));

        verify(taskStore, never()).requestCancel(anyLong());
    }

    @Test
    @DisplayName("取消不存在的任务：404")
    void cancelUnknownTaskIsNotFound() {
        when(taskStore.findTask(TASK_ID)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> controller.cancel(TASK_ID, null));
    }

    // ==================================================================
    // 重跑
    // ==================================================================

    @Test
    @DisplayName("重跑失败任务：把失败节点退回 PENDING、清取消标志、（清完才）重新入队")
    void retryResetsNodesAndRequeues() {
        when(taskStore.findTask(TASK_ID)).thenReturn(Optional.of(task(TaskStatus.FAILED)));
        when(taskStore.resetFailedNodes(TASK_ID)).thenReturn(2);

        controller.retry(TASK_ID);

        verify(taskStore).resetFailedNodes(TASK_ID);
        verify(taskStore).clearCancelRequest(TASK_ID);
        verify(taskStore).updateTaskStatus(TASK_ID, TaskStatus.PENDING, null);
        verify(taskQueue).enqueue(TASK_ID, null);
    }

    @Test
    @DisplayName("重跑被取消的任务：即使没有失败节点，只要还有 PENDING 节点就能续跑")
    void retryCancelledTaskResumesFromBreakpoint() {
        when(taskStore.findTask(TASK_ID)).thenReturn(Optional.of(task(TaskStatus.CANCELLED)));
        when(taskStore.resetFailedNodes(TASK_ID)).thenReturn(0);
        when(taskStore.findNodes(TASK_ID)).thenReturn(List.of(
                node(31L, NodeType.VERIFY, NodeStatus.PENDING)));

        controller.retry(TASK_ID);

        verify(taskStore).clearCancelRequest(TASK_ID);
        verify(taskQueue).enqueue(TASK_ID, null);
    }

    @Test
    @DisplayName("重跑一个 DAG 尚未铺开就被取消的任务：允许，重新入队让它从头铺 DAG")
    void retryWithNoNodesRestartsFromScratch() {
        when(taskStore.findTask(TASK_ID)).thenReturn(Optional.of(task(TaskStatus.CANCELLED)));
        when(taskStore.resetFailedNodes(TASK_ID)).thenReturn(0);
        when(taskStore.findNodes(TASK_ID)).thenReturn(List.of());

        controller.retry(TASK_ID);

        verify(taskStore).clearCancelRequest(TASK_ID);
        verify(taskStore).updateTaskStatus(TASK_ID, TaskStatus.PENDING, null);
        verify(taskQueue).enqueue(TASK_ID, null);
    }

    @Test
    @DisplayName("重跑正在进行的任务：409 —— 状态说了要跑，就不该再投一次")
    void retryNonTerminalConflicts() {
        when(taskStore.findTask(TASK_ID)).thenReturn(Optional.of(task(TaskStatus.RUNNING)));

        assertThrows(ConflictException.class, () -> controller.retry(TASK_ID));

        verify(taskQueue, never()).enqueue(anyLong(), any());
    }

    // ==================================================================
    // 链路
    // ==================================================================

    @Test
    @DisplayName("查链路：先确认任务存在（拼错的 id 不能安静地返回空列表）")
    void traceChecksTaskExists() {
        when(taskStore.findTask(TASK_ID)).thenReturn(Optional.empty());
        when(queryService.taskSummary(TASK_ID)).thenThrow(new NotFoundException("任务不存在: " + TASK_ID));

        assertThrows(NotFoundException.class, () -> controller.trace(TASK_ID));
    }

    @Test
    @DisplayName("查链路：无埋点时返回空视图，前端据此显示『这次执行没有链路数据』")
    void traceWithoutSpansIsEmptyButValid() {
        when(queryService.taskSummary(TASK_ID)).thenReturn(mock(TaskView.class));
        when(taskStore.findTraceSpans(TASK_ID)).thenReturn(List.of());

        TaskTraceView view = controller.trace(TASK_ID);

        assertEquals(0, view.traceCount());
        assertTrue(view.runs().isEmpty());
    }

    // ------------------------------------------------------------------

    private static MigrationTask task(TaskStatus status) {
        Instant now = Instant.now();
        return new MigrationTask(TASK_ID, "/proj", "A.java", 21, status, null, null, false, now, now);
    }

    private static HumanGate openGate() {
        return new HumanGate(GATE_ID, GATE_NODE_ID, GateStatus.PENDING, null,
                "已改写 A.java，请确认补丁后再继续验证", Instant.now(), null);
    }

    private static DagNode node(long id, NodeType type, NodeStatus status) {
        Instant now = Instant.now();
        return new DagNode(id, TASK_ID, type.name().toLowerCase(), type, List.of(), status, 0,
                null, null, now, now);
    }

    /** 与后端 record 字段一一对应的最小视图，用来断言接口返回值（而不是断 mock 的默认 null）。 */
    private static TaskView summary(TaskStatus status) {
        Instant now = Instant.now();
        return new TaskView(TASK_ID, "/proj", "A.java", 21, status.name(), null, false, now, now, null,
                // 累计运行时长：这里的 store 是内存实现、没落 span，所以是「不知道」而不是 0
                null);
    }
}
