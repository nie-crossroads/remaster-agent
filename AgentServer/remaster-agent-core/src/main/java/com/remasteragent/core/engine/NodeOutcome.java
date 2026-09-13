package com.remasteragent.core.engine;

/**
 * 节点执行结果。
 *
 * <p>用返回值表达失败而不是抛异常，是刻意的选择：节点的失败<b>是正常业务流程的一部分</b>
 * （编译不过、单测挂了，然后回退重写），不是异常情况。如果用异常表达，
 * 调度器就得用 try/catch 去区分「可重试的业务失败」和「代码有 bug 的系统故障」，
 * 而这两者的处理方式完全不同 —— 前者走 attempt+1，后者应当让任务立刻失败并保留堆栈。
 *
 * @param success 是否成功
 * @param result  成功时的产出对象，会被序列化成 JSON 写进 dag_node.result（checkpoint）
 * @param error   失败原因，会写进 dag_node.error 并可能喂回下一次改写
 */
public record NodeOutcome(boolean success, Object result, String error) {

    public static NodeOutcome ok(Object result) {
        return new NodeOutcome(true, result, null);
    }

    public static NodeOutcome fail(String error) {
        return new NodeOutcome(false, null, error);
    }
}
