package com.remasteragent.web.api;

import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.core.queue.TaskQueue;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.web.api.dto.CreateTaskRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 创建任务时「投递失败」这条路径的单测 —— 钉住的是<b>不留悬空任务</b>这个约定。
 *
 * <p>为什么值得单独测：创建任务的流程是「先 INSERT 拿到自增 id → 再 XADD 投队列」，
 * 这两步跨 PostgreSQL 与 Redis，没有共同事务。于是「任务行已入库、但队列里没有消息」
 * 是一个必然会出现的真实中间态（实测触发过：远程 Redis 超时）。
 *
 * <p>如果只是抛异常返回，这条任务会永远停在 {@code PENDING}：列表里显示「排队中」，
 * 但它永远不会动，也不会报错。这类「看起来在正常运行」的脏数据比直接失败危险得多 ——
 * 用户会一直等，而排查时又没有任何线索指向「它其实没被投递出去」。
 * 所以断言两件事：<b>状态被改成 FAILED</b>、并且<b>失败原因是可读的</b>。
 */
class TaskControllerEnqueueFailureTest {

    @Test
    void 投递失败时任务被标记为FAILED而不是留下悬空的PENDING() throws IOException {
        Path root = createValidProject();

        TaskStore taskStore = mock(TaskStore.class);
        TaskQueue taskQueue = mock(TaskQueue.class);
        TaskQueryService queryService = mock(TaskQueryService.class);

        when(taskStore.createTask(anyString(), anyString(), anyInt())).thenReturn(42L);
        // 打桩必须对准【两参】版本 —— 投递现在会把 API 侧的 traceparent 一起带过去。
        // 只桩单参版会静默不生效：doThrow 不匹配 → 「投递失败」这条路径根本没被触发，
        // 而断言报出来的是「期望抛异常但没抛」，很容易被误读成业务逻辑坏了。
        doThrow(new IllegalStateException("Redis command timed out after 10 second(s)"))
                .when(taskQueue).enqueue(eq(42L), any());

        TaskController controller = new TaskController(taskStore, taskQueue, queryService, null, null);
        CreateTaskRequest request = new CreateTaskRequest(
                root.toString(), "src/main/java/com/example/Demo.java", 21);

        QueueUnavailableException thrown = assertThrows(QueueUnavailableException.class,
                () -> controller.create(request));

        // 1) 任务被显式标记失败，而不是留在 PENDING 上「假装排队」
        verify(taskStore).updateTaskStatus(eq(42L), eq(TaskStatus.FAILED),
                org.mockito.ArgumentMatchers.contains("投递失败"));
        // 2) 异常消息里带上任务 id：调用方即使重试也能对照着查到这条失败记录
        assertTrue(thrown.getMessage().contains("42"), thrown.getMessage());
        // 3) 没有多余动作 —— 投递都没成功，不应该再去查概要
        verify(queryService, org.mockito.Mockito.never()).taskSummary(42L);
    }

    /**
     * 造一个能通过 {@link ProjectPathValidator} 的最小工程：目录 + pom.xml + .java 入口。
     *
     * <p>校验器是安全边界（见其类注释），这里不绕开它 —— 用一个真实但极小的工程目录，
     * 顺带保证「校验通过后才谈投递」这条顺序也被真实走过。
     */
    private static Path createValidProject() throws IOException {
        Path root = Files.createTempDirectory("remaster-enqueue-test");
        Files.writeString(root.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Path entry = root.resolve("src/main/java/com/example/Demo.java");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, "package com.example; public class Demo {}", StandardCharsets.UTF_8);
        return root;
    }
}
