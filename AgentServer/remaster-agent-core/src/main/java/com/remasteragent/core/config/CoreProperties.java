package com.remasteragent.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

/**
 * 编排内核配置。
 *
 * @param maxRewriteAttempts 最大重写尝试轮次。<b>这不是拍脑袋定的</b>：每一次重试都是一次
 *                           完整的「模型调用 + 沙箱构建」，成本与时间都是线性增长的。
 *                           取 2 意味着最多跑 3 轮（attempt 0/1/2）。超过这个次数还没通过，
 *                           说明问题不在模型的随机性，而在任务本身（比如工程缺少必要依赖），
 *                           继续重试只是烧钱。这也是必须有的护栏 —— 没有它，一个不可能完成的任务
 *                           会一直重试下去。
 * @param verifyMavenGoals   VERIFY 阶段在沙箱里执行的 Maven 目标。
 *                           默认用命令行直接调用 JaCoCo 插件，好处是<b>对被测仓库零侵入</b> ——
 *                           不用改它的 pom 就能采到覆盖率。被测仓库本来配了 JaCoCo 的话，
 *                           把这里改成只留 {@code test} 即可。
 *                           版本号显式钉死而不是留空让 Maven 去解析最新版：
 *                           VERIFY 产出的每个数字都会被写进评测报告，
 *                           插件版本漂移会让「同一份代码两次跑出不同覆盖率」，实测过。
 * @param workspaceRoot      沙箱工作目录的根目录。每个任务一个子目录，原仓库永不被就地修改。
 * @param stopOnFirstFailure 首个 VERIFY 失败后是否直接判定任务失败（调试用，正常应为 false）
 * @param requirePlanApproval 规划是否需要人工评审后才执行。<b>默认 false</b>：PLAN 产出计划后
 *                            直接执行，保持阶段 1/3 的端到端行为不变。置 true 时，PLAN 成功会
 *                            把任务挂到 {@code WAITING_HUMAN}，需人工在评审界面批准后再继续 ——
 *                            这是阶段 2「规划结果人工评审」验收项的开关。
 * @param requireRewriteApproval 每个文件改写后是否插入一道 GATE（人工门禁）再验证。
 *                            <b>默认 false</b>。置 true 时，`PlanNode` 铺出的每文件链条会从
 *                            {@code REWRITE → VERIFY} 变成 {@code REWRITE → GATE → VERIFY}：
 *                            改完先停下让人看一眼补丁，批准后才进沙箱验证。
 *                            与 {@code requirePlanApproval} 的区别是粒度 ——
 *                            前者是「任务级、一次性」的规划评审，本项是「节点级、每文件一道」
 *                            的通用门禁（阶段 3 的人在回路）。
 * @param workspaceCleanupEnabled 是否回收沙箱目录。<b>默认 true</b>：任务到达终态后，其
 *                            {@code task-<id>} 目录不会立刻删（要留给人看现场），但也不该永远留着 ——
 *                            每个任务都会留下一份「工程副本 + Maven 产物」，只增不删会累积成噪声。
 *                            置 false 可关掉（排查问题时想保留全量沙箱）。
 * @param workspaceRetention  沙箱保留期，默认 24 小时。判据是「任务到达终态后经过了多久」，
 *                            而不是目录的最后修改时间 —— 后者会被无关的 touch 干扰。
 *                            回收动作是<b>永久删除</b>，不进回收站：这些目录可由源工程随时重建，
 *                            而「攒一堆等人再删一遍」本身就是噪声。
 */
@ConfigurationProperties(prefix = "remaster.core")
public record CoreProperties(
        Integer maxRewriteAttempts,
        List<String> verifyMavenGoals,
        String workspaceRoot,
        Boolean stopOnFirstFailure,
        Boolean requirePlanApproval,
        Boolean requireRewriteApproval,
        Boolean workspaceCleanupEnabled,
        Duration workspaceRetention
) {

    /** JaCoCo 版本。0.8.13 支持到 Java 22，足以覆盖 JDK 21 的 class 文件版本 65。 */
    private static final String JACOCO_VERSION = "0.8.13";

    /** 沙箱保留期默认值：任务到达终态后 24 小时回收。 */
    private static final Duration DEFAULT_WORKSPACE_RETENTION = Duration.ofHours(24);

    private static final List<String> DEFAULT_GOALS = List.of(
            "org.jacoco:jacoco-maven-plugin:" + JACOCO_VERSION + ":prepare-agent",
            "test",
            "org.jacoco:jacoco-maven-plugin:" + JACOCO_VERSION + ":report");

    public CoreProperties {
        maxRewriteAttempts = maxRewriteAttempts == null ? 2 : maxRewriteAttempts;
        verifyMavenGoals = (verifyMavenGoals == null || verifyMavenGoals.isEmpty())
                ? DEFAULT_GOALS : List.copyOf(verifyMavenGoals);
        workspaceRoot = (workspaceRoot == null || workspaceRoot.isBlank())
                ? ".remaster-workspaces" : workspaceRoot;
        stopOnFirstFailure = stopOnFirstFailure != null && stopOnFirstFailure;
        requirePlanApproval = requirePlanApproval != null && requirePlanApproval;
        requireRewriteApproval = requireRewriteApproval != null && requireRewriteApproval;
        workspaceCleanupEnabled = workspaceCleanupEnabled == null || workspaceCleanupEnabled;
        // 配成 0 或负数视为「没配」，回落到默认值 —— 否则一次笔误就会把保留期变成「即时删除」，
        // 那是个很难在生产里被发现的静默行为变化。
        workspaceRetention = (workspaceRetention == null
                || workspaceRetention.isZero() || workspaceRetention.isNegative())
                ? DEFAULT_WORKSPACE_RETENTION : workspaceRetention;
    }

    /** 总轮次 = 初次 + 重试次数。 */
    public int maxRounds() {
        return maxRewriteAttempts + 1;
    }

    /**
     * 沙箱根目录的<b>绝对路径</b>。
     *
     * <p>解析集中在这里，调用方不必各自 {@code toAbsolutePath()}。
     *
     * <p>注意：若配置值写的是相对路径，解析基准是<b>进程工作目录</b>（JVM 的 {@code user.dir}），
     * 换个目录启动就会生成另一份沙箱。本项目实测因此分裂出 4 份 {@code .remaster-workspaces}
     * （从 {@code AgentServer/}、仓库根、各模块目录分别启动各留下过一份，且同名 {@code task-N}
     * 互不相干）。所以生产配置一律写绝对路径，由 {@code DagScheduler} 在启动时校验并告警。
     */
    public Path workspaceRootPath() {
        return Paths.get(workspaceRoot).toAbsolutePath().normalize();
    }

    /** 配置里写的是否为绝对路径。启动时据此决定打印 INFO 还是 WARN。 */
    public boolean workspaceRootIsAbsolute() {
        return Paths.get(workspaceRoot).isAbsolute();
    }
}
