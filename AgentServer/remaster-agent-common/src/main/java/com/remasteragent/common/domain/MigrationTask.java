package com.remasteragent.common.domain;

import java.time.Instant;

/**
 * 一次迁移任务。
 *
 * <p>阶段 1 的输入是「本地可信工程目录 + 目标文件」，还不支持直接拉远端仓库；
 * 阶段 2 接入 AST 切块与代码 RAG 后会扩展成整仓迁移。
 *
 * <h2>两种任务形态由 {@code entryFile} 区分</h2>
 * <ul>
 *   <li><b>非空</b> —— 常规迁移：改写这一个文件，编排走
 *       ANALYZE → REWRITE →（GATE）→ VERIFY，必要时在最前面插 POM_REWRITE。</li>
 *   <li><b>null</b> —— <b>整仓升级</b>：把全仓 {@code pom.xml} 的编译级别抬到目标 JDK，
 *       <b>不做任何代码改写</b>。拓扑是 POM_REWRITE →（GATE）→ VERIFY，连 ANALYZE 都不跑 ——
 *       它读的是入口文件，这里没有；而它顺带做的工程索引对本模式毫无用处（POM_REWRITE 与
 *       VERIFY 都不调模型），跑一遍只是白烧 embedding 调用。</li>
 * </ul>
 * <p>两者不是「填没填」的差别，是两个合法的任务类型，所以数据层用 NULL 而不是空串表达 ——
 * 空串会被当成「填了个空路径」，那是要报错的输入。
 *
 * @param id          自增主键
 * @param projectRoot 被测工程的根目录（含 pom.xml），沙箱执行时会被复制出去，绝不原地修改
 * @param entryFile   本轮要改写的单个文件，相对 projectRoot 的路径；<b>null = 整仓升级模式</b>
 * @param targetJdk   目标 JDK 版本，阶段 1 固定 21
 * @param status      任务状态
 * @param metricsJson 完成后的指标汇总（JSON），对应 JSONB 列；未完成时为空
 * @param failReason  失败原因，成功时为空
 * @param cancelRequested 是否已被请求取消（协作式停止：API 置位，Worker 在节点边界停下并落 CANCELLED）。
 *                   它与 {@code status} 是两个维度 —— 一个 RUNNING 且已请求取消的任务，状态仍是 RUNNING，
 *                   因为此刻确实还有节点在跑，写成 CANCELLED 就是撒谎。前端据它显示「正在取消…」
 * @param createdAt   创建时间
 * @param updatedAt   最后更新时间
 * @param name        任务名（建单页手动输入，可选；评测任务取用例 id）。仅用于展示区分，不参与编排
 * @param demo        是否为演示任务（游客触发、跑服务器本地可信样本）。true 时从真实用户任务列表过滤，
 *                    并用更短保留期清理沙箱（见 {@code WorkspaceCleaner}）。与「普通用户任务」是两条创建通道，
 *                    但共用同一套编排与沙箱，所以语义上只是一个布尔标记，不是独立的任务类型。
 */
public record MigrationTask(
        Long id,
        String projectRoot,
        String entryFile,
        int targetJdk,
        TaskStatus status,
        String metricsJson,
        String failReason,
        boolean cancelRequested,
        Instant createdAt,
        Instant updatedAt,
        String name,
        boolean demo
) {
    public static final int DEFAULT_TARGET_JDK = 21;
}
