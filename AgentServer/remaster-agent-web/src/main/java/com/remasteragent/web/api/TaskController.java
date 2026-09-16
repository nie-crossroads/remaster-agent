package com.remasteragent.web.api;

import com.remasteragent.common.domain.GateStatus;
import com.remasteragent.common.domain.HumanGate;
import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.core.progress.ProgressEvent;
import com.remasteragent.core.progress.ProgressPublisher;
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
    private final ProgressPublisher progressPublisher;

    public TaskController(TaskStore taskStore, TaskQueue taskQueue,
                          TaskQueryService queryService, SseEventHub sseEventHub,
                          ProgressPublisher progressPublisher) {
        this.taskStore = taskStore;
        this.taskQueue = taskQueue;
        this.queryService = queryService;
        this.sseEventHub = sseEventHub;
        this.progressPublisher = progressPublisher;
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
        enqueueOrFail(taskId, input);
        log.info("任务 #{} 已创建并投递: 工程={} 目标文件={} JDK={}",
                taskId, input.projectRoot(), input.entryFile(), targetJdk);

        return queryService.taskSummary(taskId);
    }

    /**
     * 投递任务；失败时把任务显式标记为 FAILED 再抛出 —— <b>绝不留悬空的 PENDING</b>。
     *
     * <p>「先落库拿 id、再投队列」这两步跨了 PostgreSQL 和 Redis，没有共同事务可包。
     * 所以投递失败时必然留下一条已经入库、却没人会执行的任务行。静默留着它的后果很隐蔽：
     * 它在列表里显示为「排队中」，用户会一直等；而实际上队列里根本没有这条消息，
     * 等多久都不会有反应 —— 这是「看起来正常运行」的脏数据，比直接报错难查得多。
     *
     * <p>标记成 FAILED 并带上失败原因，是让这条记录自己说明白发生了什么：
     * 事后翻列表能一眼看出「它没被投递出去」，而不是靠人去猜为什么不动。
     */
    private void enqueueOrFail(long taskId, ProjectPathValidator.ResolvedInput input) {
        try {
            taskQueue.enqueue(taskId);
        } catch (Exception e) {
            String reason = "任务投递失败（队列不可用）: " + e.getMessage();
            taskStore.updateTaskStatus(taskId, TaskStatus.FAILED, reason);
            log.error("任务 #{} 投递队列失败，已标记为 FAILED: 工程={} 目标文件={}",
                    taskId, input.projectRoot(), input.entryFile(), e);
            throw new QueueUnavailableException(
                    "任务已创建（id=" + taskId + "）但投递到队列失败，已标记为 FAILED。"
                            + "请确认 Redis 可用后重新提交。原因: " + e.getMessage(), e);
        }
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

    // ------------------------------------------------------------------
    // 规划评审（阶段 2）
    // ------------------------------------------------------------------

    /**
     * 批准迁移计划，任务重新入队继续执行。
     *
     * <p><b>为什么批准要走接口、而不是前端直接改状态</b>：批准必须伴随一次「重新入队」，
     * 而队列投递这件事只在服务端做得了。两者必须原子地发生在一起 ——
     * 只改状态会导致任务永远挂着没人执行，只投队列会导致 Worker 起来时状态还写着
     * WAITING_HUMAN，前端看到的状态是错的。
     *
     * <p>状态回落到 {@code PENDING} 而不是直接置 RUNNING：置 RUNNING 是在撒谎 ——
     * 此刻并没有任何 Worker 在跑它，它只是刚排上队。等 Worker 真正取走时会自己置 RUNNING。
     * 状态机里每个值都该对应「实际上正在发生的事」，这是排查问题时唯一能信的线索。
     */
    @PostMapping("/{id}/plan/approve")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskView approvePlan(@PathVariable long id) {
        requireAwaitingReview(id, "批准");
        taskStore.approvePlan(id);
        requeue(id, "迁移计划已批准，等待 Worker 继续执行");

        // 重新投递后立刻推一条状态，让评审界面的按钮马上变成「已批准」，
        // 而不是等 Worker 取走任务才动 —— 中间可能隔着几秒的轮询间隔
        progressPublisher.publish(ProgressEvent.taskStatus(
                id, TaskStatus.PENDING.name(), "迁移计划已批准，已重新排队"));
        log.info("任务 #{} 的迁移计划已批准并重新入队", id);

        return queryService.taskSummary(id);
    }

    /**
     * 驳回迁移计划：任务直接判失败，不执行任何改写。
     *
     * <p>驳回<b>不清理</b>已铺好的 REWRITE / VERIFY 节点。它们会一直是 PENDING，
     * 这反而是有价值的现场：将来回看这条任务，能看见「当时计划要改这些文件，被人拦下了」。
     * 把现场抹掉只会让「为什么这个任务没做任何事」变得无从追查。
     */
    @PostMapping("/{id}/plan/reject")
    public TaskView rejectPlan(@PathVariable long id,
                               @RequestBody(required = false) RejectPlanRequest request) {
        requireAwaitingReview(id, "驳回");
        String reason = request == null || request.reason() == null || request.reason().isBlank()
                ? null
                : request.reason().trim();
        String failReason = reason == null ? "迁移计划被人工驳回" : "迁移计划被人工驳回: " + reason;

        taskStore.updateTaskStatus(id, TaskStatus.FAILED, failReason);
        progressPublisher.publish(ProgressEvent.taskStatus(id, TaskStatus.FAILED.name(), failReason));
        log.info("任务 #{} 的迁移计划被驳回: {}", id, failReason);

        return queryService.taskSummary(id);
    }

    /**
     * 只有「正在等待评审」的任务才能被批准/驳回。
     *
     * <p>这道闸门拦下的不只是误操作，更是<b>重复批准导致的重复入队</b>：
     * 批准动作里含一次投递，连点两次就会让同一个任务被两个 Worker 同时执行，
     * 而它们写的是同一份沙箱工作目录 —— 落盘互相覆盖，且这种损坏不会有任何报错。
     */
    private void requireAwaitingReview(long taskId, String action) {
        MigrationTask task = taskStore.findTask(taskId)
                .orElseThrow(() -> new NotFoundException("任务不存在: " + taskId));
        if (task.status() != TaskStatus.WAITING_HUMAN) {
            throw new ConflictException("任务 #" + taskId + " 当前状态是 " + task.status()
                    + "，不处于等待规划评审，无法" + action
                    + "（只有 WAITING_HUMAN 状态可以；任务可能未开启评审，或已被处理过）");
        }
        // WAITING_HUMAN 现在可能来自两种原因：规划评审（本组接口）或通用 GATE 门禁。
        // 用「有没有等待中的门禁行」区分，指向正确的接口，避免点错按钮后任务纹丝不动
        if (taskStore.findOpenGate(taskId).isPresent()) {
            throw new ConflictException("任务 #" + taskId
                    + " 当前等待的是一道人工门禁（GATE），请改用 "
                    + "POST /api/tasks/" + taskId + "/gate/approve（或 /gate/reject）");
        }
    }

    // ------------------------------------------------------------------
    // 通用人工门禁（阶段 3 —— GATE 节点）
    // ------------------------------------------------------------------

    /**
     * 批准当前等待中的人工门禁，任务重新入队继续执行。
     *
     * <p>与规划评审同构：批准必须<b>原子地</b>包含「落定门禁 + 重新入队」两件事 ——
     * 只落定不投队列，任务会永远挂着没人执行；只投队列不落定，Worker 起来时门还是 PENDING，
     * 会立刻又被 {@code pauseForGateIfNeeded} 挂回去。两者缺一不可，所以都在服务端一个动作里完成。
     */
    @PostMapping("/{id}/gate/approve")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskView approveGate(@PathVariable long id,
                                @RequestBody(required = false) DecideGateRequest request) {
        HumanGate gate = requireOpenGate(id, "批准");
        String reviewer = text(request == null ? null : request.reviewer());
        String comment = text(request == null ? null : request.comment());

        if (taskStore.decideGate(gate.id(), GateStatus.APPROVED, reviewer, comment) == 0) {
            throw new ConflictException("门禁 gate #" + gate.id() + " 已被处理过，无法重复批准");
        }
        // 门禁节点必须转成功：下游 VERIFY 依赖它，节点不转 SUCCEEDED 就永远等不到依赖
        taskStore.markNodeSucceeded(gate.nodeId(), null);
        requeue(id, "人工门禁已批准，等待 Worker 继续执行");

        progressPublisher.publish(ProgressEvent.taskStatus(
                id, TaskStatus.PENDING.name(), "人工门禁已批准，已重新排队"));
        log.info("任务 #{} 的门禁 gate #{} 已批准（reviewer={}）", id, gate.id(), reviewer);

        return queryService.taskSummary(id);
    }

    /**
     * 驳回当前等待中的人工门禁：对应节点判失败，任务直接判失败，不再往下执行。
     *
     * <p>驳回<b>不清理</b>已铺好的下游节点（VERIFY 等会一直 PENDING）：那是有价值的现场 ——
     * 回看时能看见「当时这道门拦住了，任务没继续」。抹掉现场只会让「为什么没做」无从追查。
     */
    @PostMapping("/{id}/gate/reject")
    public TaskView rejectGate(@PathVariable long id,
                               @RequestBody(required = false) DecideGateRequest request) {
        HumanGate gate = requireOpenGate(id, "驳回");
        String reviewer = text(request == null ? null : request.reviewer());
        String comment = text(request == null ? null : request.comment());

        if (taskStore.decideGate(gate.id(), GateStatus.REJECTED, reviewer, comment) == 0) {
            throw new ConflictException("门禁 gate #" + gate.id() + " 已被处理过，无法重复驳回");
        }
        String failReason = comment == null ? "人工门禁被驳回" : "人工门禁被驳回: " + comment;
        taskStore.markNodeFailed(gate.nodeId(), failReason, null);
        taskStore.updateTaskStatus(id, TaskStatus.FAILED, failReason);
        progressPublisher.publish(ProgressEvent.taskStatus(id, TaskStatus.FAILED.name(), failReason));
        log.info("任务 #{} 的门禁 gate #{} 被驳回: {}", id, gate.id(), failReason);

        return queryService.taskSummary(id);
    }

    /**
     * 只有「正在等待一道门禁」的任务才能被批准/驳回。
     *
     * <p>两层校验缺一不可：任务状态是 WAITING_HUMAN（大方向对），且库里确实有 PENDING 的门禁行
     * （精确）。只看状态会把「规划评审」误判成本门禁；只看门禁行则在门已批准、任务还没被 Worker
     * 取走的那一瞬间会误放行。两道一起看，才能保证「批准的一定是此刻真正挡路的那道门」。
     */
    private HumanGate requireOpenGate(long taskId, String action) {
        MigrationTask task = taskStore.findTask(taskId)
                .orElseThrow(() -> new NotFoundException("任务不存在: " + taskId));
        if (task.status() != TaskStatus.WAITING_HUMAN) {
            throw new ConflictException("任务 #" + taskId + " 当前状态是 " + task.status()
                    + "，没有等待中的人工门禁，无法" + action
                    + "（只有 WAITING_HUMAN 且存在 PENDING 门禁时才可以）");
        }
        return taskStore.findOpenGate(taskId)
                .orElseThrow(() -> new ConflictException("任务 #" + taskId
                        + " 处于 WAITING_HUMAN，但未找到等待中的门禁 —— 若它是在等规划评审，"
                        + "请改用 POST /api/tasks/" + taskId + "/plan/approve"));
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 门禁审批请求体：审批人与意见，都可选。 */
    public record DecideGateRequest(String reviewer, String comment) {
    }

    private void requeue(long taskId, String message) {
        taskStore.updateTaskStatus(taskId, TaskStatus.PENDING, null);
        taskQueue.enqueue(taskId);
        log.info("任务 #{} 已重新入队: {}", taskId, message);
    }

    /** 驳回计划时的可选理由 —— 会写进任务的失败原因，方便日后回看「当时为什么不同意」。 */
    public record RejectPlanRequest(String reason) {
    }
}
