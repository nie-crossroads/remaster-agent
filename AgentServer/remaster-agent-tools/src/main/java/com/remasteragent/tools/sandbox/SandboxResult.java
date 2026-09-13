package com.remasteragent.tools.sandbox;

/**
 * 沙箱执行结果。
 *
 * <p>{@code stdout} / {@code stderr} 会被截断到上限长度后再返回 ——
 * Maven 的输出动辄几万行，全量留在内存里没有意义，而且会把它原样喂给模型时烧掉大量 token。
 * 真正解析结果靠的是 {@link com.remasteragent.tools.maven.MavenResultParser} 去读
 * surefire XML 与 jacoco.csv，不是靠啃控制台文本。
 *
 * @param exitCode   进程退出码；被超时杀死时为 -1
 * @param stdout     标准输出（已截断）
 * @param stderr     标准错误（已截断）
 * @param durationMs 实际耗时
 * @param timedOut   是否因超时被强制杀死
 */
public record SandboxResult(
        int exitCode,
        String stdout,
        String stderr,
        long durationMs,
        boolean timedOut
) {

    public boolean succeeded() {
        return !timedOut && exitCode == 0;
    }

    /** 给日志和 prompt 用的简短摘要。 */
    public String summary() {
        if (timedOut) {
            return "沙箱执行超时（" + durationMs + " ms），进程已被强制终止";
        }
        return "退出码 " + exitCode + "，耗时 " + durationMs + " ms";
    }
}
