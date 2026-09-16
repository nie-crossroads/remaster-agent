package com.remasteragent.web.api.dto;

import com.remasteragent.common.domain.TraceSpan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 链路视图的单测 —— 钉住「形状由服务端算一次」这件事。
 *
 * <h2>为什么层级不从时间戳推</h2>
 * <p>并行节点的 span 在时间上会互相交叠（比如两个文件同时改写），
 * 用时间戳套嵌套关系必然算错，而算错的表现是「缩进看起来不太对」——
 * 没人会为此报警，但报表上「这次任务的时间花在哪一层」就成了假的。
 * 父指针是唯一的真相，本类把这条钉死。
 *
 * <h2>为什么要有环保护</h2>
 * <p>{@code trace_span} 是一张普通的表，任何一次脏写（手工 INSERT、导入外部数据）
 * 都可能造出环。没有保护的话，一次「点开链路面板」就会把详情接口挂死 ——
 * 一个用户可触发的死循环，值几行保护代码。
 *
 * <h2>为什么「一组一次运行」必须有测试守着</h2>
 * <p>任务被取消后重跑，「取消那次」与「重跑那次」相隔可能是几小时。早先这些 span 被
 * 平铺在同一根时间轴上，于是每次真正干活的耗时被压成 0.6% 的细线 ——
 * 界面上看起来就是「链路里好多条的进度是空的」。分组是这条缺陷的修复，
 * 也是本类里 {@code runDurationDoesNotSpanAcrossRuns} 要长期守住的形状。
 */
class TaskTraceViewTest {

    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";
    private static final String TRACE_SUBMIT = "25213939fe58f79022a34e7ef87f9ca0";
    private static final String TRACE_RERUN = "2cf386fc4d92a1b2c3d4e5f60718293a";

    @Test
    @DisplayName("空列表：返回空视图而不是 null，前端不必为『老任务没有埋点』写特判")
    void emptySpansYieldEmptyView() {
        TaskTraceView view = TaskTraceView.of(List.of());

        assertEquals(0, view.traceCount());
        assertTrue(view.runs().isEmpty());
    }

    @Test
    @DisplayName("层级从父指针推出：task → node → llm 三层，缩进 0/1/2")
    void depthFollowsParentPointers() {
        Instant t0 = Instant.parse("2026-09-14T10:00:00Z");
        List<TraceSpan> spans = List.of(
                span(1L, "a", null, "task", t0, 5_000L),
                span(2L, "b", "a", "node:rewrite:src/Demo.java", t0.plusMillis(10), 4_000L),
                span(3L, "c", "b", "llm:REWRITE", t0.plusMillis(20), 3_000L));

        TaskTraceView view = TaskTraceView.of(spans);

        assertEquals(1, view.traceCount());
        assertEquals(TRACE, view.runs().get(0).traceId());
        assertEquals(List.of(0, 1, 2), view.runs().get(0).spans().stream()
                .map(TaskTraceView.SpanView::depth).toList());
    }

    @Test
    @DisplayName("同一层多兄弟：各自 depth=1，互不影响（并行的两个文件改写）")
    void siblingsShareDepth() {
        Instant t0 = Instant.parse("2026-09-14T10:00:00Z");
        List<TraceSpan> spans = List.of(
                span(1L, "a", null, "task", t0, 5_000L),
                span(2L, "b", "a", "node:rewrite:A.java", t0.plusMillis(10), 3_000L),
                span(3L, "c", "a", "node:rewrite:B.java", t0.plusMillis(20), 3_000L));

        TaskTraceView view = TaskTraceView.of(spans);

        assertEquals(List.of(0, 1, 1), view.runs().get(0).spans().stream()
                .map(TaskTraceView.SpanView::depth).toList());
    }

    @Test
    @DisplayName("父不在返回的集合里（跨任务的脏数据）：按根处理，绝不 NPE")
    void orphanSpanIsTreatedAsRoot() {
        Instant t0 = Instant.parse("2026-09-14T10:00:00Z");
        TaskTraceView view = TaskTraceView.of(List.of(
                span(1L, "b", "不存在的父", "node:verify:A.java", t0, 1_000L)));

        assertEquals(0, view.runs().get(0).spans().get(0).depth());
    }

    @Test
    @DisplayName("脏数据造出环：不死循环，停在硬上限")
    void cyclicParentsTerminate() {
        Instant t0 = Instant.parse("2026-09-14T10:00:00Z");
        // a 的父是 b，b 的父是 a —— 库里两次错写就能造出来
        List<TraceSpan> spans = List.of(
                span(1L, "a", "b", "node:x", t0, 100L),
                span(2L, "b", "a", "node:y", t0.plusMillis(1), 100L));

        TaskTraceView view = TaskTraceView.of(spans);

        List<TaskTraceView.SpanView> views = view.runs().get(0).spans();
        assertEquals(2, views.size(), "必须返回而不是挂住 —— 这是用户可触发的死循环");
        views.forEach(span -> assertTrue(span.depth() <= 16));
    }

    @Test
    @DisplayName("组耗时是墙钟（最早开始 → 最晚结束），不是各 span 耗时之和")
    void runDurationIsWallClockNotSum() {
        Instant t0 = Instant.parse("2026-09-14T10:00:00Z");
        // 嵌套：5s 的任务里套着 4s 的节点，套着 3s 的调用。加起来 12s，墙钟只有 5s。
        List<TraceSpan> spans = List.of(
                span(1L, "a", null, "task", t0, 5_000L),
                span(2L, "b", "a", "node:rewrite:A.java", t0.plusMillis(500), 4_000L),
                span(3L, "c", "b", "llm:REWRITE", t0.plusMillis(1_000), 3_000L));

        assertEquals(5_000L, TaskTraceView.of(spans).runs().get(0).durationMs(),
                "把嵌套耗时相加会重复计算，得到 12s 这种没有意义的数字");
    }

    @Test
    @DisplayName("未结束的 span：耗时记 0 而不是 null，前端不必为它写分支")
    void unfinishedSpanReportsZeroDuration() {
        Instant t0 = Instant.parse("2026-09-14T10:00:00Z");
        TaskTraceView view = TaskTraceView.of(List.of(
                new TraceSpan(1L, TRACE, "a", null, 7L, null, "task", "INTERNAL", "UNSET",
                        t0, null, null, null)));

        TaskTraceView.TraceRun run = view.runs().get(0);
        assertEquals(0L, run.spans().get(0).durationMs());
        assertEquals(0L, run.durationMs(), "没有结束时间就撑不出墙钟跨度");
        assertNull(run.endedAt(), "没跑完的运行不该编一个结束时刻出来");
    }

    @Test
    @DisplayName("短名压缩：长节点键保留类型与文件名，避免把时间轴挤爆")
    void shortNameKeepsTypeAndFileName() {
        assertEquals("node: Demo.java", TaskTraceView.of(List.of(
                span(1L, "a", null, "node:rewrite:src/main/java/com/example/Demo.java",
                        Instant.now(), 1L))).runs().get(0).spans().get(0).shortName());
        assertEquals("task", TaskTraceView.of(List.of(
                span(1L, "a", null, "task", Instant.now(), 1L))).runs().get(0).spans().get(0).shortName());
    }

    // ------------------------------------------------------------------
    // 以下五条守着「按运行分组」这个形状
    // ------------------------------------------------------------------

    @Test
    @DisplayName("按 traceId 分组：任务 #10 的真实形状（建单 1 + 取消 1 + 重跑 8）")
    void multipleRunsAreGroupedByTraceId() {
        Instant submit = Instant.parse("2026-09-16T02:49:24Z");
        Instant cancelled = Instant.parse("2026-09-16T02:54:04Z");
        Instant rerun = Instant.parse("2026-09-16T05:21:54Z");

        List<TraceSpan> spans = List.of(
                span(1L, TRACE_SUBMIT, "s1", null, "api:POST /api/tasks", submit, 208L),
                span(2L, TRACE, "c1", null, "task", cancelled, 649L),
                span(3L, TRACE_RERUN, "r1", null, "task", rerun, 77_025L),
                span(4L, TRACE_RERUN, "r2", "r1", "node:analyze", rerun.plusMillis(100), 7_331L),
                span(5L, TRACE_RERUN, "r3", "r1", "node:plan", rerun.plusMillis(8_000), 5_602L),
                span(6L, TRACE_RERUN, "r4", "r3", "llm:PLAN", rerun.plusMillis(8_100), 5_214L),
                span(7L, TRACE_RERUN, "r5", "r1", "node:rewrite:src/main/java/com/example/legacy/LegacySalesReport.java",
                        rerun.plusMillis(14_000), 53_967L),
                span(8L, TRACE_RERUN, "r6", "r5", "llm:REWRITE", rerun.plusMillis(14_100), 53_241L),
                span(9L, TRACE_RERUN, "r7", "r1", "node:verify:src/main/java/com/example/legacy/LegacySalesReport.java",
                        rerun.plusMillis(68_000), 5_636L),
                span(10L, TRACE_RERUN, "r8", "r7", "sandbox:mvn", rerun.plusMillis(68_100), 5_425L));

        TaskTraceView view = TaskTraceView.of(spans);

        assertEquals(3, view.traceCount(), "三次执行就是三组，不能拍平成一条链路");
        assertEquals(List.of(1, 1, 8), view.runs().stream()
                .map(TaskTraceView.TraceRun::spanCount).toList());
        assertEquals(List.of(TRACE_SUBMIT, TRACE, TRACE_RERUN), view.runs().stream()
                .map(TaskTraceView.TraceRun::traceId).toList(), "按开始时间升序，最新一次在末尾");
    }

    @Test
    @DisplayName("跨运行的间隔不进任何一组的时间轴 —— 否则每次运行的进度条都会被压成细线")
    void runDurationDoesNotSpanAcrossRuns() {
        Instant cancelled = Instant.parse("2026-09-16T02:54:04Z");
        Instant rerun = Instant.parse("2026-09-16T05:21:54Z");   // 2.5 小时后的重跑

        TaskTraceView view = TaskTraceView.of(List.of(
                span(1L, TRACE, "a", null, "task", cancelled, 649L),
                span(2L, TRACE_RERUN, "b", null, "task", rerun, 77_025L)));

        assertEquals(649L, view.runs().get(0).durationMs());
        assertEquals(77_025L, view.runs().get(1).durationMs());
        assertTrue(view.runs().get(1).durationMs() < 120_000L,
                "回归：这里曾经返回跨两次运行的 2h33m（9227179ms），"
                        + "把重跑那 77 秒压成画布 0.09% 宽，看起来就是「进度条是空的」");
    }

    @Test
    @DisplayName("只跑了一个 span 的运行（建单那次 HTTP 请求）也自成一组")
    void singleSpanRunIsItsOwnGroup() {
        Instant t0 = Instant.parse("2026-09-16T02:49:24Z");

        TaskTraceView view = TaskTraceView.of(List.of(
                span(1L, TRACE_SUBMIT, "s1", null, "api:POST /api/tasks", t0, 208L)));

        TaskTraceView.TraceRun run = view.runs().get(0);
        assertEquals(1, view.traceCount());
        assertEquals(1, run.spanCount());
        assertEquals("api:POST /api/tasks", run.rootName(),
                "组标签用完整名 —— 短名会把这串压成 `api: tasks`，丢掉 HTTP 方法");
        assertEquals(208L, run.durationMs());
    }

    @Test
    @DisplayName("父指针只在同一条 trace 内成立：指向别的运行的父当孤儿处理")
    void depthIsComputedWithinRun() {
        Instant t0 = Instant.parse("2026-09-16T02:49:24Z");

        TaskTraceView view = TaskTraceView.of(List.of(
                span(1L, TRACE, "a", null, "task", t0, 5_000L),
                // "z" 声称自己的父是 "a"，但 "a" 属于另一条 trace —— 不能跨运行认爹
                span(2L, TRACE_RERUN, "z", "a", "node:rewrite:A.java", t0.plusSeconds(3_600), 1_000L)));

        assertEquals(0, runOf(view, TRACE).spans().get(0).depth());
        assertEquals(0, runOf(view, TRACE_RERUN).spans().get(0).depth());
    }

    @Test
    @DisplayName("组元信息齐备：起止、耗时、span 数、根名；endedAt 与 durationMs 必须对得上")
    void runMetadataIsConsistent() {
        Instant t0 = Instant.parse("2026-09-16T05:21:54Z");

        TaskTraceView view = TaskTraceView.of(List.of(
                span(1L, TRACE, "a", null, "task", t0, 5_000L),
                span(2L, TRACE, "b", "a", "node:rewrite:src/main/java/com/example/Demo.java",
                        t0.plusMillis(100), 4_000L)));

        TaskTraceView.TraceRun run = view.runs().get(0);
        assertNotNull(run.startedAt());
        assertEquals(t0, run.startedAt());
        assertEquals(t0.plusMillis(5_000), run.endedAt());
        assertEquals(5_000L, run.durationMs());
        assertEquals(2, run.spanCount());
        assertEquals("task", run.rootName());
        assertEquals(run.endedAt(), run.startedAt().plusMillis(run.durationMs()),
                "endedAt 与 durationMs 是同一件事的两种写法，任何一边单独改都会让它们打架");
    }

    @Test
    @DisplayName("累计运行时长 = 各组墙钟之和，且不吃掉运行之间的空档")
    void totalRunDurationSumsRunsAndDropsIdleGaps() {
        // 任务 #10 的真实形状：02:49 建单 → 02:54 那次被取消 → 05:21 重跑成功。
        // 「机器干了多久」只能是三段之和，「你等了多久」才是首尾墙钟。
        Instant submit = Instant.parse("2026-09-16T02:49:24Z");
        Instant cancelled = Instant.parse("2026-09-16T02:54:04Z");
        Instant rerun = Instant.parse("2026-09-16T05:21:54Z");

        List<TraceSpan> spans = List.of(
                span(1L, TRACE_SUBMIT, "s1", null, "api:POST /api/tasks", submit, 208L),
                span(2L, TRACE, "c1", null, "task", cancelled, 649L),
                span(3L, TRACE_RERUN, "r1", null, "task", rerun, 77_025L));

        long total = TaskTraceView.totalRunDurationMs(spans);

        assertEquals(208L + 649L + 77_025L, total);

        // 反面对照：端到端（最早开始 → 最晚结束）把 4m40s + 2h27m50s 两段空档全吃了进去。
        // 这就是「任务耗时 2h33m、机器只跑了 78 秒」的由来 —— 两个数必须分得开，
        // 更要紧的是求和路径上不能有任何一段把空档算进去（哪怕只是顺手取 min~max）。
        long wallMs = rerun.plusMillis(77_025L).toEpochMilli() - submit.toEpochMilli();
        assertTrue(total * 100 < wallMs,
                "累计运行时长必须比端到端墙钟小两个数量级；相等说明空档被算进来了");
    }

    // ------------------------------------------------------------------

    private static TaskTraceView.TraceRun runOf(TaskTraceView view, String traceId) {
        return view.runs().stream()
                .filter(run -> traceId.equals(run.traceId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("结果里没有这条 trace 的分组: " + traceId));
    }

    private static TraceSpan span(Long id, String spanId, String parentSpanId,
                                  String name, Instant startAt, long durationMs) {
        return span(id, TRACE, spanId, parentSpanId, name, startAt, durationMs);
    }

    private static TraceSpan span(Long id, String traceId, String spanId, String parentSpanId,
                                  String name, Instant startAt, long durationMs) {
        return new TraceSpan(id, traceId, spanId, parentSpanId, 7L, null,
                name, "INTERNAL", "OK", startAt, startAt.plusMillis(durationMs), durationMs, null);
    }
}
