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
    /**
     * 父工程升级：把 {@code spring-boot-starter-parent} / BOM 升级到目标大版本（Spring Boot 2.7 → 3.x）。
     *
     * <p><b>这是阶段 5 第②层（parent/BOM 升级）</b>：换掉 parent 版本，整个 BOM 托管的
     * {@code spring-boot-starter-*} 依赖会从 <b>javax</b> 命名空间跳到 <b>jakarta</b> 命名空间，
     * 同时强制 Java 17+。它是第④层（坐标迁移：注入 jakarta 依赖）能成立的前提——
     * 不先升 parent，注入的 {@code jakarta.*} 依赖只会跟旧 SB2 的 {@code javax} 栈冲突。</p>
     *
     * <p><b>确定性、不调模型</b>：目标版本是固化常量，由纯文本替换完成，不查 Maven Central。
     * 之所以单独成节点而非扩进 {@link #POM_REWRITE}，是为了让「升级失败」能精确定位到
     * 「parent 层」「依赖层」还是「代码层」。</p>
     *
     * <p><b>必须排在 POM_REWRITE 之后、DEPENDENCY_UPGRADE 之前</b>：先抬编译级别、再升 parent/BOM，
     * 最后才注入 jakarta 依赖，顺序错了会让 VERIFY 在错误的命名空间下编译。</p>
     */
    PARENT_UPGRADE,
    /**
     * 依赖升级：把因 {@code javax.*→jakarta.*} 坐标迁移而缺失的 Jakarta API 依赖注入 {@code pom.xml}。
     *
     * <p><b>确定性、不调模型</b>：由白名单映射表（被移除的 javax 包 → Jakarta 构件坐标，版本固化为配置）
     * 驱动，是阶段 5「SDK 升级」第④层（坐标迁移）的落地形态。之所以单独成节点而非扩进
     * {@link #POM_REWRITE}，是为了让「升级失败」能精确定位到「依赖层」还是「代码层」。
     *
     * <p><b>必须排在 POM_REWRITE 之后、所有 REWRITE 之前</b>：POM_REWRITE 抬完编译级别后，
     * 源码改写把 import 换成 {@code jakarta.*}，此时 pom 里必须有对应依赖，VERIFY 才能编过。
     * 它与 POM_REWRITE 都只改 pom、互不重叠（一个动编译级别、一个动 dependencies）。
     */
    DEPENDENCY_UPGRADE,
    /** 生成改写方案并落到沙箱工作目录 */
    REWRITE,
    /** 在沙箱里编译 + 跑单测 + 采覆盖率 */
    VERIFY,
    /** 人工门禁，挂起等待审批 */
    GATE
}
