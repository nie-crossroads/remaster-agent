package com.remasteragent.core.gate;

import com.remasteragent.common.domain.GateStatus;
import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.core.config.CoreProperties;
import com.remasteragent.core.progress.ProgressEvent;
import com.remasteragent.core.progress.ProgressPublisher;
import com.remasteragent.core.store.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 人工门禁超时回收 —— 没人处理的门禁不会永远占着一条 PENDING。
 *
 * <h2>为什么需要它</h2>
 * <p>门禁的设计是「挂起等人」，而挂起<b>不占 Worker、不占资源</b>，所以「没人批」这件事
 * 在系统里是完全静默的：任务停在 {@code WAITING_HUMAN}，没有超时、没有告警、没有重试。
 * 一眼看去它和「正在跑」没有区别 —— 这正是危险所在。跑自动化验收或 CI 时，
 * 一个忘了批的门禁会让整条流水线安静地卡到天荒地老。
 *
 * <h2>超时为什么判失败，而不是自动放行</h2>
 * <p>放行等于让「人在回路」这道闸门悄悄失效 —— 而它存在的全部理由是
 * 「机器判断不了的，让人看一眼」。超时自动放行会把这个项目最想讲的那件事
 * （不确定的地方由人兜底）在最关键的时刻否定掉。判失败则留下明确的原因
 * （「门禁超时未处理」），事后回看时一眼就懂，也逼着人去调大超时或去批它。
 *
 * <h2>为什么默认关闭</h2>
 * <p>{@code remaster.core.gate-timeout} 默认 {@code 0} = 永不超时。门禁挂起的语义就是「等人」，
 * 而人在开会、在睡觉、在过周末。一个会自动把任务判死的默认值，坏处远大于好处。
 *
 * <h2>为什么挂消费循环而不是 {@code @Scheduled}</h2>
 * <p>与 {@code WorkspaceCleaner} 同款理由：挂起的任务不会自己回到调度器（它不在跑），
 * 所以这个扫描必须由<b>某个常驻的循环</b>来驱动；而 {@code TaskConsumer} 在双进程/内嵌
 * 两种部署形态下各自唯一存在，拿它当触发点天然只有一个执行者，不需要分布式锁。
 */
@Component
public class GateTimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(GateTimeoutSweeper.class);

    /**
     * 两次扫描的最小间隔。
     *
     * <p>必须<b>远小于</b>超时设定本身：真正的超时时刻会被推迟最多一个扫描间隔，
     * 若间隔取到和超时同量级，「超时 30 分钟」实际会变成「30 到 60 分钟之间」。
     * 取 5 分钟，对分钟级的门禁超时是合适的粒度，也不会让消费循环频繁查库。
     */
    public static final Duration DEFAULT_SWEEP_INTERVAL = Duration.ofMinutes(5);

    /** 自动处理时的审批人标识。刻意不用 null —— 那一栏会显示在门禁记录里，写清楚「不是你批的」。 */
    private static final String SYSTEM_REVIEWER = "system";

    private final Duration timeout;
    private final Duration interval;
    private final TaskStore taskStore;
    private final ProgressPublisher progressPublisher;
    private final Clock clock;

    /** 上次扫描时刻。{@code null} 表示本进程还没扫过 —— 首次调用立即扫一次。 */
    private Instant lastSweep;

    /**
     * 生产装配。
     *
     * <p>进度发布口用 {@link ObjectProvider} 取、取不到退化成 NOOP：与 {@code DagScheduler}
     * 保持同一种取法。core 刻意不依赖 Spring Web，而 {@code ProgressPublisher} 的实现来自
     * Worker 装配，单测与只跑 core 的场景里容器中可能根本没有这个 Bean。
     */
    @Autowired
    public GateTimeoutSweeper(CoreProperties properties, TaskStore taskStore,
                              ObjectProvider<ProgressPublisher> publisherProvider) {
        this(properties.gateTimeout(), DEFAULT_SWEEP_INTERVAL, taskStore,
                publisherProvider == null
                        ? ProgressPublisher.NOOP
                        : publisherProvider.getIfAvailable(() -> ProgressPublisher.NOOP),
                Clock.systemUTC());
    }

    /** 全参构造：单测的接缝（可注入假时钟与极短间隔）。 */
    public GateTimeoutSweeper(Duration timeout, Duration interval, TaskStore taskStore,
                              ProgressPublisher progressPublisher, Clock clock) {
        this.timeout = timeout;
        this.interval = interval;
        this.taskStore = taskStore;
        this.progressPublisher = progressPublisher;
        this.clock = clock;
    }

    /**
     * 由消费循环每轮调用：距上次扫描不足 {@link #DEFAULT_SWEEP_INTERVAL} 时立即返回。
     *
     * <p>本方法<b>永不抛异常</b>。它挂在消费循环的路径上，一旦抛出去就会被循环的兜底 catch
     * 当成「这一轮消费失败」—— 明明是过期门禁的问题，却会连累任务执行。
     */
    public void sweepIfDue() {
        if (!enabled()) {
            return;
        }
        Instant now = clock.instant();
        if (lastSweep != null && now.isBefore(lastSweep.plus(interval))) {
            return;
        }
        lastSweep = now;
        try {
            SweepReport report = sweep(now);
            // 扫到了才记（且只在有实质动作时用 INFO）：否则消费循环每 5 分钟一条
            // 「扫描 0 个」，会把真正有用的日志淹掉。
            if (report.expired() > 0) {
                log.warn("门禁超时处理完成: {}", report);
            } else {
                log.debug("门禁超时扫描完成: {}", report);
            }
        } catch (Exception e) {
            log.warn("门禁超时扫描失败，本轮跳过（过期门禁会在下一轮重试）", e);
        }
    }

    /** 立即扫描一次，不受间隔约束（供单测与手工触发使用）。 */
    public SweepReport sweep() {
        return sweep(clock.instant());
    }

    /**
     * 扫描并处理超期的门禁。
     *
     * <p>对每一道超期门禁做的事，与 {@code POST /gate/reject} 完全一致 —— 只是审批人写 {@code system}。
     * 刻意<b>复用同一套语义</b>而不是另起一条路径：两道门（人批的 / 超时的）如果行为不同，
     * 排查时就得多记一套规则，而这种「多出来的知识」正是长期维护中最容易失真的东西。
     */
    public SweepReport sweep(Instant now) {
        if (!enabled()) {
            return new SweepReport(0, 0);
        }

        Instant threshold = now.minus(timeout);
        List<TaskStore.OpenGateRef> overdue;
        try {
            overdue = taskStore.findOverdueGates(threshold);
        } catch (Exception e) {
            // 查库失败按「本轮不处理」：误判一个门禁超期的代价是「把等人中的任务判死」，
            // 比「晚几分钟再处理」严重得多，所以往保守那边倒。
            log.warn("查询超期门禁失败，本轮跳过", e);
            return new SweepReport(0, 0);
        }
        if (overdue.isEmpty()) {
            return new SweepReport(0, 0);
        }

        int expired = 0;
        for (TaskStore.OpenGateRef gate : overdue) {
            if (expireOne(gate, now)) {
                expired++;
            }
        }
        return new SweepReport(overdue.size(), expired);
    }

    private boolean expireOne(TaskStore.OpenGateRef gate, Instant now) {
        String reason = "人工门禁超时未处理（等待超过 " + describe(timeout) + "），已自动判失败";
        try {
            // 先落定门禁，再动节点与任务。顺序很重要：decideGate 带 `WHERE status='PENDING'`，
            // 返回 0 说明这道门刚被人批过/驳过 —— 那一刻必须【立刻放手】，
            // 否则会把一个人刚刚批准的任务覆盖成失败。
            int decided = taskStore.decideGate(gate.gateId(), GateStatus.REJECTED, SYSTEM_REVIEWER, reason);
            if (decided == 0) {
                log.info("门禁 gate #{} 在超时处理前已被人工决定，放手不覆盖（任务 #{}）",
                        gate.gateId(), gate.taskId());
                return false;
            }

            taskStore.markNodeFailed(gate.nodeId(), reason, null);
            taskStore.updateTaskStatus(gate.taskId(), TaskStatus.FAILED, reason);
            publish(gate, reason);

            log.warn("门禁 gate #{} 超时未处理，任务 #{} 判失败（节点 {}，挂起于 {}）",
                    gate.gateId(), gate.taskId(), gate.nodeKey(),
                    gate.createdAt() == null ? "?" : gate.createdAt());
            return true;
        } catch (Exception e) {
            log.warn("处理超期门禁 gate #{} 失败（任务 #{}），将在下一轮重试",
                    gate.gateId(), gate.taskId(), e);
            return false;
        }
    }

    private void publish(TaskStore.OpenGateRef gate, String reason) {
        try {
            progressPublisher.publish(ProgressEvent.taskStatus(gate.taskId(), TaskStatus.FAILED.name(), reason));
        } catch (Exception e) {
            // 与 DagScheduler.publish 同款兜底：进度是观测，不是正确性
            log.warn("发布门禁超时事件失败（任务 #{}）", gate.taskId(), e);
        }
    }

    private boolean enabled() {
        return timeout != null && !timeout.isZero() && !timeout.isNegative() && interval != null;
    }

    /** 把时长写成 30m / 24h / 90s 这种一眼能读的形式（与配置里能写的写法一致）。 */
    private static String describe(Duration duration) {
        long seconds = duration.getSeconds();
        if (seconds % 3600 == 0 && seconds >= 3600) {
            return (seconds / 3600) + "h";
        }
        if (seconds % 60 == 0 && seconds >= 60) {
            return (seconds / 60) + "m";
        }
        return seconds + "s";
    }

    /**
     * 一轮扫描的账。
     *
     * @param scanned 扫到的超期门禁数
     * @param expired 真正被判失败的门禁数（可能是 0 —— 有人在扫描与处理之间抢先批了）
     */
    public record SweepReport(int scanned, int expired) {

        @Override
        public String toString() {
            return "扫到超期门禁 " + scanned + " 道 / 实际判失败 " + expired + " 道";
        }
    }
}
