package com.remasteragent.web.api;

import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.web.api.dto.TaskDetailView;
import com.remasteragent.web.api.dto.TaskView;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 任务查询 —— REST 接口与 SSE 快照共用同一套读取逻辑。
 *
 * <p>抽出来的直接收益是「快照」和「GET 详情」永远返回同一个结构。
 * 如果各写一份，最典型的后果是：前端按接口 A 的字段解析快照，但字段名在接口 B 里改了，
 * 于是页面初始能显示、一刷新就空白 —— 而这两处代码在仓库里隔得很远，很难联想到一起。
 */
@Service
public class TaskQueryService {

    private final TaskStore taskStore;
    private final TaskViewMapper viewMapper;

    public TaskQueryService(TaskStore taskStore, TaskViewMapper viewMapper) {
        this.taskStore = taskStore;
        this.viewMapper = viewMapper;
    }

    /** 最近的任务列表，按创建时间倒序。 */
    public List<TaskView> recentTasks(int limit) {
        int effective = limit <= 0 ? 50 : Math.min(limit, 200);
        return taskStore.findRecentTasks(effective).stream().map(viewMapper::toView).toList();
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
                taskStore.findOpenGate(taskId).orElse(null));
    }

    /** 任务概要，不存在时抛 404。 */
    public TaskView taskSummary(long taskId) {
        MigrationTask task = taskStore.findTask(taskId)
                .orElseThrow(() -> new NotFoundException("任务不存在: " + taskId));
        return viewMapper.toView(task);
    }
}
