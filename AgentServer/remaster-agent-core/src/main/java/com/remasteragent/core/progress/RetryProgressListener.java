package com.remasteragent.core.progress;

import com.remasteragent.common.domain.NodeStatus;
import com.remasteragent.llm.retry.LlmAttemptListener;
import com.remasteragent.llm.retry.LlmRetryExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把「大模型第几次尝试失败了」翻译成一条前端看得懂的进度事件。
 *
 * <h2>要解决的问题</h2>
 * <p>上游网关偶发 524（上游响应超时）时，一次调用会在<b>同一次节点执行内部</b>重试两三次，
 * 每次一到两分钟。而这段时间里节点状态一直是 RUNNING、{@code attempt} 也一直是 0
 * （HTTP 层重试不递增节点 attempt —— 它压根不是「回退重写」）——
 * 页面看起来就是彻底静止的，与「卡死」无从区分。
 *
 * <p>实测过一个真实任务：REWRITE 节点跑了 5 分 56 秒，其中约 4 分钟是在等两次注定失败的重试。
 * 用户唯一能看到的信息是「第 1 轮 · 运行中」，于是很自然地得出了「卡住了」的结论 ——
 * 而这是错误的结论，任务最后是成功的。把重试广播出去，停滞感才变成「正在重试」这个可理解的进度。
 *
 * <h2>为什么事件类型复用 node_status</h2>
 * <p>节点确实还是 RUNNING，变的只是「为什么还在跑」。新造一种类型就要前端多一个分支、
 * 后端多一处契约，而语义上并没有新增任何东西 —— 涨的是维护成本，不是信息量。
 *
 * <h2>为什么做成独立类而不是各节点自己写</h2>
 * <p>PLAN 与 REWRITE 都需要它。重试进度这条链路上最容易出错的地方（拼错状态值、
 * 漏包 try/catch、复用了错的 attempt 语义）都一样，抄一遍就是多一份抄错的机会。
 */
public final class RetryProgressListener implements LlmAttemptListener {

    private static final Logger log = LoggerFactory.getLogger(RetryProgressListener.class);

    private final ProgressPublisher publisher;
    private final long taskId;
    private final Long nodeId;
    private final String nodeKey;
    private final int nodeAttempt;

    /** 这一步在做什么，如「改写」「规划」—— 拼进给用户看的那句话里。 */
    private final String action;

    public RetryProgressListener(ProgressPublisher publisher, long taskId, Long nodeId,
                                 String nodeKey, int nodeAttempt, String action) {
        this.publisher = publisher == null ? ProgressPublisher.NOOP : publisher;
        this.taskId = taskId;
        this.nodeId = nodeId;
        this.nodeKey = nodeKey;
        this.nodeAttempt = nodeAttempt;
        this.action = action == null ? "" : action;
    }

    @Override
    public void onAttemptFailed(int attempt, int maxAttempts, Throwable cause, boolean willRetry) {
        String reason = LlmRetryExecutor.describeShort(cause);
        // 用冒号而不是再加一层括号：原因自身可能已经带括号（如「HTTP 524（上游响应超时）」），
        // 套两层括号在页面上会读成一团。
        String message = action + "第 " + attempt + "/" + maxAttempts + " 次调用失败：" + reason
                + (willRetry ? "，正在重试" : "，不再重试");
        try {
            publisher.publish(ProgressEvent.nodeStatus(
                    taskId, nodeId, nodeKey, NodeStatus.RUNNING.name(), nodeAttempt, message));
        } catch (RuntimeException e) {
            // 进度发不出去绝不能让业务失败：它是「顺便告诉你」的旁路信息，不是业务数据。
            // 但也不能静默 —— 事件丢了却没人知道，正是那条静默失效链路最初的成因。
            log.warn("发布重试进度失败（不影响任务）: {}", e.toString());
        }
    }
}
