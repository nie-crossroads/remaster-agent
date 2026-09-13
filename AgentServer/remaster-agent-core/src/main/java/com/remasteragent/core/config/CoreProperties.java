package com.remasteragent.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
 */
@ConfigurationProperties(prefix = "remaster.core")
public record CoreProperties(
        Integer maxRewriteAttempts,
        List<String> verifyMavenGoals,
        String workspaceRoot,
        Boolean stopOnFirstFailure
) {

    /** JaCoCo 版本。0.8.13 支持到 Java 22，足以覆盖 JDK 21 的 class 文件版本 65。 */
    private static final String JACOCO_VERSION = "0.8.13";

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
    }

    /** 总轮次 = 初次 + 重试次数。 */
    public int maxRounds() {
        return maxRewriteAttempts + 1;
    }
}
