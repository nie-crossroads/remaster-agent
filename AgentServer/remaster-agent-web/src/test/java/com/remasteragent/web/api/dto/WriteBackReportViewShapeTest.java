package com.remasteragent.web.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.remasteragent.core.writeback.WriteBackReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link WriteBackReportView} 的<b>线上 JSON 形状</b>。
 *
 * <h2>为什么必须单独测形状</h2>
 * <p>本类里有两个看着像 getter 的方法：{@code ready()} 与 {@code applied()}。
 * 它们是 record 的<b>非组件</b>方法，名字又没有 {@code get}/{@code is} 前缀，
 * 所以 Jackson 序列化 record 时<b>根本不会带上它们</b>。
 *
 * <p>这个坑本项目已经付过一次代价：{@code TaskMetrics} 的派生方法
 * （{@code compilePassRate()} / {@code isGreen()} 那一类）在跨进程事件载荷里全丢，
 * 前端拿到的对象少了几个键、整体覆盖后指标面板静默清零 —— 任务完全正常，
 * 没有任何报错和日志。当时的结论是「同一语义两个来源 ⇒ 必须有测试断言形状相同」，
 * 这里就是那条结论的延续。
 *
 * <p>因此对外契约刻意定成「<b>判定从源数据现算</b>」：预检通过 = {@code blocked} 为空，
 * 已写回 = {@code appliedAt != null}。本测试同时断言那两个布尔<b>不出现在载荷里</b> ——
 * 一旦有人给它们补上 {@code get} 前缀，它们就会突然出现在线上 JSON 里，
 * 前端若同时存在两条判据就会漂移，这个断言会在那一刻失败并把人拦下来。
 */
class WriteBackReportViewShapeTest {

    /** 与 `AgentVue/src/api/types.ts` 的 `WriteBackReport` 逐字段对应。 */
    private static final Set<String> EXPECTED_TOP_LEVEL_FIELDS = Set.of(
            "taskId", "projectRoot", "workspace", "backupDir",
            "files", "blocked", "warnings", "suggestedCommitMessage", "appliedAt");

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("顶层的键与前端类型逐个对应，且派生布尔不出现在载荷里")
    void topLevelFieldsMatchFrontendContract() throws Exception {
        WriteBackReport report = new WriteBackReport(7L, "E:/demo", "E:/ws/task-7", "E:/backups/task-7",
                List.of(new WriteBackReport.FileEntry("src/main/java/Demo.java", 128L, "abc", true)),
                List.of(new WriteBackReport.Blocked("DIRTY_WORKING_TREE", "请先 git stash")),
                List.of("不是 git 工作区"), "refactor: 迁移", Instant.parse("2026-09-17T08:00:00Z"));

        JsonNode node = MAPPER.readTree(MAPPER.writeValueAsString(WriteBackReportView.of(report)));

        Set<String> actual = StreamSupport.stream(
                        ((Iterable<String>) () -> node.fieldNames()).spliterator(), false)
                .collect(Collectors.toSet());
        assertEquals(EXPECTED_TOP_LEVEL_FIELDS, actual,
                "顶层字段集变了就必须同步 AgentVue/src/api/types.ts");

        // 这两个是 record 的非组件方法，Jackson 不带它们出去 —— 前端不能依赖
        assertFalse(actual.contains("ready"), "ready() 不是 record 组件，不该出现在载荷里");
        assertFalse(actual.contains("applied"), "applied() 不是 record 组件，不该出现在载荷里");
    }

    @Test
    @DisplayName("嵌套的 files / blocked 形状与前端类型一致")
    void nestedFieldsMatchContract() throws Exception {
        WriteBackReport report = new WriteBackReport(7L, "E:/demo", "E:/ws/task-7", null,
                List.of(new WriteBackReport.FileEntry("src/main/java/Demo.java", 128L, "abc", true)),
                List.of(new WriteBackReport.Blocked("BASE_MISMATCH", "源文件已被改动")),
                List.of(), null, null);

        JsonNode node = MAPPER.readTree(MAPPER.writeValueAsString(WriteBackReportView.of(report)));

        assertEquals(Set.of("filePath", "bytes", "sha256", "baseOk"), fieldNames(node.get("files").get(0)));
        assertEquals(Set.of("code", "message"), fieldNames(node.get("blocked").get(0)));
        assertTrue(node.get("appliedAt").isNull(), "只做预检时 appliedAt 必须是 null（前端据此判「还没写」）");
    }

    private static Set<String> fieldNames(JsonNode node) {
        return StreamSupport.stream(((Iterable<String>) () -> node.fieldNames()).spliterator(), false)
                .collect(Collectors.toSet());
    }
}
