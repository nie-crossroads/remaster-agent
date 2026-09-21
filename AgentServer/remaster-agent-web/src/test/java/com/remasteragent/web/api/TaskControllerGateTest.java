package com.remasteragent.web.api;

import com.remasteragent.common.domain.GateStatus;
import com.remasteragent.common.domain.HumanGate;
import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.core.progress.ProgressPublisher;
import com.remasteragent.core.queue.TaskQueue;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.web.sse.SseEventHub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GATE 门禁审批接口（{@code /gate/approve | /reject}）的单测。
 *
 * <p>钉住的是「批准」这件事必须<b>原子地</b>包含三件缺一不可的动作，
 * 以及「只有真正等待中的门禁才能被处理」这道闸门：
 * <ol>
 *   <li>落定门禁行（{@code decideGate}）—— 否则 Worker 起来时门还是 PENDING，会被立刻挂回去；</li>
 *   <li>门禁节点转 SUCCEEDED —— 否则下游 VERIFY 永远等不到依赖；</li>
 *   <li>任务置 PENDING 并重新入队 —— 否则任务永远挂着没人执行。</li>
 * </ol>
 * 少任何一件都会让「批准」看起来成功了、任务却纹丝不动 —— 那是最难查的一类问题。
 */
class TaskControllerGateTest {

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
    // 批准
    // ==================================================================

    @Test
    @DisplayName("批准门禁：落定 + 节点转成功 + 任务重新入队，三件一起发生")
    void approveDecidesGateAndRequeues() {
        givenWaitingWithOpenGate();

        when(taskStore.decideGate(GATE_ID, GateStatus.APPROVED, "alice", null)).thenReturn(1);

        controller.approveGate(TASK_ID, new TaskController.DecideGateRequest("alice", null));

        verify(taskStore).decideGate(GATE_ID, GateStatus.APPROVED, "alice", null);
        verify(taskStore).markNodeSucceeded(GATE_NODE_ID, null);
        verify(taskStore).updateTaskStatus(TASK_ID, TaskStatus.PENDING, null);
        // 重新入队时必须把当前请求的 traceparent 带上（这里没有活动 span，所以是 null）：
        // 这一次「续跑」由人点按钮触发，它的 trace 根该落在那个 HTTP 请求上
        verify(taskQueue).enqueue(TASK_ID, null);
    }

    @Test
    @DisplayName("重复批准（门禁已被处理）：返回 409，且不重复入队")
    void duplicateApproveConflicts() {
        givenWaitingWithOpenGate();
        // decideGate 影响 0 行 = 行已被别人抢先处理过
        when(taskStore.decideGate(GATE_ID, GateStatus.APPROVED, null, null)).thenReturn(0);

        assertThrows(ConflictException.class,
                () -> controller.approveGate(TASK_ID, null));

        verify(taskQueue, never()).enqueue(anyLong(), any());
    }

    @Test
    @DisplayName("任务不在 WAITING_HUMAN：返回 409")
    void approveRejectsWrongStatus() {
        when(taskStore.findTask(TASK_ID)).thenReturn(Optional.of(task(TaskStatus.RUNNING)));

        assertThrows(ConflictException.class,
                () -> controller.approveGate(TASK_ID, null));

        verify(taskStore, never()).decideGate(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("状态对但没有等待中的门禁（其实是规划评审）：返回 409，指向 plan 接口")
    void approveRejectsWhenNoOpenGate() {
        when(taskStore.findTask(TASK_ID)).thenReturn(Optional.of(task(TaskStatus.WAITING_HUMAN)));
        when(taskStore.findOpenGate(TASK_ID)).thenReturn(Optional.empty());

        assertThrows(ConflictException.class,
                () -> controller.approveGate(TASK_ID, null));
    }

    // ==================================================================
    // 驳回
    // ==================================================================

    @Test
    @DisplayName("驳回门禁：门禁标 REJECTED、节点判失败、任务判失败")
    void rejectFailsNodeAndTask() {
        givenWaitingWithOpenGate();
        when(taskStore.decideGate(GATE_ID, GateStatus.REJECTED, "bob", "补丁有问题")).thenReturn(1);

        controller.rejectGate(TASK_ID, new TaskController.DecideGateRequest("bob", "补丁有问题"));

        verify(taskStore).decideGate(GATE_ID, GateStatus.REJECTED, "bob", "补丁有问题");
        verify(taskStore).markNodeFailed(GATE_NODE_ID, "人工门禁被驳回: 补丁有问题", null);
        verify(taskStore).updateTaskStatus(TASK_ID, TaskStatus.FAILED, "人工门禁被驳回: 补丁有问题");
        verify(taskQueue, never()).enqueue(anyLong(), any());
    }

    // ------------------------------------------------------------------

    private void givenWaitingWithOpenGate() {
        when(taskStore.findTask(TASK_ID)).thenReturn(Optional.of(task(TaskStatus.WAITING_HUMAN)));
        when(taskStore.findOpenGate(TASK_ID)).thenReturn(Optional.of(
                new HumanGate(GATE_ID, GATE_NODE_ID, GateStatus.PENDING, null,
                        "已改写 A.java，请确认补丁后再继续验证", Instant.now(), null)));
    }

    private static MigrationTask task(TaskStatus status) {
        Instant now = Instant.now();
        return new MigrationTask(TASK_ID, "/proj", "A.java", 21, status, null, null, false, now, now, null, false);
    }
}
