package com.remasteragent.llm.rewrite;

import com.remasteragent.common.agent.RewriteProposal;
import com.remasteragent.common.rag.RetrievedChunk;
import com.remasteragent.llm.retry.LlmAttemptListener;

import java.util.List;

/**
 * 改写请求。
 *
 * @param filePath        目标文件，相对工程根
 * @param packageName     包名，用于校验模型没有偷偷换包
 * @param className       主类型名，同上
 * @param sourceContent   改写前完整源码
 * @param targetJdk       目标 JDK 版本
 * @param attempt         第几次尝试；&gt;0 说明这是回退重写
 * @param failureFeedback 上次失败的摘要（编译错误行 / 失败用例名），首次为空。
 *                        这是回退能起作用的关键：不给失败信息就让模型重写，
 *                        它只会用另一种写法再错一遍。
 * @param contextChunks   检索到的相关代码块（跨文件上下文），可为空。
 *                        <b>为什么放在这里而不是让改写器自己去查</b>：改写器是纯 LLM 封装，
 *                        一旦它自己去连数据库、算向量，就再也没法用桩件单测了。
 *                        由节点（编排层）把已经取到的上下文递进来，改写器只负责「拼进 prompt」。
 *                        null 会被规整成空列表，调用方不必到处判空。
 * @param attemptListener 每次 HTTP 尝试失败后的回调，可为空（规整成 {@link LlmAttemptListener#NONE}）。
 *                        <b>由调用方（节点）而不是改写器来提供</b>：只有节点知道这次执行属于哪个
 *                        任务的哪个节点，也只有它手上有进度发布口。改写器只负责「如实上报」，
 *                        不关心上报到哪 —— 于是它仍然可以在没有 Redis、没有数据库的单测里跑。
 */
public record RewriteCommand(
        String filePath,
        String packageName,
        String className,
        String sourceContent,
        int targetJdk,
        int attempt,
        String failureFeedback,
        List<RetrievedChunk> contextChunks,
        LlmAttemptListener attemptListener
) {

    public RewriteCommand {
        contextChunks = contextChunks == null ? List.of() : List.copyOf(contextChunks);
        attemptListener = attemptListener == null ? LlmAttemptListener.NONE : attemptListener;
    }

    /** 兼容「不带重试回调」的构造：重试与进度上报交由上层负责。 */
    public RewriteCommand(String filePath, String packageName, String className,
                          String sourceContent, int targetJdk, int attempt, String failureFeedback,
                          List<RetrievedChunk> contextChunks) {
        this(filePath, packageName, className, sourceContent, targetJdk, attempt, failureFeedback,
                contextChunks, null);
    }

    /** 兼容「不带检索上下文」的构造：阶段 1 的调用方与单测都走这个入口。 */
    public RewriteCommand(String filePath, String packageName, String className,
                          String sourceContent, int targetJdk, int attempt, String failureFeedback) {
        this(filePath, packageName, className, sourceContent, targetJdk, attempt, failureFeedback,
                List.of(), null);
    }

    public boolean isRetry() {
        return attempt > 0;
    }

    /** 是否带上了跨文件上下文 —— 决定 prompt 里要不要出现「相关代码」那一节。 */
    public boolean hasContext() {
        return !contextChunks.isEmpty();
    }
}
