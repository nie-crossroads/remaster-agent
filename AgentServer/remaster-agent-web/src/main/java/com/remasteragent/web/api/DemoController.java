package com.remasteragent.web.api;

import com.remasteragent.common.domain.TaskStatus;

import java.util.List;
import java.util.Map;
import com.remasteragent.core.progress.ProgressPublisher;
import com.remasteragent.core.queue.TaskQueue;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.core.trace.TracePropagation;
import com.remasteragent.web.api.dto.TaskView;
import com.remasteragent.web.config.DemoProperties;
import com.remasteragent.web.sse.SseEventHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 演示模式端点。
 *
 * <h2>核心约束：绝不信任客户端传入的工程路径</h2>
 * <p>请求体只接收一个样本 {@code key}，{@code projectRoot}/{@code entryFile} 完全由服务端配置
 * （{@link DemoProperties}）解析。这是演示端点的安全边界：游客只能从服务端提供的样本里挑一个跑，
 * 无法指定任意工程。
 *
 * <h2>演示任务怎么被识别</h2>
 * <p>创建时打 {@code demo=true} 标记（{@code migration_task.demo} 列）。它走与普通任务
 * <b>完全相同</b>的编排与沙箱，只在两处被区别对待：沙箱清理对它有更短保留期；
 * 并发护栏只数演示任务。标记本身不改变任何调度语义。
 *
 * <h2>并发护栏</h2>
 * <p>{@code mvn test} 很重，一个面试官的跑批不该饿死其他人。这里只数「正在运行的非终态演示任务」
 * （{@link TaskStore#countActiveDemoTasks()}），达到上限直接返回 429，而不是排队。
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    /** 演示样本统一升到 21（与项目目标 JDK 一致）。 */
    private static final int DEMO_TARGET_JDK = 21;

    private final TaskStore taskStore;
    private final TaskQueue taskQueue;
    private final TaskQueryService queryService;
    private final SseEventHub sseEventHub;
    private final ProgressPublisher progressPublisher;
    private final DemoProperties demoProperties;

    @Autowired
    public DemoController(TaskStore taskStore, TaskQueue taskQueue,
                          TaskQueryService queryService, SseEventHub sseEventHub,
                          ProgressPublisher progressPublisher, DemoProperties demoProperties) {
        this.taskStore = taskStore;
        this.taskQueue = taskQueue;
        this.queryService = queryService;
        this.sseEventHub = sseEventHub;
        this.progressPublisher = progressPublisher;
        this.demoProperties = demoProperties;
    }

    /**
     * 演示模式开关状态（免登录）。
     *
     * <p>落地页据此决定是否显示「运行示例工程」CTA、以及是否需要先登录。
     * 刻意免登录：否则未登录用户连「有没有演示」都探不出来，落地页只能无条件显示按钮，
     * 点了才被告知不可用。
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "enabled", demoProperties.enabled(),
                // 决策点 5：演示需要登录共享 demo 账号（见 docs/DEMO_LANDING_ARCH_PLAN.md §6）
                "requiresLogin", true);
    }

    /**
     * 可选样本列表（需登录）。
     *
     * <p>工作台的「新建任务」表单据此渲染下拉框 —— 前端不该自己硬编码工程路径：
     * 路径是本机绝对路径、随部署不同，只有服务端知道真实值。
     *
     * <p>刻意放在鉴权后面（而非像 {@code /status} 那样公开）：样本路径是服务器本地文件系统信息，
     * 没必要泄露给未登录的访客。
     */
    @GetMapping("/samples")
    public List<SampleView> samples() {
        return demoProperties.samples().stream()
                .map(s -> new SampleView(s.key(), s.name(), s.projectRoot(), s.entryFile(), DEMO_TARGET_JDK))
                .toList();
    }

    /**
     * 以 DEMO 角色创建一个跑本地可信样本的任务。
     *
     * <p>返回 {@code 202 Accepted}（语义同 {@code POST /api/tasks}）：请求只是「接受了任务」，
     * 不是「任务已完成」。前端拿到 {@code TaskView} 后跳转到既有详情视图，复用同样的 SSE 进度与 DAG 渲染。
     *
     * @param body 可选。{@code {"sample":"legacy-demo"}}；省略或 sample 为空则用配置里的第一个样本
     */
    @PostMapping("/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskView run(@RequestBody(required = false) RunRequest body) {
        if (!demoProperties.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "演示模式未开启");
        }

        // 并发护栏：只数正在运行的演示任务，避免一个面试官的跑批饿死其他人
        if (taskStore.countActiveDemoTasks() >= demoProperties.concurrency()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "演示任务并发已满（上限 " + demoProperties.concurrency() + "），请稍后再试");
        }

        if (demoProperties.samples().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "演示样本未配置（remaster.demo.samples），请联系管理员开启演示前先配置样本");
        }

        // 样本路径由配置提供，绝不信任客户端传入 —— 客户端只能给 key
        String requestedKey = body == null ? null : body.sample();
        DemoProperties.Sample sample = demoProperties.sampleByKey(requestedKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "未知的演示样本: " + requestedKey + "（可选: "
                                + demoProperties.samples().stream().map(DemoProperties.Sample::key).toList() + "）"));

        ProjectPathValidator.ResolvedInput input = ProjectPathValidator.validate(
                sample.projectRoot(), sample.entryFile());

        String taskName = "演示：" + (sample.name() == null || sample.name().isBlank() ? sample.key() : sample.name());
        long taskId = taskStore.createTask(input.projectRoot().toString(), input.entryFile(),
                DEMO_TARGET_JDK, taskName, true);

        try {
            taskQueue.enqueue(taskId, TracePropagation.currentTraceparent());
        } catch (Exception e) {
            // 投递失败时显式置 FAILED：绝不留一条已入库却没人执行的悬空 PENDING（同 TaskController 的约定）
            taskStore.updateTaskStatus(taskId, TaskStatus.FAILED, "演示任务投递失败（队列不可用）: " + e.getMessage());
            log.error("演示任务 #{} 投递队列失败，已标记为 FAILED: 样本={}", taskId, input.projectRoot(), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "演示任务已创建（id=" + taskId + "）但投递到队列失败，请确认 Redis 可用后重试", e);
        }

        log.info("演示任务 #{} 已创建并投递: 样本={}({}) 目标={} 入口={}", taskId, sample.key(), input.projectRoot(),
                DEMO_TARGET_JDK, input.entryFile() == null ? "整仓升级" : input.entryFile());
        return queryService.taskSummary(taskId);
    }

    /** /api/demo/run 的请求体。只有 key 是客户端可控的。 */
    public record RunRequest(String sample) {
    }

    /** 返回给前端的样本视图（含目标 JDK，省得前端再硬编码一个 21）。 */
    public record SampleView(String key, String name, String projectRoot, String entryFile, int targetJdk) {
    }
}
