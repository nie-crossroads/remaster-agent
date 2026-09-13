package com.remasteragent.common.domain;

import java.time.Instant;

/**
 * 一次改写产生的补丁 —— 人工 diff 审查与回滚的依据。
 *
 * <p>模型产出的是整文件内容，diff 由服务端本地算出来（见
 * {@code com.remasteragent.tools.diff.UnifiedDiffGenerator}）。这样做比让模型直接生成
 * unified diff 可靠得多：模型拼 diff 极易错行，而错一行的补丁是没法应用的。
 *
 * @param id             自增主键
 * @param nodeId         产出它的 REWRITE 节点
 * @param filePath       被改写的文件，相对工程根
 * @param diff           unified diff 文本，前端 Monaco 直接渲染
 * @param originalHash   改写前文件内容哈希，用于校验「基线有没有被人动过」
 * @param applied        是否已应用到沙箱工作目录
 * @param createdAt      生成时间
 */
public record PatchRecord(
        Long id,
        long nodeId,
        String filePath,
        String diff,
        String originalHash,
        boolean applied,
        Instant createdAt
) {
}
