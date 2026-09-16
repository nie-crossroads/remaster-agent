package com.remasteragent.web.api;

import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.TraceSpan;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.web.api.dto.TaskDetailView;
import com.remasteragent.web.api.dto.TaskTraceView;
import com.remasteragent.web.api.dto.TaskView;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 任务查询 —— REST 接口与 SSE 快照共用同一套读取逻辑。
 *
 * <p>抽出来的直接收益是「快照」和「GET 详情」永远返回同一个结构。
 * 如果各写一份，最典型的后果是：前端按接口 A 的字段解析快照，但字段名在接口 B 里改了，
 * 于是页面初始能显示、一刷新就空白 —— 而这两处代码在仓库里隔得很远，很难联想到一起。
 *
 * <p>本类也是**唯一**把「累计运行时长」喂给视图的地方：它必须与链路面板用同一份分组算法
 * （{@link TaskTraceView#totalRunDurationMs}），否则界面上会出现「面板三组加起来 78 秒、
 * 列表却说 65 秒」这种没人能解释的差异。
 */
@Service
public class TaskQueryService {

    private final TaskStore taskStore;
    private final TaskViewMapper viewMapper;

    public TaskQueryService(TaskStore taskStore, TaskViewMapper viewMapper) {
        this.taskStore = taskStore;
        this.viewMapper = viewMapper;
    }

    /**
     * 最近的任务列表，按创建时间倒序。
     *
     * <p>span 走**一次**批量查询（不是逐个任务调一遍）：列表最多 200 条，
     * 逐条查会让一次列表请求变成 201 条 SQL。
     */
    public List<TaskView> recentTasks(int limit) {
        int effective = limit <= 0 ? 50 : Math.min(limit, 200);
        List<MigrationTask> tasks = taskStore.findRecentTasks(effective);
        Map<Long, List<TraceSpan>> spansByTask =
                taskStore.findTraceSpansByTasks(tasks.stream().map(MigrationTask::id).toList());
        return tasks.stream()
                .map(task -> viewMapper.toView(task, runDurationMs(spansByTask.get(task.id()))))
                .toList();
    }

    /**
     * 任务详情：概要 + 节点 + 补丁 + 成本 + 迁移计划。
     *
     * @throws NotFoundException 任务不存在
     */
    public TaskDetailView taskDetail(long taskId) {
        MigrationTask task = taskStore.findTask(taskId)
                .orElseThrow(() -> new NotFoundException("任务不存在: " + taskId));
        return viewMapper.toDetail(
                task,
                taskStore.findNodes(taskId),
                taskStore.findPatches(taskId),
                taskStore.summarizeCost(taskId),
                taskStore.isPlanApproved(taskId),
                // 当前等待中的门禁（没有则为 null）—— 评审卡片只在它非空时出现
                taskStore.findOpenGate(taskId).orElse(null),
                runDurationMs(taskStore.findTraceSpans(taskId)));
    }

    /** 任务概要，不存在时抛 404。 */
    public TaskView taskSummary(long taskId) {
        MigrationTask task = taskStore.findTask(taskId)
                .orElseThrow(() -> new NotFoundException("任务不存在: " + taskId));
        return viewMapper.toView(task, runDurationMs(taskStore.findTraceSpans(taskId)));
    }

    /**
     * 累计运行时长；**没有 span 时返回 null**（= 不知道），不是 0。
     *
     * <p>这个区分是必须的：埋点是在阶段 3 才接上的，此前的任务一条 span 都没有。
     * 若把「查不到」当成 0，界面会理直气壮地写出「运行 0 秒（端到端 1m 57s）」，
     * 而真相是「我们不知道它跑了多久」。
     */
    private static Long runDurationMs(List<TraceSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return null;
        }
        return TaskTraceView.totalRunDurationMs(spans);
    }
}
