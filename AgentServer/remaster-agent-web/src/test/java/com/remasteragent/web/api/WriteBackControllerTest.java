package com.remasteragent.web.api;

import com.remasteragent.core.writeback.SourceWriteBackService;
import com.remasteragent.core.writeback.WriteBackReport;
import com.remasteragent.web.api.dto.WriteBackReportView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回写接口（{@code /api/tasks/{id}/write-back}）的单测。
 *
 * <p>钉住三件事：
 * <ol>
 *   <li><b>预检不写盘</b> —— GET 只调 {@code preflight}，绝不能顺手调 {@code apply}；</li>
 *   <li><b>被拦不是异常</b> —— 拦路项要原样透出成报告（前端渲染成卡片），不能变成 500；</li>
 *   <li><b>任务不存在是 404</b> —— 拼错的 id 不该伪装成「这个任务回写不了」。</li>
 * </ol>
 */
class WriteBackControllerTest {

    private static final long TASK_ID = 7L;

    private final SourceWriteBackService service = mock(SourceWriteBackService.class);
    private final TaskQueryService queryService = mock(TaskQueryService.class);

    private final WriteBackController controller = new WriteBackController(service, queryService);

    @Test
    @DisplayName("GET 预检：只检查不写盘，把待写清单透出")
    void preflightOnlyChecks() {
        when(service.preflight(TASK_ID)).thenReturn(report(null,
                List.of(new WriteBackReport.FileEntry("src/main/java/Demo.java", 128L, "abc", true)),
                List.of(), List.of("不是 git 工作区，回写后无版本控制兜底")));

        WriteBackReportView view = controller.preflight(TASK_ID);

        assertTrue(view.ready());
        assertFalse(view.applied(), "预检绝不能真的写盘");
        assertEquals(1, view.files().size());
        assertEquals("src/main/java/Demo.java", view.files().get(0).filePath());
        assertTrue(view.files().get(0).baseOk());
        assertEquals(1, view.warnings().size());
        // 只调预检，没调执行 —— 这是本接口最重要的不变量
        verify(service).preflight(TASK_ID);
        verify(service, never()).apply(TASK_ID);
    }

    @Test
    @DisplayName("GET 预检：拦路项原样透出，供前端渲染成「为什么不能写」")
    void preflightPassesBlockersThrough() {
        when(service.preflight(TASK_ID)).thenReturn(report(null, List.of(),
                List.of(new WriteBackReport.Blocked("DIRTY_WORKING_TREE",
                        "源工程工作区不干净（git 工作区有 3 处未提交改动）—— 请先 git commit 或 git stash")),
                List.of()));

        WriteBackReportView view = controller.preflight(TASK_ID);

        assertFalse(view.ready());
        assertEquals(1, view.blocked().size());
        assertEquals("DIRTY_WORKING_TREE", view.blocked().get(0).code());
        assertTrue(view.blocked().get(0).message().contains("git stash"), "说明里必须带怎么解决");
    }

    @Test
    @DisplayName("POST 执行成功：透出写入时间与备份目录")
    void applyReportsSuccess() {
        WriteBackReport applied = report(Instant.now(),
                List.of(new WriteBackReport.FileEntry("src/main/java/Demo.java", 128L, "abc", true)),
                List.of(), List.of());
        when(service.apply(TASK_ID)).thenReturn(applied);

        WriteBackReportView view = controller.apply(TASK_ID);

        assertTrue(view.applied());
        assertNotNull(view.appliedAt());
        assertEquals("E:/backups/task-7", view.backupDir());
        assertNotNull(view.suggestedCommitMessage(), "建议提交信息随报告返回，供人直接采用");
    }

    @Test
    @DisplayName("POST 被拦：返回报告而不是抛异常（拦路项本身就是结果的内容）")
    void applyBlockedIsNotAnError() {
        when(service.apply(TASK_ID)).thenReturn(report(null, List.of(),
                List.of(new WriteBackReport.Blocked("TASK_NOT_SUCCEEDED",
                        "任务当前状态是 FAILED，只有 SUCCEEDED 的任务才能回写")),
                List.of()));

        WriteBackReportView view = controller.apply(TASK_ID);

        assertFalse(view.applied());
        assertEquals("TASK_NOT_SUCCEEDED", view.blocked().get(0).code());
    }

    @Test
    @DisplayName("任务不存在：两个端点都 404，且不触碰回写服务")
    void missingTaskIsNotFound() {
        when(queryService.taskSummary(404L)).thenThrow(new NotFoundException("任务不存在: 404"));

        assertThrows(NotFoundException.class, () -> controller.preflight(404L));
        assertThrows(NotFoundException.class, () -> controller.apply(404L));
        verify(service, never()).preflight(404L);
        verify(service, never()).apply(404L);
    }

    // ------------------------------------------------------------------

    private static WriteBackReport report(Instant appliedAt,
                                          List<WriteBackReport.FileEntry> files,
                                          List<WriteBackReport.Blocked> blocked,
                                          List<String> warnings) {
        return new WriteBackReport(TASK_ID, "E:/demo", "E:/ws/task-7", "E:/backups/task-7",
                files, blocked, warnings,
                "refactor: 将 demo 迁移到 JDK 21\n\n由 RemasterAgent 任务 #7 自动改写", appliedAt);
    }
}
