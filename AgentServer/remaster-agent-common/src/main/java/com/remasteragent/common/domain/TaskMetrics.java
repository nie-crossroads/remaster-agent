package com.remasteragent.common.domain;

/**
 * 一次迁移任务的量化指标 —— 这才是能写进简历的东西。
 *
 * <p>刻意保留原始计数（通过数 / 总数）而不只存比率，
 * 因为面试时被追问「分母是什么」的概率远高于「比率是多少」。
 *
 * @param filesTotal       参与迁移的文件数
 * @param compilePassed    编译通过的文件数
 * @param testsTotal       单测总数
 * @param testsPassed      单测通过数
 * @param coverage         行覆盖率（JaCoCo，0~1）
 * @param llmCalls         LLM 调用总次数
 * @param promptTokens     prompt token 总量
 * @param completionTokens completion token 总量
 * @param totalCost        总成本（元）
 * @param verifyAttempts   VERIFY 节点执行次数，>1 说明发生过回退重写
 * @param durationMs       端到端耗时
 */
public record TaskMetrics(
        int filesTotal,
        int compilePassed,
        int testsTotal,
        int testsPassed,
        double coverage,
        int llmCalls,
        long promptTokens,
        long completionTokens,
        double totalCost,
        int verifyAttempts,
        long durationMs
) {

    /** 编译通过率。分母为 0 时返回 0，避免除零。 */
    public double compilePassRate() {
        return filesTotal == 0 ? 0d : (double) compilePassed / filesTotal;
    }

    /** 单测通过率。分母为 0 时返回 0。 */
    public double testPassRate() {
        return testsTotal == 0 ? 0d : (double) testsPassed / testsTotal;
    }

    /** 是否发生过回退重写 —— 面试时这是最能展开讲的一个布尔值。 */
    public boolean retried() {
        return verifyAttempts > 1;
    }
}
