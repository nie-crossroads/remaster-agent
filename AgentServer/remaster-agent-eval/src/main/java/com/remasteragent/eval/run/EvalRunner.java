package com.remasteragent.eval.run;

import com.remasteragent.eval.api.ApiClient;
import com.remasteragent.eval.api.TaskSummary;
import com.remasteragent.eval.catalog.EvalCase;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * 逐条提交用例、轮询到终态，产出 {@link EvalRun}。
 *
 * <h2>三条刻意的取舍</h2>
 * <ol>
 *   <li><b>串行，不并发。</b>Worker 的模型调用走网关，是有速率限制的；并发只会把
 *       「限流重试」这件事放大成更多噪声，而且耗时口径会掺进资源竞争，报告反而不好解释。
 *       串行的代价只是总时长线性增长 —— 对一次性评测可以接受。</li>
 *   <li><b>轮询，不订 SSE。</b>理由见 {@link ApiClient}：harness 要能在无浏览器、
 *       无长连接的环境跑，而 SSE 是内存态、进程一重启就丢。</li>
 *   <li><b>单条失败不中断整批。</b>一条用例提交失败（比如 API 抖了）不该让另外 20 条
 *       白跑。但它的结果会被标成 {@link EvalRun.Verdict#HARNESS_ERROR}，
 *       <b>不进成功率</b> —— 把工具故障算进结论是自我欺骗。</li>
 * </ol>
 */
public final class EvalRunner {

    private final ApiClient api;
    private final Duration pollInterval;
    private final Duration taskTimeout;
    private final Consumer<String> progress;

    /**
     * @param pollInterval 轮询间隔。2 秒足够：任务最短也要几十秒，多轮询只是浪费
     * @param taskTimeout  单任务上限。取值要覆盖「3 轮回退重写 × 每轮一次全工程 mvn test」
     * @param progress     进度回调（命令行用它打日志）
     */
    public EvalRunner(ApiClient api, Duration pollInterval, Duration taskTimeout, Consumer<String> progress) {
        this.api = api;
        this.pollInterval = pollInterval;
        this.taskTimeout = taskTimeout;
        this.progress = progress;
    }

    /** 跑一条用例。任何异常都被收进结果的 {@code harnessError}，不向外抛。 */
    public EvalRun run(EvalCase evalCase, Path repoRoot) {
        long startedAtNanos = System.nanoTime();
        String projectRoot = evalCase.projectPath(repoRoot).toString();

        long taskId;
        try {
            taskId = api.createTask(projectRoot, evalCase.entryFile(), evalCase.targetJdk(), evalCase.id());
        } catch (RuntimeException e) {
            progress.accept("提交失败: " + e.getMessage());
            return EvalRun.harnessError(evalCase, elapsedMs(startedAtNanos),
                    "提交任务失败: " + e.getMessage());
        }
        progress.accept("已提交 task #" + taskId + "（工程 " + evalCase.project() + "）");

        long deadline = startedAtNanos + taskTimeout.toNanos();
        TaskSummary last = null;
        while (System.nanoTime() < deadline) {
            try {
                last = api.getTask(taskId);
            } catch (RuntimeException e) {
                progress.accept("查询 task #" + taskId + " 失败: " + e.getMessage());
                return new EvalRun(evalCase.id(), evalCase.project(), evalCase.entryFile(), evalCase.title(),
                        evalCase.patterns(), evalCase.expect(), taskId, null, null, null, null,
                        elapsedMs(startedAtNanos), "查询任务失败: " + e.getMessage(), Instant.now());
            }
            if (last.isTerminal()) {
                break;
            }
            sleepQuietly(pollInterval);
        }

        long wallClockMs = elapsedMs(startedAtNanos);
        if (last == null || !last.isTerminal()) {
            String status = last == null ? "未知" : last.status();
            progress.accept("超时（最后状态 " + status + "）");
            return new EvalRun(evalCase.id(), evalCase.project(), evalCase.entryFile(), evalCase.title(),
                    evalCase.patterns(), evalCase.expect(), taskId, status, null, null, null,
                    wallClockMs, "超过 " + taskTimeout.toMinutes() + " 分钟仍未结束（最后状态 " + status + "）",
                    Instant.now());
        }

        progress.accept("task #" + taskId + " 结束: " + last.status()
                + (last.runDurationMs() == null ? "" : "，实际运行 " + last.runDurationMs() + "ms"));

        return new EvalRun(evalCase.id(), evalCase.project(), evalCase.entryFile(), evalCase.title(),
                evalCase.patterns(), evalCase.expect(), taskId, last.status(), last.failReason(),
                last.metrics(), last.runDurationMs(), wallClockMs, null, Instant.now());
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("评测被中断", e);
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
