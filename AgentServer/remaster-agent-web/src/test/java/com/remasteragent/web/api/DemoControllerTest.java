package com.remasteragent.web.api;

import com.remasteragent.core.progress.ProgressPublisher;
import com.remasteragent.core.queue.TaskQueue;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.web.api.dto.TaskView;
import com.remasteragent.web.config.DemoProperties;
import com.remasteragent.web.sse.SseEventHub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

/**
 * 演示端点的硬约束：
 * <ol>
 *   <li>未开启 → 404（落地页 CTA 据此隐藏）；</li>
 *   <li>并发已满 → 429（不排队，直接拒绝）；</li>
 *   <li>没配样本 → 500（配置缺失，不是客户端错）；</li>
 *   <li>客户端给了不存在的 key → 400（key 是客户端唯一可控的输入）；</li>
 *   <li>开启且样本有效 → 以 demo=true 创建任务并投递，路径只从配置解析；</li>
 *   <li>不带 body → 用第一个样本（默认剧本）。</li>
 * </ol>
 * 样本目录用 {@code @TempDir} + 一个最小 pom.xml 提供，避免依赖真实工程。
 */
@DisplayName("DemoController：演示端点")
class DemoControllerTest {

    private static final String POM = "<project/>";

    private final TaskStore taskStore = mock(TaskStore.class);
    private final TaskQueue taskQueue = mock(TaskQueue.class);
    private final TaskQueryService queryService = mock(TaskQueryService.class);
    private final SseEventHub sseEventHub = mock(SseEventHub.class);
    private final ProgressPublisher progressPublisher = mock(ProgressPublisher.class);

    private DemoController controller(DemoProperties props) {
        return new DemoController(taskStore, taskQueue, queryService, sseEventHub, progressPublisher, props);
    }

    /** 造一份只含单个样本的配置（样本根指向给定目录，入口文件留空 = 整仓升级路径）。 */
    private static DemoProperties singleSample(boolean enabled, String projectRoot, int concurrency) {
        return new DemoProperties(enabled,
                List.of(new DemoProperties.Sample("legacy-demo", "报表服务", projectRoot, null)),
                concurrency, null, null, null, null);
    }

    @Test
    @DisplayName("未开启演示 → 404")
    void disabledReturns404() {
        DemoProperties props = singleSample(false, "E:/whatever", 2);
        var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller(props).run(null));
        assertEquals(NOT_FOUND, ex.getStatusCode());
        verify(taskStore, never()).createTask(anyString(), anyString(), anyInt(), anyString(), eq(true));
    }

    @Test
    @DisplayName("并发已满 → 429 且不创建任务")
    void concurrencyFullReturns429() {
        DemoProperties props = singleSample(true, "E:/whatever", 2);
        when(taskStore.countActiveDemoTasks()).thenReturn(2);
        var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller(props).run(null));
        assertEquals(TOO_MANY_REQUESTS, ex.getStatusCode());
        verify(taskStore, never()).createTask(anyString(), anyString(), anyInt(), anyString(), eq(true));
    }

    @Test
    @DisplayName("没配样本 → 500，且不创建任务")
    void noSamplesReturns500() {
        DemoProperties props = new DemoProperties(true, List.of(), 2, null, null, null, null);
        when(taskStore.countActiveDemoTasks()).thenReturn(0);
        var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller(props).run(null));
        assertEquals(INTERNAL_SERVER_ERROR, ex.getStatusCode());
        verify(taskStore, never()).createTask(anyString(), anyString(), anyInt(), anyString(), eq(true));
    }

    @Test
    @DisplayName("未知样本 key → 400，路径永不由客户端决定")
    void unknownSampleKeyReturns400() {
        DemoProperties props = singleSample(true, "E:/whatever", 2);
        when(taskStore.countActiveDemoTasks()).thenReturn(0);
        var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller(props).run(new DemoController.RunRequest("does-not-exist")));
        assertEquals(BAD_REQUEST, ex.getStatusCode());
        verify(taskStore, never()).createTask(anyString(), anyString(), anyInt(), anyString(), eq(true));
    }

    @Test
    @DisplayName("开启且样本有效 → 以 demo=true 创建并投递")
    void runCreatesDemoTask(@TempDir Path dir) throws Exception {
        // ProjectPathValidator 要求目录存在且含 pom.xml；entryFile 留空走整仓升级路径
        Files.writeString(dir.resolve("pom.xml"), POM);

        DemoProperties props = singleSample(true, dir.toAbsolutePath().toString(), 2);
        when(taskStore.countActiveDemoTasks()).thenReturn(0);
        when(taskStore.createTask(anyString(), any(), anyInt(), anyString(), eq(true))).thenReturn(42L);
        TaskView view = new TaskView(42L, dir.toString(), null, "演示：报表服务", 21, "PENDING",
                null, false, Instant.now(), Instant.now(), null, null, true);
        when(queryService.taskSummary(42L)).thenReturn(view);

        TaskView result = controller(props).run(null);

        assertEquals(42L, result.id());
        assertEquals(true, result.demo());
        // 任务名带上样本名，列表里能区分跑的是哪个剧本
        verify(taskStore).createTask(anyString(), isNull(), eq(21), eq("演示：报表服务"), eq(true));
        verify(taskQueue).enqueue(eq(42L), any());
    }

    @Test
    @DisplayName("按 key 选样本：跑的是被选中的那个工程")
    void runPicksRequestedSample(@TempDir Path first, @TempDir Path second) throws Exception {
        Files.writeString(first.resolve("pom.xml"), POM);
        Files.writeString(second.resolve("pom.xml"), POM);

        DemoProperties props = new DemoProperties(true, List.of(
                new DemoProperties.Sample("a", "样本A", first.toAbsolutePath().toString(), null),
                new DemoProperties.Sample("b", "样本B", second.toAbsolutePath().toString(), null)),
                2, null, null, null, null);
        when(taskStore.countActiveDemoTasks()).thenReturn(0);
        when(taskStore.createTask(anyString(), any(), anyInt(), anyString(), eq(true))).thenReturn(7L);
        when(queryService.taskSummary(7L)).thenReturn(new TaskView(7L, second.toString(), null, "演示：样本B", 21,
                "PENDING", null, false, Instant.now(), Instant.now(), null, null, true));

        controller(props).run(new DemoController.RunRequest("b"));

        // 选 b 就必须落在 b 的目录上（而不是默认的 a）
        verify(taskStore).createTask(eq(second.toAbsolutePath().toString()), isNull(), eq(21), eq("演示：样本B"), eq(true));
    }

    @Test
    @DisplayName("样本列表：把配置里的 key/名称/路径/目标 JDK 原样给前端")
    void samplesExposedToFrontend() {
        DemoProperties props = new DemoProperties(true, List.of(
                new DemoProperties.Sample("legacy-demo", "示例一", "E:/p1", "src/main/java/A.java"),
                new DemoProperties.Sample("legacy-unfixable", "示例三", "E:/p3", "src/main/java/C.java")),
                2, null, null, null, null);

        List<DemoController.SampleView> views = controller(props).samples();

        assertEquals(2, views.size());
        assertEquals("legacy-demo", views.get(0).key());
        assertEquals("示例一", views.get(0).name());
        assertEquals("E:/p1", views.get(0).projectRoot());
        assertEquals("src/main/java/A.java", views.get(0).entryFile());
        assertEquals(21, views.get(0).targetJdk());
        assertEquals("legacy-unfixable", views.get(1).key());
    }
}
