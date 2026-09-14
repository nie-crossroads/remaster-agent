package com.remasteragent.common.domain;

/**
 * DAG 节点的类型。
 *
 * <p>阶段 1 只用到 ANALYZE / REWRITE / VERIFY；阶段 2 加入 PLAN（理解层：读工程结构、
 * 规划迁移哪些文件）；GATE 在阶段 3 启用。
 */
public enum NodeType {
    /** 规划：读工程结构与检索上下文，产出「迁移哪些文件、什么顺序」的 DAG 计划 */
    PLAN,
    /** 读懂目标文件：AST 抽符号，组装上下文 */
    ANALYZE,
    /** 生成改写方案并落到沙箱工作目录 */
    REWRITE,
    /** 在沙箱里编译 + 跑单测 + 采覆盖率 */
    VERIFY,
    /** 人工门禁，挂起等待审批 */
    GATE
}
