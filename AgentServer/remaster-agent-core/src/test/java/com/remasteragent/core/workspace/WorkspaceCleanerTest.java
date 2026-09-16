package com.remasteragent.core.workspace;

import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.core.config.CoreProperties;
import com.remasteragent.core.store.InMemoryTaskStore;
import com.remasteragent.core.workspace.WorkspaceCleaner.CleanupReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 沙箱回收的判据单测 —— 全部同步、无 sleep、无真实时间依赖。
 *
 * <p>回收是<b>不可逆</b>的动作，所以判据必须逐条钉死。这里刻意用「可推进的假时钟」而不是
 * {@code Thread.sleep} 或改目录 mtime 来模拟过期：判据本身是「任务终态时刻 + 保留期 &lt; now」，
 * 让 now 可控才是最贴近真实语义的验证方式（改 mtime 只能验证孤儿分支）。
 *
 * <p>重点覆盖的四个「不许删」：未到期的、非终态的、名字对不上的、开关关掉的。
 * 误删的代价不对称 —— 多留一份目录只是噪声，删掉一份正在用的目录是不可逆的事故。
 */
class WorkspaceCleanerTest {

    private static final Duration RETENTION = Duration.ofHours(24);
    private static final Duration SWEEP_INTERVAL = Duration.ofMinutes(30);

    @TempDir
    Path sandboxRoot;

    private final MutableClock clock = new MutableClock(Instant.now());
    private InMemoryTaskStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryTaskStore();
    }

    // ------------------------------------------------------------------
    // 终态任务：按「任务终态时刻 + 保留期」判断
    // ------------------------------------------------------------------

    @Test
    @DisplayName("终态任务超过保留期：目录被永久删除")
    void terminalTaskPastRetentionIsDeleted() throws IOException {
        long taskId = terminalTask(TaskStatus.SUCCEEDED, null);
        Path dir = sandbox(taskId);

        clock.advance(RETENTION.plusHours(1));
        CleanupReport report = cleaner().sweep();

        assertFalse(Files.exists(dir), "终态且超期的沙箱应被删除");
        assertEquals(1, report.scanned());
        assertEquals(1, report.deleted());
    }

    @Test
    @DisplayName("终态任务未满保留期：保留（这段窗口是留给人看现场的）")
    void terminalTaskWithinRetentionIsKept() throws IOException {
        long taskId = terminalTask(TaskStatus.FAILED, "编译不过");
        Path dir = sandbox(taskId);

        clock.advance(RETENTION.minusMinutes(1));
        CleanupReport report = cleaner().sweep();

        assertTrue(Files.exists(dir), "刚跑完的任务现场必须留着 —— 人要看改写结果和失败原因");
        assertEquals(1, report.kept());
        assertEquals(0, report.deleted());
    }

    @Test
    @DisplayName("运行中 / 等待人工的任务：无论过多久都不删")
    void nonTerminalTasksAreNeverDeleted() throws IOException {
        long running = terminalTask(TaskStatus.RUNNING, null);
        long waiting = terminalTask(TaskStatus.WAITING_HUMAN, null);
        long pending = terminalTask(TaskStatus.PENDING, null);
        Path runningDir = sandbox(running);
        Path waitingDir = sandbox(waiting);
        Path pendingDir = sandbox(pending);

        clock.advance(Duration.ofDays(365));
        CleanupReport report = cleaner().sweep();

        assertTrue(Files.exists(runningDir), "RUNNING 的任务随时可能继续写这份目录");
        assertTrue(Files.exists(waitingDir), "门禁挂起的任务在等人，不是废弃");
        assertTrue(Files.exists(pendingDir));
        assertEquals(3, report.kept());
        assertEquals(0, report.deleted());
    }

    // ------------------------------------------------------------------
    // 孤儿目录：库里没有这条任务，按目录自身时间判断
    // ------------------------------------------------------------------

    @Test
    @DisplayName("库里查不到的孤儿目录：按目录时间判定，过期即删")
    void orphanDirPastRetentionIsDeleted() throws IOException {
        Path orphan = sandbox(999);
        setModified(orphan, clock.instant().minus(Duration.ofDays(10)));

        CleanupReport report = cleaner().sweep();

        assertFalse(Files.exists(orphan), "数据库重置后遗留的目录没有任何任务认领它，留着只会让人误判");
        assertEquals(1, report.deleted());
    }

    @Test
    @DisplayName("库里查不到的目录但未过期：保留（可能任务行还没落库）")
    void orphanDirWithinRetentionIsKept() throws IOException {
        Path fresh = sandbox(999);
        setModified(fresh, clock.instant());

        CleanupReport report = cleaner().sweep();

        assertTrue(Files.exists(fresh));
        assertEquals(1, report.kept());
    }

    // ------------------------------------------------------------------
    // 边界：不属于自己的东西一律不碰
    // ------------------------------------------------------------------

    @Test
    @DisplayName("名字不是 task-<数字> 的目录一律不碰")
    void unrelatedDirsAreNeverTouched() throws IOException {
        Path foreign = Files.createDirectories(sandboxRoot.resolve("someone-elses-data"));
        Path malformed = Files.createDirectories(sandboxRoot.resolve("task-abc"));
        setModified(foreign, clock.instant().minus(Duration.ofDays(365)));
        setModified(malformed, clock.instant().minus(Duration.ofDays(365)));

        CleanupReport report = cleaner().sweep();

        assertTrue(Files.exists(foreign), "沙箱根下可能有别人的东西，这个类没有资格删");
        assertTrue(Files.exists(malformed));
        assertEquals(0, report.scanned(), "名字对不上的一律不计入统计，也不删除");
    }

    @Test
    @DisplayName("沙箱根不存在：安静返回全零，不抛异常")
    void missingRootIsHarmless() {
        WorkspaceCleaner cleaner = new WorkspaceCleaner(sandboxRoot.resolve("not-created"),
                RETENTION, true, SWEEP_INTERVAL, store, clock);

        assertEquals(new CleanupReport(0, 0, 0, 0), cleaner.sweep());
    }

    // ------------------------------------------------------------------
    // 节流与开关
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sweepIfDue 按间隔节流：首次立即扫，未满间隔不重复扫")
    void sweepIfDueThrottlesByInterval() throws IOException {
        long first = terminalTask(TaskStatus.SUCCEEDED, null);
        Path firstDir = sandbox(first);
        clock.advance(RETENTION.plusHours(1));

        WorkspaceCleaner cleaner = cleaner();
        cleaner.sweepIfDue();
        assertFalse(Files.exists(firstDir), "首次调用（lastSweep 为空）应立即扫一次 —— Worker 启动即清理历史残骸");

        long second = terminalTask(TaskStatus.SUCCEEDED, null);
        Path secondDir = sandbox(second);

        cleaner.sweepIfDue();
        assertTrue(Files.exists(secondDir), "距上次扫描未满间隔，这一轮应直接跳过");

        clock.advance(SWEEP_INTERVAL);
        cleaner.sweepIfDue();
        assertFalse(Files.exists(secondDir), "过了间隔后应重新扫描并回收");
    }

    @Test
    @DisplayName("开关关闭：sweepIfDue 什么都不做（排查问题时想留全量沙箱）")
    void disabledCleanerDoesNothing() throws IOException {
        long taskId = terminalTask(TaskStatus.SUCCEEDED, null);
        Path dir = sandbox(taskId);
        clock.advance(RETENTION.plusHours(1));

        WorkspaceCleaner disabled = new WorkspaceCleaner(sandboxRoot, RETENTION, false,
                SWEEP_INTERVAL, store, clock);
        disabled.sweepIfDue();

        assertTrue(Files.exists(dir));
    }

    // ------------------------------------------------------------------
    // 配置兜底
    // ------------------------------------------------------------------

    @Test
    @DisplayName("保留期配成 0 或负数：回落到默认 24h，而不是「立即删除」")
    void zeroOrNegativeRetentionFallsBackToDefault() {
        assertEquals(Duration.ofHours(24), properties(Duration.ZERO).workspaceRetention());
        assertEquals(Duration.ofHours(24), properties(Duration.ofHours(-1)).workspaceRetention());
        assertEquals(Duration.ofHours(24), properties(null).workspaceRetention());
        assertTrue(properties(null).workspaceCleanupEnabled(), "默认开启：不清理就会一直攒目录");
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private WorkspaceCleaner cleaner() {
        return new WorkspaceCleaner(sandboxRoot, RETENTION, true, SWEEP_INTERVAL, store, clock);
    }

    private static CoreProperties properties(Duration retention) {
        return new CoreProperties(2, List.of("test"), ".unused", false, false, false, null, retention);
    }

    /** 建一个处于给定状态的任务（用 InMemoryTaskStore 的真实写入，updatedAt 即状态写入时刻）。 */
    private long terminalTask(TaskStatus status, String failReason) {
        long taskId = store.createTask("E:/demo", "src/Demo.java", 21);
        store.updateTaskStatus(taskId, status, failReason);
        return taskId;
    }

    /** 建出该任务的沙箱目录，并放一个文件进去（模拟真实的「工程副本 + 产物」）。 */
    private Path sandbox(long taskId) throws IOException {
        Path dir = Files.createDirectories(sandboxRoot.resolve("task-" + taskId));
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        return dir;
    }

    private static void setModified(Path dir, Instant when) throws IOException {
        Files.setLastModifiedTime(dir, FileTime.from(when));
    }

    /** 可推进的假时钟：让「到期才删」这条判据无需 sleep 就能验证。 */
    private static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration amount) {
            this.instant = instant.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
