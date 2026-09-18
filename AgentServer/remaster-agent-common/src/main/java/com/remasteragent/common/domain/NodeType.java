package com.remasteragent.common.domain;

/**
 * DAG 节点的类型。
 *
 * <p>阶段 1 只用到 ANALYZE / REWRITE / VERIFY；阶段 2 加入 PLAN（理解层：读工程结构、
 * 规划迁移哪些文件）；GATE 在阶段 3 启用；POM_REWRITE 在阶段 4 收尾启用
 * （整仓 JDK 升级的编译级别改写）。
 */
public enum NodeType {
    /** 规划：读工程结构与检索上下文，产出「迁移哪些文件、什么顺序」的 DAG 计划 */
    PLAN,
    /** 读懂目标文件：AST 抽符号，组装上下文 */
    ANALYZE,
    /**
     * 改写构建文件的编译级别（{@code pom.xml} 的 {@code maven.compiler.release} 等）。
     *
     * <p><b>为什么它必须排在所有 REWRITE 之前</b>：模型按提示词产出的 record / 文本块等
     * Java 17+ 语法，在 {@code release=8} 的工程里会被 {@code javac} 直接拒绝 ——
     * 于是每个文件都会「改对了但编译不过」，白白烧掉整个回退配额。
     * 先把编译级别抬到目标 JDK，后面的改写才有意义。
     *
     * <p><b>它不做依赖升级</b>：只改编译级别。依赖版本跃迁是另一件事
     * （要联网的版本元数据 + 破坏性变更连锁修复），刻意不混在一个节点里。
     */
    POM_REWRITE,
    /** 生成改写方案并落到沙箱工作目录 */
    REWRITE,
    /** 在沙箱里编译 + 跑单测 + 采覆盖率 */
    VERIFY,
    /** 人工门禁，挂起等待审批 */
    GATE
}
