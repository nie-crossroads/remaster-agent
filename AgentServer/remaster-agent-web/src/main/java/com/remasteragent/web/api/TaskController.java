package com.remasteragent.web.api;

import com.remasteragent.core.queue.TaskQueue;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.web.api.dto.CreateTaskRequest;
import com.remasteragent.web.api.dto.TaskDetailView;
import com.remasteragent.web.api.dto.TaskView;
import com.remasteragent.web.sse.SseEventHub;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 迁移任务的 REST + SSE 接口。
 *
 * <h2>职责边界</h2>
 * <p>这个 Controller <b>只写状态、只投队列，绝不执行迁移</b>。
 * 创建任务的耗时被压到「一次 INSERT + 一次 XADD」，与后面几十分钟的迁移完全解耦 ——
 * 这正是拆出 Worker 进程的意义所在。若在这里直接调用调度器，一个 HTTP 请求会挂住几十分钟，
 * 任何超时设置都救不了。
 *
 * <h2>输入校验去哪了</h2>
 * <p>路径相关的校验（目录穿越、必须含 pom.xml、目标必须是 .java）都在
 * {@link ProjectPathValidator} —— 那是安全边界，必须能被单独测。这里只负责编排：
 * 校验 → 落库 → 投队列 → 返回当前状态。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    private final TaskStore taskStore;
    private final TaskQueue taskQueue;
    private final TaskQueryService queryService;
    private final SseEventHub sseEventHub;

    public TaskController(TaskStore taskStore, TaskQueue taskQueue,
                          TaskQueryService queryService, SseEventHub sseEventHub) {
        this.taskStore = taskStore;
        this.taskQueue = taskQueue;
        this.queryService = queryService;
        this.sseEventHub = sseEventHub;
    }

    /**
     * 创建迁移任务并投递到队列。
     *
     * <p>返回 {@code 202 Accepted} 而不是 {@code 200}：请求的语义是「接受这个任务」，
     * 不是「任务已经完成」。用 200 会让调用方误以为可以立刻读结果。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskView create(@Valid @RequestBody CreateTaskRequest request) {
        ProjectPathValidator.ResolvedInput input =
                ProjectPathValidator.validate(request.projectRoot(), request.entryFile());
        int targetJdk = request.targetJdkOrDefault();

        long taskId = taskStore.createTask(input.projectRoot().toString(), input.entryFile(), targetJdk);
        taskQueue.enqueue(taskId);
        log.info("任务 #{} 已创建并投递: 工程={} 目标文件={} JDK={}",
                taskId, input.projectRoot(), input.entryFile(), targetJdk);

        return queryService.taskSummary(taskId);
    }

    /** 任务列表，按创建时间倒序。 */
    @GetMapping
    public List<TaskView> list(@RequestParam(defaultValue = "50") int limit) {
        return queryService.recentTasks(limit);
    }

    /** 任务详情：概要 + DAG 节点 + 补丁 + 成本。 */
    @GetMapping("/{id}")
    public TaskDetailView detail(@PathVariable long id) {
        return queryService.taskDetail(id);
    }

    /**
     * 订阅任务进度（SSE）。
     *
     * <p>连接建立后立刻收到一条 {@code snapshot} 事件（任务的完整当前状态），
     * 之后是若干 {@code progress} 事件。之所以一定要有快照，是因为进度事件走的是
     * 发完即忘的 Pub/Sub —— 只靠增量的话，连接建立前发生的事就永远补不回来了。
     */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable long id) {
        // 先确认任务存在：否则会为拼错的任务 id 挂一条永远不会有事件的连接，
        // 前端只能靠超时才发现，而错误信息完全没有指向性
        queryService.taskSummary(id);
        return sseEventHub.open(id);
    }
}
