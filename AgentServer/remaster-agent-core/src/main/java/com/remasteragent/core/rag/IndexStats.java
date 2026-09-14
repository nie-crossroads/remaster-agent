package com.remasteragent.core.rag;

/**
 * 一次代码索引的结果统计。
 *
 * @param repoId     仓库 id（{@code repo.id}）
 * @param files      成功切块的源码文件数
 * @param chunks     写入的代码块总数
 * @param vectorized 是否带向量写入。<b>false 不是失败</b>：说明未配置 embedding 模型
 *                   （或向量化过程中远端报错而降级），向量路会被跳过，全文/符号两路照常可用。
 *                   把它显式暴露出来，是为了让「检索质量不如预期」时能一眼看出是不是少了一路召回。
 */
public record IndexStats(
        long repoId,
        int files,
        int chunks,
        boolean vectorized
) {
}
