package com.remasteragent.common.domain;

import java.time.Instant;

/**
 * 一次迁移任务。
 *
 * <p>阶段 1 的输入是「本地可信工程目录 + 目标文件」，还不支持直接拉远端仓库；
 * 阶段 2 接入 AST 切块与代码 RAG 后会扩展成整仓迁移。
 *
 * @param id          自增主键
 * @param projectRoot 被测工程的根目录（含 pom.xml），沙箱执行时会被复制出去，绝不原地修改
 * @param entryFile   本轮要改写的单个文件，相对 projectRoot 的路径
 * @param targetJdk   目标 JDK 版本，阶段 1 固定 21
 * @param status      任务状态
 * @param metricsJson 完成后的指标汇总（JSON），对应 JSONB 列；未完成时为空
 * @param failReason  失败原因，成功时为空
 * @param createdAt   创建时间
 * @param updatedAt   最后更新时间
 */
public record MigrationTask(
        Long id,
        String projectRoot,
        String entryFile,
        int targetJdk,
        TaskStatus status,
        String metricsJson,
        String failReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static final int DEFAULT_TARGET_JDK = 21;
}
