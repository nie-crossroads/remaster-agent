package com.remasteragent.common.agent;

import java.util.List;

/**
 * POM_REWRITE 节点的产出。
 *
 * <p><b>为什么必须带上完整的新内容</b>（而不是只记「改了哪些文件」）：
 * {@code DagScheduler.restoreWorkspace} 的工作方式是「复制原工程 + 从 checkpoint 重放已成功节点的产出」。
 * 沙箱的 {@code .git} 不进副本、pom 也不在任何 REWRITE 节点的产物里 —— 只记路径的话，
 * 一旦任务因人工门禁/评审挂起后重新入队（沙箱被重建），编译级别会悄悄退回升级前的值，
 * 后续 VERIFY 于是在 {@code release=8} 下编译 record 而必然失败，且失败原因指向「代码有问题」——
 * 与真因（pom 改动丢了）完全错位。带上内容，这类静默错位就不可能发生。
 *
 * @param targetJdk   本次升级到的目标 JDK
 * @param scannedPoms 扫过的 pom 数（含无需改动的）。<b>刻意记下来</b>：否则「一个文件都没改」
 *                    与「压根没找到 pom」在结果里长得一样，事后无法区分是「已达标」还是「找错了目录」
 * @param pomFiles    被改写的 pom 清单（相对工程根）
 * @param files       逐文件的产出（路径 + 新内容 + 改了什么），供 checkpoint 重放
 */
public record PomRewriteResult(
        int targetJdk,
        int scannedPoms,
        List<String> pomFiles,
        List<FileChange> files
) {

    /**
     * 单个 pom 的改动。
     *
     * @param filePath   相对工程根的 pom 路径（根的 pom 就是 {@code pom.xml}）
     * @param newContent 改写后的完整文件内容
     * @param summary    一句话说明改动了什么（如 {@code maven.compiler.release: 8 → 21}）
     */
    public record FileChange(
            String filePath,
            String newContent,
            String summary
    ) {
    }

    /** 没有任何 pom 需要改动时的产出（编译级别已达标）。 */
    public static PomRewriteResult noChange(int targetJdk, int scannedPoms) {
        return new PomRewriteResult(targetJdk, scannedPoms, List.of(), List.of());
    }

    /** 是否真的改动了文件。 */
    public boolean changed() {
        return !files.isEmpty();
    }
}
