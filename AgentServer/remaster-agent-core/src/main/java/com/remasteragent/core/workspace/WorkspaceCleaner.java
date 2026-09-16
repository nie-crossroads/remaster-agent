package com.remasteragent.core.workspace;

import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.core.config.CoreProperties;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.tools.sandbox.WorkspacePreparer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 沙箱目录回收 —— 任务到达终态并超过保留期后，删掉它的 {@code task-<id>} 目录。
 *
 * <h2>为什么需要它</h2>
 * <p>每个任务都会在沙箱根下留一份「被测工程副本 + Maven 产物」。{@code WorkspacePreparer}
 * 只对<b>同一个</b>任务清空重建（那保证了「同一基线」这个正确性前提），但<b>跨任务从不过问</b>：
 * 跑 N 个任务就是 N 个目录，只增不减。本机实测攒到过 33 个 —— 单个才 300K、总共 6.4M，
 * 占的不是空间而是<b>注意力</b>：排查问题时满屏 {@code task-*}，分不清哪个是活的。
 *
 * <h2>为什么是「按终态 + 保留期」而不是「任务一结束就删」</h2>
 * <p>任务刚跑完那一刻恰恰是最需要现场的时候 —— 人要开副本看改写结果、对比 diff、定位失败原因。
 * 立刻删掉等于把「可复现」这个卖点自己拆了。所以留一个窗口期（默认 24h）：
 * <b>先给人看，再回收</b>。
 *
 * <h2>四条判据（宁可漏删，不可误删）</h2>
 * <ol>
 *   <li><b>只碰</b>严格匹配 {@code task-<数字>} 的目录 —— 名字对不上的一律不动，
 *       沙箱根下万一有别人的东西，这个类没有资格删。</li>
 *   <li><b>非终态一律保留</b>：{@code PENDING / RUNNING / WAITING_HUMAN} 的任务随时可能继续跑，
 *       删掉等于把正在进行的任务连根拔掉。人工门禁挂起的任务尤其危险 —— 它在等人，看起来「不动」，
 *       但绝不是「废弃」。</li>
 *   <li><b>终态任务</b>按 {@code migration_task.updated_at}（终态写入那一刻）判断是否超期，
 *       而不是目录的 mtime —— 后者会被无关的 touch 干扰。</li>
 *   <li><b>库中查不到的孤儿目录</b>（例如数据库被重置过、目录还在）按目录 mtime 判断。这类残骸
 *       没有任何任务认领它，留着只会让人误以为「任务还在」。</li>
 * </ol>
 *
 * <h2>为什么挂在消费循环上，而不做成 {@code @Scheduled}</h2>
 * <p>沙箱的创建者是「执行任务的那个进程」—— 双进程部署时是 Worker，内嵌模式（{@code
 * remaster.worker.embedded=true}）时是 API。{@code TaskConsumer} 恰好就是这两个场景下
 * <b>各自唯一存在</b>的那份实例，把它当作定时触发点，回收天然只有一个执行者：
 * 换成 {@code @Scheduled} 则两个进程都会扫，需要额外引入分布式锁来去重 ——
 * 为一个「删过期目录」的功能上锁，不值。
 *
 * <p>这也解释了为什么本类虽然由 Spring 装配，核心逻辑却是可纯单测的普通对象：
 * 构造器接受 {@link Clock}，单测用可推进的假时钟验证「到期才删」，无需 sleep。
 */
@Component
public class WorkspaceCleaner {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceCleaner.class);

    /** 沙箱目录命名约定：{@code task-<任务id>}。不匹配的一律不碰。 */
    private static final Pattern TASK_DIR = Pattern.compile("task-(\\d+)");

    /**
     * 两次扫描的最小间隔。
     *
     * <p>消费循环本身是秒级空转（阻塞读 {@code block-millis}），若每轮都去列目录、查库，
     * 就是拿「每 5 秒一次全目录扫描」换一个 30 分钟粒度的事件 —— 明显不划算。
     * 这里写成常量而不是配置项：它不影响任何对外语义，没有暴露给使用者的必要。
     */
    public static final Duration DEFAULT_SWEEP_INTERVAL = Duration.ofMinutes(30);

    private final Path workspaceRoot;
    private final Duration retention;
    private final boolean enabled;
    private final Duration interval;
    private final TaskStore taskStore;
    private final Clock clock;

    /** 上次扫描时刻。{@code null} 表示本次进程还没扫过 —— 首次调用立即扫一次。 */
    private Instant lastSweep;

    @Autowired
    public WorkspaceCleaner(CoreProperties properties, TaskStore taskStore) {
        this(properties.workspaceRootPath(), properties.workspaceRetention(),
                properties.workspaceCleanupEnabled(), DEFAULT_SWEEP_INTERVAL,
                taskStore, Clock.systemUTC());
    }

    /** 全参构造：单测的接缝（可注入假时钟与极短间隔）。 */
    public WorkspaceCleaner(Path workspaceRoot, Duration retention, boolean enabled, Duration interval,
                            TaskStore taskStore, Clock clock) {
        this.workspaceRoot = workspaceRoot;
        this.retention = retention;
        this.enabled = enabled;
        this.interval = interval;
        this.taskStore = taskStore;
        this.clock = clock;
    }

    /**
     * 由消费循环每轮调用：距上次扫描不足 {@link #DEFAULT_SWEEP_INTERVAL} 时立即返回。
     *
     * <p>本方法<b>永不抛异常</b>。它挂在消费循环的路径上，一旦抛出去就会被循环的兜底 catch
     * 当成「这一轮消费失败」—— 明明是清理垃圾出了问题，却会连累任务执行。回收是尽力而为的
     * 后台动作，失败只该留下一条日志。
     */
    public void sweepIfDue() {
        if (!enabled) {
            return;
        }
        Instant now = clock.instant();
        if (lastSweep != null && now.isBefore(lastSweep.plus(interval))) {
            return;
        }
        lastSweep = now;
        CleanupReport report = sweep(now);
        // 只要扫到了目录就记一条 INFO —— 这是「回收器还活着、它看到了什么」的唯一信号。
        // 只保留、不删除的那一轮同样要记：否则「沙箱一直没被删」就无法区分是
        // 「判据生效了」还是「这东西根本没跑」。全空才降到 DEBUG，那时确实没什么可说。
        if (report.scanned() > 0 || report.failed() > 0) {
            log.info("沙箱回收完成: {}", report);
        } else {
            log.debug("沙箱回收完成: {}", report);
        }
    }

    /**
     * 立即扫描一次，<b>不受开关与间隔约束</b>（供单测与手工触发使用）。
     *
     * <p>开关只作用于 {@link #sweepIfDue()}：这个方法表达的是「现在，就扫」这个明确意图，
     * 再叠一层「是否允许」会让调用方拿到一个静默什么都不做的空操作。
     */
    public CleanupReport sweep() {
        return sweep(clock.instant());
    }

    private CleanupReport sweep(Instant now) {
        if (!Files.isDirectory(workspaceRoot)) {
            log.debug("沙箱根目录不存在或不是目录，跳过回收: {}", workspaceRoot);
            return new CleanupReport(0, 0, 0, 0);
        }

        List<Path> children;
        try (Stream<Path> stream = Files.list(workspaceRoot)) {
            children = stream.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            log.warn("列出沙箱根目录失败，本轮跳过回收: {}", workspaceRoot, e);
            return new CleanupReport(0, 0, 0, 0);
        }

        Instant cutoff = now.minus(retention);
        int scanned = 0;
        int deleted = 0;
        int kept = 0;
        int failed = 0;
        List<String> failureDetail = new ArrayList<>();

        for (Path dir : children) {
            // 符号链接不跟：可能指到沙箱根之外，递归删会跑到别人的地盘上
            if (Files.isSymbolicLink(dir)) {
                continue;
            }
            Matcher matcher = TASK_DIR.matcher(dir.getFileName().toString());
            if (!matcher.matches()) {
                continue;
            }
            scanned++;

            boolean shouldDelete;
            try {
                shouldDelete = shouldDelete(Long.parseLong(matcher.group(1)), dir, cutoff);
            } catch (Exception e) {
                // 查库失败时保守处理：这一轮当作「不删」。删不掉只是多留一份目录，
                // 误删却是不可逆的 —— 两者代价不对称，所以往保守那边倒。
                log.warn("判断沙箱目录是否该回收时出错，本轮保留: {}", dir, e);
                kept++;
                continue;
            }

            if (!shouldDelete) {
                kept++;
                continue;
            }
            if (deletePermanently(dir)) {
                deleted++;
            } else {
                failed++;
                failureDetail.add(dir.getFileName().toString());
            }
        }

        CleanupReport report = new CleanupReport(scanned, deleted, kept, failed);
        if (!failureDetail.isEmpty()) {
            log.warn("以下沙箱目录删除失败（将在下一轮重试）: {}", String.join(", ", failureDetail));
        }
        return report;
    }

    /** 单个目录是否该回收。见类注释里的四条判据。 */
    private boolean shouldDelete(long taskId, Path dir, Instant cutoff) {
        Optional<MigrationTask> task = taskStore.findTask(taskId);

        if (task.isPresent()) {
            MigrationTask it = task.get();
            if (!isTerminal(it.status())) {
                // 运行中 / 等待人工 —— 随时可能继续写这份目录，碰不得
                return false;
            }
            Instant finishedAt = it.updatedAt() != null ? it.updatedAt() : lastModified(dir);
            return finishedAt != null && finishedAt.isBefore(cutoff);
        }

        // 库里没有这条任务：孤儿目录（数据库重置过、任务被清过）。
        // 没有任何任务会再认领它，按目录自身时间判。
        Instant modified = lastModified(dir);
        return modified != null && modified.isBefore(cutoff);
    }

    /**
     * 永久删除。
     *
     * <p>刻意<b>不进回收站</b>：沙箱是源工程的一次性投影，源工程与补丁都在别处（源仓库只读、
     * 补丁落 {@code patch} 表），随时可重建。搬进回收站只是把「删一遍」变成「删两遍」，
     * 还要人再确认一次，纯属多出来的动作。
     */
    private boolean deletePermanently(Path dir) {
        try {
            WorkspacePreparer.deleteRecursively(dir);
            log.info("已永久删除过期沙箱目录: {}", dir);
            return true;
        } catch (IOException e) {
            log.warn("删除沙箱目录失败: {}", dir, e);
            return false;
        }
    }

    /**
     * 是否已到终态。
     *
     * <p>{@code CANCELLED} 必须算进去：取消之后这个任务的沙箱<b>不会再被写</b>了，
     * 它和被判失败的任务没有区别。漏掉它会表现成「取消掉的任务，沙箱永远不被回收」——
     * 而且不会有任何报错，只是磁盘里慢慢多出一批永远不会消失的目录。
     */
    private static boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.SUCCEEDED
                || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELLED;
    }

    private static Instant lastModified(Path dir) {
        try {
            return Files.getLastModifiedTime(dir).toInstant();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 一轮扫描的账。
     *
     * @param scanned 认出的沙箱目录数（名字不匹配的不计入）
     * @param deleted 本次永久删除的目录数
     * @param kept    因未到期或非终态而保留的目录数
     * @param failed  删除失败、下一轮会重试的目录数
     */
    public record CleanupReport(int scanned, int deleted, int kept, int failed) {

        @Override
        public String toString() {
            return "扫描 " + scanned + " 个 / 删除 " + deleted + " 个 / 保留 " + kept + " 个 / 失败 " + failed + " 个";
        }
    }
}
