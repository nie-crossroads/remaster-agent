package com.remasteragent.web.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remasteragent.common.domain.TaskMetrics;
import com.remasteragent.common.domain.TaskMetricsSnapshot;
import com.remasteragent.web.api.dto.TaskView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标「快照」与「增量事件」的 JSON 形状契约测试。
 *
 * <h2>这个测试是被一个真实 bug 逼出来的</h2>
 * <p>前端把 SSE 增量<b>叠加</b>在 REST 快照之上：先 {@code GET /api/tasks/{id}} 建底，
 * 再用 {@code task_metrics} 事件覆盖 {@code task.metrics}。两条路的载荷本来是同一个语义，
 * 但形状不同：
 *
 * <ul>
 *   <li>REST 走的是显式映射（{@code TaskMetrics} → {@code MetricsView}），通过率字段齐全；</li>
 *   <li>SSE 曾把 {@code TaskMetrics} <b>直接</b>序列化，而 {@code compilePassRate()} /
 *       {@code testPassRate()} / {@code retried()} 是 record 的<b>派生方法</b>，
 *       Jackson 对 record 只认组件 —— 这三个字段整个消失。</li>
 * </ul>
 *
 * <p>后果：任务刚跑完，最后一个 {@code task_metrics} 事件把指标换成缺字段的对象，
 * 前端 {@code compilePassRate == null} → 通过率算成 0 → 进度条空、图标变 ✗，
 * <b>而任务明明是成功的</b>。最难查的一点是 {@code coverage} 恰好是 record 组件，
 * 所以「行覆盖率」一直显示 100%，看起来像进度条组件坏了，而不是数据形状问题。
 *
 * <h2>钉住什么</h2>
 * <ol>
 *   <li>{@link TaskMetricsSnapshot} 与 {@link TaskView.MetricsView} 的属性名集合<b>必须完全相同</b>
 *       —— 两边任何一个加字段都必须同步改另一个，否则前端会再一次「投影出与快照不一致的状态」。</li>
 *   <li>{@link TaskMetrics} 直接序列化时<b>确实缺</b>派生字段 —— 把曾经的 bug 固化成事实记录，
 *       谁想「省掉 snapshot 直接发 TaskMetrics」都会被这个用例拦住。</li>
 * </ol>
 */
class MetricsEventShapeTest {

    /** 用严格配置：形状契约不该依赖谁怎么配 mapper。 */
    private final ObjectMapper mapper = new ObjectMapper();

    private static TaskMetrics sampleMetrics() {
        return new TaskMetrics(
                3,        // filesTotal
                3,        // compilePassed（通过率应为 1.0）
                15,       // testsTotal
                12,       // testsPassed（通过率应为 0.8）
                0.96d,    // coverage
                4,        // llmCalls
                12_345L,  // promptTokens
                6_789L,   // completionTokens
                0.0312d,  // totalCost
                2,        // verifyAttempts（>1 → retried=true）
                189_741L  // durationMs
        );
    }

    private static Set<String> propertyNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    @DisplayName("事件载荷与快照 DTO 的属性名集合完全相同（顺序无关）")
    void eventPayloadAndSnapshotDtoHaveIdenticalShape() throws Exception {
        Set<String> fromEvent = propertyNames(mapper.readTree(
                mapper.writeValueAsString(TaskMetricsSnapshot.of(sampleMetrics()))));
        Set<String> fromDto = propertyNames(mapper.readTree(
                mapper.writeValueAsString(toMetricsView(sampleMetrics()))));

        assertEquals(new TreeSet<>(fromDto), new TreeSet<>(fromEvent),
                "SSE 的 task_metrics 载荷与 REST 的 MetricsView 必须同形状；"
                        + "差集意味着前端会投影出与快照不一致的指标");
    }

    @Test
    @DisplayName("回归：直接序列化 TaskMetrics 会丢掉三个派生字段")
    void rawStorageShapeLacksDerivedFields() throws Exception {
        JsonNode raw = mapper.readTree(mapper.writeValueAsString(sampleMetrics()));

        assertFalse(raw.has("compilePassRate"), "compilePassRate() 是派生方法，不在 record 组件里");
        assertFalse(raw.has("testPassRate"), "testPassRate() 同上");
        assertFalse(raw.has("retried"), "retried() 同上");
        assertTrue(raw.has("coverage"), "coverage 是 record 组件，所以它一直正常显示 —— 这正是当初的误导点");

        // 换成传输形状后就齐了
        JsonNode snapshot = mapper.readTree(mapper.writeValueAsString(TaskMetricsSnapshot.of(sampleMetrics())));
        assertTrue(snapshot.has("compilePassRate"));
        assertTrue(snapshot.has("testPassRate"));
        assertTrue(snapshot.has("retried"));
        assertEquals(raw.size() + 3, snapshot.size(),
                "传输形状恰好比存储形状多 3 个派生字段");
    }

    @Test
    @DisplayName("派生值由 snapshot 算一次，且与存储形状的算法一致")
    void derivedValuesMatchTheRecordComputation() {
        TaskMetrics metrics = sampleMetrics();
        TaskMetricsSnapshot snapshot = TaskMetricsSnapshot.of(metrics);

        assertEquals(metrics.compilePassRate(), snapshot.compilePassRate(), 1e-12);
        assertEquals(metrics.testPassRate(), snapshot.testPassRate(), 1e-12);
        assertEquals(metrics.retried(), snapshot.retried());
        assertEquals(1.0d, snapshot.compilePassRate(), 1e-12);
        assertEquals(0.8d, snapshot.testPassRate(), 1e-12);

        // 原始计数一并带过去 —— 前端要靠它区分「0%」和「没有样本」
        assertEquals(metrics.filesTotal(), snapshot.filesTotal());
        assertEquals(metrics.testsTotal(), snapshot.testsTotal());
        assertEquals(metrics.coverage(), snapshot.coverage(), 1e-12);
    }

    @Test
    @DisplayName("分母为 0 时通过率是 0 而计数也是 0，前端据此显示「无样本」而不是 0%")
    void zeroDenominatorIsDistinguishable() {
        TaskMetrics noFiles = new TaskMetrics(0, 0, 0, 0, -1.0d, 0, 0L, 0L, 0d, 1, 0L);
        TaskMetricsSnapshot snapshot = TaskMetricsSnapshot.of(noFiles);

        assertEquals(0d, snapshot.compilePassRate(), 1e-12);
        assertEquals(0, snapshot.filesTotal());
        assertEquals(-1.0d, snapshot.coverage(), 1e-12);
        assertFalse(snapshot.retried(), "只跑了一轮不算回退");
    }

    @Test
    @DisplayName("形状是固定的：严格 mapper 能原样往返")
    void snapshotRoundTripsStrictly() throws Exception {
        TaskMetricsSnapshot snapshot = TaskMetricsSnapshot.of(sampleMetrics());

        TaskMetricsSnapshot restored =
                mapper.readValue(mapper.writeValueAsString(snapshot), TaskMetricsSnapshot.class);

        assertEquals(snapshot, restored);
        assertNotEquals(snapshot.testsPassed(), restored.testsTotal(), "12 通过 / 15 总数，不该被搞混");
    }

    /** 手工构造一份 web 层视图，字段顺序与 {@code TaskViewMapper.parseMetrics} 完全一致。 */
    private static TaskView.MetricsView toMetricsView(TaskMetrics m) {
        return new TaskView.MetricsView(
                m.filesTotal(),
                m.compilePassed(),
                m.compilePassRate(),
                m.testsTotal(),
                m.testsPassed(),
                m.testPassRate(),
                m.coverage(),
                m.llmCalls(),
                m.promptTokens(),
                m.completionTokens(),
                m.totalCost(),
                m.verifyAttempts(),
                m.retried(),
                m.durationMs());
    }
}
