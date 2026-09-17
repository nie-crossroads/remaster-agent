package com.remasteragent.web.api;

import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.GateStatus;
import com.remasteragent.common.domain.HumanGate;
import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.NodeStatus;
import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.common.domain.TraceSpan;
import com.remasteragent.core.progress.ProgressEvent;
import com.remasteragent.core.progress.ProgressPublisher;
import com.remasteragent.core.queue.TaskQueue;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.core.trace.TracePropagation;
import com.remasteragent.core.trace.TraceTracer;
import com.remasteragent.web.api.dto.CreateTaskRequest;
import com.remasteragent.web.api.dto.TaskDetailView;
import com.remasteragent.web.api.dto.TaskTraceView;
import com.remasteragent.web.api.dto.TaskView;
import com.remasteragent.web.sse.SseEventHub;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final TraceTracer tracer;

    /** 单测入口：不埋点。 */
    public TaskController(TaskStore taskStore, TaskQueue taskQueue,
                          TaskQueryService queryService, SseEventHub sseEventHub,
                          ProgressPublisher progressPublisher) {
        this(taskStore, taskQueue, queryService, sseEventHub, progressPublisher, TraceTracer.NOOP);
    }

    @Autowired
    public TaskController(TaskStore taskStore, TaskQueue taskQueue,
                          TaskQueryService queryService, SseEventHub sseEventHub,
                          ProgressPublisher progressPublisher, TraceTracer tracer) {
        this.taskStore = taskStore;
        this.taskQueue = taskQueue;
        this.queryService = queryService;
        this.sseEventHub = sseEventHub;
        this.progressPublisher = progressPublisher;
        this.tracer = tracer == null ? TraceTracer.NOOP : tracer;
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
        Span span = tracer.startApi("POST", "/api/tasks");
        try (Scope ignored = span.makeCurrent()) {
            ProjectPathValidator.ResolvedInput input =
                    ProjectPathValidator.validate(request.projectRoot(), request.entryFile());
            int targetJdk = request.targetJdkOrDefault();
            String name = normalizeName(request.name());

            long taskId = taskStore.createTask(input.projectRoot().toString(),
                    input.entryFile(), targetJdk, name);
            span.setAttribute("task.id", taskId);
            enqueueOrFail(taskId, input, TracePropagation.currentTraceparent());
            log.info("任务 #{} 已创建并投递: 任务名={} 工程={} 目标文件={} JDK={}",
                    taskId, name, input.projectRoot(), input.entryFile(), targetJdk);

            TaskView view = queryService.taskSummary(taskId);
            TraceTracer.endOk(span);
            return view;
        } catch (RuntimeException e) {
            TraceTracer.endException(span, e);
            throw e;
        }
    }

    /**
     * 规整任务名：去空白，空白视为「未命名」（null）。
     *
     * <p>刻意不校验必填：任务名只是展示用的标签，值不值得为一个显示字段把建单卡住？
     * 不值得 —— 真正决定任务能不能跑的是工程路径与目标文件。前端会引导填写，
     * 但服务端留出「无名字也能建」的口子，评测 harness 等调用方就不必跟着改。
     */
    private static String normalizeName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
    private void enqueueOrFail(long taskId, ProjectPathValidator.ResolvedInput input,
                               String traceparent) {
        try {
            taskQueue.enqueue(taskId, traceparent);
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
        // 把当前请求的 traceparent 一并投出去：这一次「续跑」是由人点按钮触发的，
        // 让它的 trace 根落在这个 HTTP 请求上，「谁批准的、什么时候、这次跑得怎么样」
        // 就串成了一条 —— 否则续跑的执行会自成一条无头 trace。
        taskQueue.enqueue(taskId, TracePropagation.currentTraceparent());
        log.info("任务 #{} 已重新入队: {}", taskId, message);
    }

    // ------------------------------------------------------------------
    // 任务控制（阶段 3 收尾：取消 / 重跑）与全链路 Trace
    // ------------------------------------------------------------------

    /**
     * 取消任务 —— <b>协作式</b>，不硬杀。
     *
     * <h3>为什么分两种走法</h3>
     * <p>能不能立刻停下来，取决于「此刻有没有节点在跑」：
     * <ul>
     *   <li><b>PENDING / WAITING_HUMAN</b>：没有任何节点在执行，可以直接落定 {@code CANCELLED}。
     *       等评审时取消尤其常见 —— 人看了计划觉得不对，不想让它跑，也不想去点「驳回」。</li>
     *   <li><b>RUNNING</b>：只能置标志位，由 Worker 在<b>节点边界</b>停下。
     *       一个节点内部（尤其沙箱里的 {@code mvn test}）没法安全中断：
     *       硬杀子进程会留下半截工作目录，比多跑一个节点更糟。所以这个请求返回的是
     *       「已受理」，状态仍是 RUNNING —— 那才是此刻的真实情况。</li>
     * </ul>
     *
     * <p>无论哪条路径都会先把 {@code cancel_requested} 置上：它可能在「判状态」与「落状态」
     * 之间恰好被 Worker 取走开始执行，只改状态的话那一刻就丢了这个意图。
     */
    @PostMapping("/{id}/cancel")
    public TaskView cancel(@PathVariable long id,
                           @RequestBody(required = false) CancelTaskRequest request) {
        MigrationTask task = taskStore.findTask(id)
                .orElseThrow(() -> new NotFoundException("任务不存在: " + id));
        if (isTerminal(task.status())) {
            throw new ConflictException("任务 #" + id + " 已是终态 " + task.status()
                    + "，无法取消（只有 PENDING / RUNNING / WAITING_HUMAN 可以）");
        }

        String detail = text(request == null ? null : request.reason());
        String reason = detail == null ? "任务被人工取消" : "任务被人工取消: " + detail;

        taskStore.requestCancel(id);

        if (task.status() == TaskStatus.RUNNING) {
            String message = "已请求取消，将在当前节点结束后停止";
            progressPublisher.publish(ProgressEvent.taskStatus(id, TaskStatus.RUNNING.name(), message));
            log.info("任务 #{} 已请求取消（正在执行，等 Worker 在节点边界停止）: {}", id, reason);
            return queryService.taskSummary(id);
        }

        // 没有节点在跑：可以立刻落定。顺带把等待中的门禁关掉 —— 留着它，
        // 详情页会一直挂着一张「待审批」的卡片，而那个任务已经没人会去批了。
        closeOpenGateIfAny(id, reason);
        taskStore.updateTaskStatus(id, TaskStatus.CANCELLED, reason);
        progressPublisher.publish(ProgressEvent.taskStatus(id, TaskStatus.CANCELLED.name(), reason));
        log.info("任务 #{} 已取消: {}", id, reason);

        return queryService.taskSummary(id);
    }

    /**
     * 重跑失败/取消的任务。
     *
     * <h3>为什么是「退回节点」而不是「新建任务」</h3>
     * <p>新建任务会丢掉已经攒下的 checkpoint（ANALYZE 的分析结果、已成功文件的改写产物），
     * 重跑一遍要从头烧 token。退回节点则精确复用：<b>已成功的节点不动</b>，
     * 只把失败与被跳过的部分放回起跑线。
     *
     * <h3>被取消的任务为什么也能重跑</h3>
     * <p>取消停在节点边界，此时的节点全是「已成功」或「还没跑」—— 没有失败节点可退回，
     * 但那些 PENDING 节点本身就是断点。所以这里不要求「必须重置到东西」，
     * 只要还有没跑完的节点，重新入队就能续上。
     */
    @PostMapping("/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskView retry(@PathVariable long id) {
        MigrationTask task = taskStore.findTask(id)
                .orElseThrow(() -> new NotFoundException("任务不存在: " + id));
        if (task.status() != TaskStatus.FAILED && task.status() != TaskStatus.CANCELLED) {
            throw new ConflictException("任务 #" + id + " 当前状态是 " + task.status()
                    + "，不能重跑（只有 FAILED / CANCELLED 可以；正在跑的任务请先取消）");
        }

        List<DagNode> nodes = taskStore.findNodes(id);
        int reset = taskStore.resetFailedNodes(id);
        boolean hasPending = nodes.stream().anyMatch(node -> node.status() == NodeStatus.PENDING);

        // 这里刻意【不】把「一个节点都没有」当成无从重跑。节点为空说明任务在铺开 DAG 之前就停了
        // （刚建单就被取消，或在 PLAN / ANALYZE 铺节点之前就失败）—— 这种任务重新入队后，
        // Worker 会走 bootstrapDagIfAbsent 把 DAG 重新铺一遍，是能跑的。
        // 早期版本在这里返 409，实测挡住了最自然的一条路径：建单 → 立刻取消 → 又想让跑起来。

        // 清掉取消标志：不清的话，Worker 取到任务的第一轮循环就会看到「已请求取消」并立刻停下 ——
        // 表现成「点了重跑，任务闪一下就又变成已取消」。
        taskStore.clearCancelRequest(id);

        String message;
        if (reset > 0) {
            message = "任务重跑：已把 " + reset + " 个失败/跳过节点退回待执行";
        } else if (hasPending) {
            message = "任务重跑：从断点继续未完成的节点";
        } else {
            message = "任务重跑：没有可续跑的节点，将从头铺开 DAG 再执行";
        }
        requeue(id, message);
        progressPublisher.publish(ProgressEvent.taskStatus(id, TaskStatus.PENDING.name(), message));
        log.info("任务 #{} 已重跑（重置节点 {} 个，原有节点 {} 个，待执行 {}）",
                id, reset, nodes.size(), hasPending ? "有" : "无");

        return queryService.taskSummary(id);
    }

    /**
     * 任务的全链路 Trace。
     *
     * <p>单独一个端点而不是塞进详情：一次任务可能产生几百条 span，而详情是每次刷新都在拉的东西 ——
     * 让「打开链路面板」这个动作自己去取，详情页的体积和延迟不受影响。
     *
     * <p>注意<b>一个任务可能有多条 trace</b>：初次执行一条，之后每次「批准后重新入队」又会起一条
     * （那是另一次执行，另一次触发者）。这不是缺陷而是事实的反映 —— 所以返回的是
     * <b>按 traceId 分好组的多次运行</b>（{@link TaskTraceView#runs()}），每组的甘特图各自
     * 以自己的起点为时间轴原点。
     *
     * <p>曾经这里是把全部 span 平铺成一条时间轴、再附一个跨全部运行的「总耗时」。
     * 那会让「取消 → 重跑」之间几小时的空档把每次真正的耗时压成看不见的细线，
     * 界面上看起来就是「链路里好多条的进度是空的」。分组是唯一能让比例恢复意义的做法。
     */
    @GetMapping("/{id}/trace")
    public TaskTraceView trace(@PathVariable long id) {
        // 先确认任务存在，否则拼错的 id 会安静地返回一个空列表，看起来像「这个任务没有埋点」
        queryService.taskSummary(id);
        List<TraceSpan> spans = taskStore.findTraceSpans(id);
        return TaskTraceView.of(spans);
    }

    /** 把该任务当前等待中的门禁就地关掉（人工取消时用）。没有门在等则是空操作。 */
    private void closeOpenGateIfAny(long taskId, String reason) {
        taskStore.findOpenGate(taskId).ifPresent(gate -> {
            // decideGate 的 `WHERE status='PENDING'` 保证这里不会覆盖别人刚做出的决定
            if (taskStore.decideGate(gate.id(), GateStatus.REJECTED, "system", reason) > 0) {
                taskStore.markNodeFailed(gate.nodeId(), reason, null);
            }
        });
    }

    /** 终态判定 —— 与 {@code TaskConsumer} 保持同一套口径（含 CANCELLED）。 */
    private static boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.SUCCEEDED
                || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELLED;
    }

    /** 取消任务时的可选理由 —— 会写进任务的失败原因，日后能看出「当时为什么叫停」。 */
    public record CancelTaskRequest(String reason) {
    }

    /** 驳回计划时的可选理由 —— 会写进任务的失败原因，方便日后回看「当时为什么不同意」。 */
    public record RejectPlanRequest(String reason) {
    }
}
