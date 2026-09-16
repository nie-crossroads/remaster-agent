package com.remasteragent.core.engine;

/**
 * 节点执行结果 —— 三态：成功 / 失败 / 挂起。
 *
 * <p>用返回值表达失败而不是抛异常，是刻意的选择：节点的失败<b>是正常业务流程的一部分</b>
 * （编译不过、单测挂了，然后回退重写），不是异常情况。如果用异常表达，
 * 调度器就得用 try/catch 去区分「可重试的业务失败」和「代码有 bug 的系统故障」，
 * 而这两者的处理方式完全不同 —— 前者走 attempt+1，后者应当让任务立刻失败并保留堆栈。
 *
 * <h2>为什么还要有第三种「挂起」</h2>
 * <p>GATE 节点到达时既没成功也没失败：它在等人。把它硬塞进「成功」会让下游节点
 * 立刻开跑（等于门禁没生效），塞进「失败」会触发回退重写（等于把「等人」误当成
 * 「改错了」）—— 两种都会错得很隐蔽。所以显式给出第三态，让调度器能对它做
 * 「挂起任务、不占 Worker、等人工放行后再从 checkpoint 续跑」这一件事。
 *
 * <p>{@link #success()} 与 {@link #suspended()} 是互斥的谓词；挂起时 {@code result}
 * 携带的是「挂起的现场描述」（供展示/审计），{@code error} 恒为空。
 *
 * @param kind   结果类型
 * @param result 成功时的产出对象（序列化进 {@code dag_node.result} 作为 checkpoint），
 *               或挂起时的现场描述
 * @param error  失败原因，会写进 {@code dag_node.error} 并可能喂回下一次改写
 */
public record NodeOutcome(Kind kind, Object result, String error) {

    /** 结果类型。 */
    public enum Kind {
        /** 执行成功 */
        SUCCESS,
        /** 执行失败（业务失败，可能触发回退重写） */
        FAIL,
        /** 挂起等待人工（GATE 节点） */
        SUSPEND
    }

    public static NodeOutcome ok(Object result) {
        return new NodeOutcome(Kind.SUCCESS, result, null);
    }

    public static NodeOutcome fail(String error) {
        return new NodeOutcome(Kind.FAIL, null, error);
    }

    /**
     * 失败，但带上有价值的现场。
     *
     * <p>VERIFY 失败时那些覆盖率/单测统计恰恰是最该留痕的数据 —— 只记一句「失败了」，
     * 事后完全无法回答「它当时离通过还差多少」。所以失败态也允许携带 result。
     *
     * @param result 失败时的现场（同样会被序列化进 checkpoint）
     * @param error  失败原因摘要
     */
    public static NodeOutcome fail(Object result, String error) {
        return new NodeOutcome(Kind.FAIL, result, error);
    }

    /**
     * 挂起，等待人工放行。
     *
     * @param result 挂起现场描述（如「已生成补丁，待确认」），可为空
     */
    public static NodeOutcome suspend(Object result) {
        return new NodeOutcome(Kind.SUSPEND, result, null);
    }

    /** 是否成功。挂起不算成功。 */
    public boolean success() {
        return kind == Kind.SUCCESS;
    }

    /** 是否挂起等待人工。 */
    public boolean suspended() {
        return kind == Kind.SUSPEND;
    }
}
