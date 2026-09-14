package com.remasteragent.llm.retry;

/**
 * 「一次大模型调用尝试失败」的通知口。
 *
 * <h2>为什么需要它</h2>
 * <p>LangChain4j 自带的 {@code maxRetries} 是<b>静默重试</b>：它只往日志里打一行 WARN，
 * 调用方（我们的节点）完全不知道发生过重试。实测过一次远低于预期的任务：一个 REWRITE 节点
 * 跑了近 6 分钟，页面却全程显示「第 1 轮 · 运行中」—— 因为上游网关返回了两次 524，
 * SDK 自己默默重试了，而节点侧既没有日志也没有事件。
 *
 * <p>调用方（编排层）拿到这个回调后，就能把「正在重试」翻译成一条进度事件推给前端。
 * 这条链路必须打通：<b>重试本身没错，但「重试了却没人知道」会把一个正常行为伪装成卡死</b>，
 * 用户唯一的反应就是以为程序挂了。
 *
 * <p><b>为什么定义在 llm 模块而不是 core</b>：依赖方向是 {@code core → llm}，llm 不能反向依赖
 * core 的 {@code ProgressEvent}。所以这里只留一个纯 Java 的回调签名，由 core 侧的实现
 * 负责决定「把它变成哪种进度事件」。
 *
 * @see LlmRetryExecutor
 */
@FunctionalInterface
public interface LlmAttemptListener {

    /**
     * 一次尝试失败后回调。
     *
     * <p>调用时机在「决定是否重试」之后：所以 {@code willRetry} 为 {@code true} 时，
     * 实现方可以放心地告诉用户「正在重试」；为 {@code false} 时这次失败即将抛给调用方，
     * 应当表达成「不再重试」。
     *
     * @param attempt     第几次尝试失败了（从 1 开始，不是 0）
     * @param maxAttempts 这个执行器一共会尝试几次
     * @param cause       本次失败的原因
     * @param willRetry   是否还会再试一次；{@code false} 表示异常即将抛出
     */
    void onAttemptFailed(int attempt, int maxAttempts, Throwable cause, boolean willRetry);

    /** 什么都不做的实现 —— 单测与不关心重试进度的调用方用。 */
    LlmAttemptListener NONE = (attempt, maxAttempts, cause, willRetry) -> {
    };
}
