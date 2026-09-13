package com.remasteragent.llm.rewrite;

import com.remasteragent.common.agent.RewriteProposal;

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
 */
public record RewriteCommand(
        String filePath,
        String packageName,
        String className,
        String sourceContent,
        int targetJdk,
        int attempt,
        String failureFeedback
) {

    public boolean isRetry() {
        return attempt > 0;
    }
}
