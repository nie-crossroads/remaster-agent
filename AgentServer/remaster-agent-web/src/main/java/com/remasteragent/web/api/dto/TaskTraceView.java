package com.remasteragent.web.api.dto;

import com.remasteragent.common.domain.TraceSpan;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 任务的链路视图 —— 前端「链路耗时」面板的数据形状。**按运行分组**。
 *
 * <h2>为什么不能直接把 {@link TraceSpan} 丢给前端</h2>
 * <p>两处差距，都不是格式偏好：
 * <ol>
 *   <li><b>层级要算出来。</b> {@code parent_span_id} 是一个指针，前端拿到它还得自己建索引、
 *       判环、排缩进。这层「父子 → 缩进层级」的翻译是展示语义，放在服务端做一次，
 *       前端与将来的报表就用同一套口径。</li>
 *   <li><b>派生字段要有唯一出处。</b> {@code shortName}、{@code depth}、每次运行的耗时这些
 *       如果让前端各自算，第一个和第二个消费方迟早会算出不一样的结果 ——
 *       本项目在 {@code TaskMetrics} 上已经踩过一次「同一语义两个来源」的坑。</li>
 * </ol>
 *
 * <h2>为什么必须按运行分组，而不是拼成一条时间轴</h2>
 * <p>初次执行是一条 trace；之后每次「批准规划 / 批准门禁 / 重跑」都会重新入队，
 * 那是一次<b>新的执行</b>、由一次新的 HTTP 请求触发，因此自成一条 trace。
 * 平铺成一条时间轴会同时坏掉两件事：
 * <ul>
 *   <li><b>时间轴被空档撑爆。</b> 取消到重跑之间可能隔几小时（人吃饭去了），
 *       共用一根轴时这次运行的 77 秒只占 0.09% 宽，被最小宽度兜底压成一条细线 ——
 *       界面上看起来就是「进度条是空的」。每组各自以本组起点为原点，比例才有意义。</li>
 *   <li><b>顶层的「总耗时」只能撒谎。</b> 一个字段要同时回答「这次跑了多久」和
 *       「第一次点提交到现在多久」，答案必然对不上。</li>
 * </ul>
 * <p>所以这里删掉了早先的顶层 {@code traceId} / {@code totalDurationMs}（它们取的是
 * 最早那条 span 的 trace、以及跨全部运行的墙钟，前者答非所问，后者毫无意义），
 * 改为把每次运行摊开成 {@link TraceRun}。
 *
 * @param traceCount 这个任务一共被跑了几次（= {@code runs} 的大小）
 * @param runs       按组开始时间<b>升序</b>，即最后一次执行在末尾（前端倒序渲染「最新在上」）
 */
public record TaskTraceView(
        int traceCount,
        List<TraceRun> runs
) {

    /**
     * 从一个任务的**全部** span 分组出「每次运行」。
     *
     * <p>入参不做 traceId 过滤是刻意的：{@code trace_span} 按 task 查出来的本来就是
     * 这个任务跑过的所有次数，分组是展示层的翻译，不该在存储层丢掉信息。
     */
    public static TaskTraceView of(List<TraceSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return new TaskTraceView(0, List.of());
        }

        // LinkedHashMap：保持 span 首次出现的顺序，而入参已按 start_at 排序，
        // 因此分组结果的天然顺序就是「按开始时间」，与 span 相邻性也一致。
        Map<String, List<TraceSpan>> grouped = new LinkedHashMap<>();
        for (TraceSpan span : spans) {
            grouped.computeIfAbsent(keyOf(span), key -> new ArrayList<>()).add(span);
        }

        List<TraceRun> runs = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<TraceSpan>> entry : grouped.entrySet()) {
            runs.add(toRun(entry.getKey(), entry.getValue()));
        }
        // 显式排序而不是依赖插入顺序：入参顺序由 SQL 的 ORDER BY 决定，
        // 换个仓储实现（或内存实现）就不保证了 —— 组顺序是接口契约的一部分。
        runs.sort(Comparator.comparing(TraceRun::startedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return new TaskTraceView(runs.size(), List.copyOf(runs));
    }

    /**
     * 累计运行时长 = 各次运行的墙钟之和（不含排队与人工等待）。
     *
     * <p><b>为什么复用 {@link #of(List)} 而不是另写一遍分组求和</b>：
     * 这个数字要回答的是「机器一共干了多久」，而它唯一的凭据就是面板上那几组条形。
     * 两处各写一份分组逻辑，迟早会出现「面板上三组加起来 78 秒、任务列表却说 65 秒」——
     * 本项目在 {@code TaskMetrics} 上已经为「同一语义两个来源」付过一次代价
     * （派生字段在一次序列化里全丢，指标静默归零）。
     *
     * <p>空输入返回 0 而不是 -1：没有 span 就是没跑过，0 秒是实话。
     * 「压根没有链路数据」与「跑了 0 秒」由调用方用 null 区分（见 {@code MetricsView#runDurationMs}）。
     */
    public static long totalRunDurationMs(List<TraceSpan> spans) {
        long total = 0L;
        for (TraceRun run : of(spans).runs()) {
            total += run.durationMs();
        }
        return total;
    }

    private static String keyOf(TraceSpan span) {
        return span.traceId() == null ? "" : span.traceId();
    }

    /**
     * 一次运行 = 一条 trace 的全部 span + 组级的元信息。
     *
     * <p>组级耗时用 {@code 最早开始 → 最晚结束}，不用「各 span 耗时之和」——
     * 后者会把嵌套（node 里套 llm，llm 里套 sandbox）重复计算好几遍。
     */
    private static TraceRun toRun(String traceId, List<TraceSpan> spans) {
        Map<String, TraceSpan> bySpanId = new LinkedHashMap<>();
        for (TraceSpan span : spans) {
            if (span.spanId() != null) {
                bySpanId.put(span.spanId(), span);
            }
        }

        List<SpanView> views = new ArrayList<>(spans.size());
        for (TraceSpan span : spans) {
            views.add(new SpanView(
                    span.id(),
                    span.traceId(),
                    span.spanId(),
                    span.parentSpanId(),
                    span.nodeId(),
                    span.name(),
                    span.shortName(),
                    span.status(),
                    span.startAt(),
                    span.durationMs() == null ? 0L : span.durationMs(),
                    depthOf(span, bySpanId)));
        }

        Instant startedAt = earliestStart(spans);
        Instant latestEnd = latestEnd(spans);
        boolean allEnded = latestEnd != null;
        return new TraceRun(
                traceId,
                startedAt,
                allEnded ? latestEnd : null,
                millisBetween(startedAt, latestEnd),
                views.size(),
                rootNameOf(views),
                List.copyOf(views));
    }

    /** 组内最早的开始时刻。span 的 start_at 在库里是 NOT NULL，这里仍防一手 null。 */
    private static Instant earliestStart(List<TraceSpan> spans) {
        Instant earliest = null;
        for (TraceSpan span : spans) {
            Instant start = span.startAt();
            if (start != null && (earliest == null || start.isBefore(earliest))) {
                earliest = start;
            }
        }
        return earliest;
    }

    /**
     * 组内最晚的结束时刻；**组内只要有一个 span 没有结束时间就返回 null**。
     *
     * <p>两种结束时刻的来源：
     * <ul>
     *   <li>常规的 {@code end_at} —— 权威值。</li>
     *   <li>没有 {@code end_at} 时（进程被杀那类），退回 {@code start_at + duration_ms}：
     *       这颗 span 的结束时间无从得知，但它已经跑的时长是知道的，
     *       用它兜住跨度，免得「有 span 在跑」被画成「整组瞬间结束」。</li>
     * </ul>
     * <p>返回 null 是给调用方一个诚实的信号：这一组还没跑完，别拿一个假的结束时刻
     * 去回答「这次一共花了多久」。
     */
    private static Instant latestEnd(List<TraceSpan> spans) {
        Instant latest = null;
        boolean allEnded = true;
        for (TraceSpan span : spans) {
            Instant end = span.endAt();
            if (end == null) {
                allEnded = false;
                Instant start = span.startAt();
                if (start == null || span.durationMs() == null) {
                    continue;
                }
                end = start.plusMillis(Math.max(0L, span.durationMs()));
            }
            if (latest == null || end.isAfter(latest)) {
                latest = end;
            }
        }
        return allEnded ? latest : null;
    }

    private static long millisBetween(Instant from, Instant to) {
        if (from == null || to == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(from, to).toMillis());
    }

    /**
     * 这组运行的可读标签 = 根 span 的名字（如 {@code task} / {@code api:POST /api/tasks}）。
     *
     * <p>用**完整名**而不是 {@code shortName}：短名是为「别把时间轴挤爆」而压过的
     * （{@code api:POST /api/tasks} 会被压成 {@code api: tasks}，丢掉 HTTP 方法），
     * 而组标题独占一行、不占时间轴宽度，没有压缩的必要。
     *
     * <p>库里没有「这次运行是谁触发的」这一列，但根 span 的名字已经回答了它 ——
     * 与其新加一列去记「运行类型」，不如把已经在链路里的信息读出来。
     * 多根（脏数据）时取最早的那条。
     */
    private static String rootNameOf(List<SpanView> views) {
        for (SpanView view : views) {
            if (view.depth() != 0) {
                continue;
            }
            if (view.name() != null && !view.name().isBlank()) {
                return view.name();
            }
            if (view.shortName() != null && !view.shortName().isBlank()) {
                return view.shortName();
            }
        }
        return null;
    }

    /**
     * 计算缩进层级：沿父指针往上数，数不到头就停。
     *
     * <p><b>按组计算</b>：父指针只在同一条 trace 内成立。把整批 span 放进同一张表里查
     * 虽然大多也能算对（span_id 是全局唯一的），但一条 trace 的层级没有任何理由
     * 依赖另一条 trace 的数据，范围收窄到组内更不容易出错，也更省内存。
     *
     * <p>三处提前退出都不是多余的：
     * <ul>
     *   <li><b>父不在集合里</b>（跨任务的脏数据、被裁剪过的 span）→ 按根处理。
     *       这里必须 break 而不是抛异常：一次脏写不该让整个链路面板打不开。</li>
     *   <li><b>父指针成环</b>（手工 INSERT、导入外部数据都能造出来）→ 靠 visited 集合挡住。
     *       {@code trace_span} 是一张普通的表，而这里一旦死循环，
     *       整个详情接口就会挂住 —— 一个「点开链路面板把服务点死」的缺陷，值几行保护代码。</li>
     *   <li><b>层级硬上限</b> → 兜住前面两道都没拦住的极端情形。</li>
     * </ul>
     */
    private static int depthOf(TraceSpan span, Map<String, TraceSpan> bySpanId) {
        if (span.spanId() == null) {
            return 0;
        }
        int depth = 0;
        Set<String> visited = new HashSet<>();
        visited.add(span.spanId());

        String parentId = span.parentSpanId();
        while (parentId != null && !parentId.isBlank() && depth < MAX_DEPTH) {
            if (!visited.add(parentId)) {
                break;
            }
            TraceSpan parent = bySpanId.get(parentId);
            if (parent == null) {
                break;
            }
            depth++;
            parentId = parent.parentSpanId();
        }
        return depth;
    }

    /** 缩进层级的硬上限 —— 只为兜住脏数据造出的环，正常链路不过三四层。 */
    private static final int MAX_DEPTH = 16;

    /**
     * 一次运行（一条 trace）。
     *
     * @param traceId    这条 trace 的 id
     * @param startedAt  组内最早的开始时刻；组内没有任何合法时间时为 null
     * @param endedAt    组内最晚结束时刻；**有 span 没结束就是 null**（没跑完，别装成跑完了）
     * @param durationMs 本组墙钟跨度；`endedAt != null` 时恒等于 `endedAt - startedAt`
     * @param spanCount  本组 span 数
     * @param rootName   根 span 的完整名字，用作这组运行的可读标签（组标题独占一行，不必压缩）
     * @param spans      组内 span，按开始时间升序，已带上缩进层级
     */
    public record TraceRun(
            String traceId,
            Instant startedAt,
            Instant endedAt,
            long durationMs,
            int spanCount,
            String rootName,
            List<SpanView> spans
    ) {
    }

    /**
     * 单个 span 的展示形状。
     *
     * @param depth     缩进层级（0 = 根），由父指针推出
     * @param shortName 压缩后的短名（如 {@code node: Bar.java}），避免长键把时间轴挤爆
     * @param durationMs 耗时；未结束的 span 为 0
     */
    public record SpanView(
            Long id,
            String traceId,
            String spanId,
            String parentSpanId,
            Long nodeId,
            String name,
            String shortName,
            String status,
            Instant startedAt,
            long durationMs,
            int depth
    ) {
    }
}
