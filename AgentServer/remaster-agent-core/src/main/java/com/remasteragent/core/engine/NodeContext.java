package com.remasteragent.core.engine;

import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.MigrationTask;

import java.nio.file.Path;

/**
 * 一次节点执行的上下文。
 *
 * <p>刻意只携带「这次执行需要知道的东西」，不含任何服务引用 —— 服务通过节点实现的构造函数注入。
 * 这样节点本身是纯函数式的：给定上下文就产出结果，可以用桩件在单测里跑完整的调度与回退逻辑，
 * 不需要数据库、不需要模型、不需要 Docker。这是「core 不依赖 Spring Web」这条约束真正的收益。
 *
 * @param task          所属任务
 * @param node          正在执行的节点（含 attempt，回退重写时它 &gt;0）
 * @param workspace     沙箱工作目录，被测工程的副本；节点只能在这里面改东西
 * @param retryFeedback 上一次失败的摘要，回退重写时才有值
 */
public record NodeContext(
        MigrationTask task,
        DagNode node,
        Path workspace,
        String retryFeedback
) {

    public int attempt() {
        return node.attempt();
    }

    public boolean isRetry() {
        return node.attempt() > 0;
    }
}
