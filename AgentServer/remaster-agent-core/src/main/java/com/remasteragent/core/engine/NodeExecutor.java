package com.remasteragent.core.engine;

import com.remasteragent.common.domain.NodeType;

/**
 * 节点执行器 —— 一种节点类型一个实现。
 *
 * <p>调度器只知道这个接口，不知道 ANALYZE 内部用 JavaParser、VERIFY 内部要起沙箱。
 * 这条边界让调度逻辑可以被彻底单测：塞一个永远返回失败的 {@code VERIFY} 桩件进去，
 * 就能确定性地验证「回退重写」这条最容易出错的路径，而不需要真的跑一次模型和 Maven。
 */
public interface NodeExecutor {

    /** 本执行器负责的节点类型。 */
    NodeType type();

    /**
     * 执行节点。
     *
     * <p>实现约定：<b>业务失败用 {@link NodeOutcome#fail} 返回，不要抛异常。</b>
     * 只有真正的系统故障（数据库连不上、配置文件缺失）才应该抛出去。
     */
    NodeOutcome execute(NodeContext context);
}
