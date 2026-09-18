package com.remasteragent.core.writeback;

import java.time.Instant;
import java.util.List;

/**
 * 一次「变更回写」的预检报告 / 执行结果。
 *
 * <p>两者刻意用同一个形状：预检告诉你「将会写哪几个文件、有什么拦路的」，
 * 执行后返回的是同一张表加一个时间戳。前端因此可以只渲染一种卡片 ——
 * 「先看清单再点确认」这条交互不需要两套数据结构，也就不会出现
 * 「预检说有 3 个文件、执行后报告说 2 个」这种两边对不上的情况。
 *
 * @param taskId                  任务 id
 * @param projectRoot             源工程根目录（写回目标）
 * @param workspace               沙箱工作目录（写回内容来源）
 * @param backupDir               备份目录；未执行时为 null
 * @param files                   将要 / 已经写回的文件
 * @param blocked                 拦路项 —— <b>非空即拒绝执行</b>，绝不「尽力而为写一部分」
 * @param warnings                放行但不放心的提示（如目录不是 git 工作区）
 * @param suggestedCommitMessage  建议的提交信息；写回是仓库内容变更，提交这件事交给人
 * @param appliedAt               实际写回时间；null 表示只做了预检
 */
public record WriteBackReport(
        long taskId,
        String projectRoot,
        String workspace,
        String backupDir,
        List<FileEntry> files,
        List<Blocked> blocked,
        List<String> warnings,
        String suggestedCommitMessage,
        Instant appliedAt
) {

    /**
     * 一个待写回的文件。
     *
     * @param filePath   相对工程根的路径
     * @param bytes      将要写入的字节数
     * @param sha256     将要写入内容的哈希（执行后即为磁盘上的实际内容指纹）
     * @param baseOk     该文件的「改写前基线」与源工程现状是否一致
     */
    public record FileEntry(String filePath, long bytes, String sha256, boolean baseOk) {
    }

    /**
     * 拦路项。
     *
     * @param code    机器可判的代号（{@code TASK_NOT_SUCCEEDED} / {@code DIRTY_WORKING_TREE} 等），
     *                前端据此决定提示措辞，也便于以后做自动化
     * @param message 给人看的说明，必须包含「怎么解决」
     */
    public record Blocked(String code, String message) {
    }

    /** 预检是否通过（没有拦路项）。 */
    public boolean ready() {
        return blocked.isEmpty();
    }

    /** 是否已经真的写回了。 */
    public boolean applied() {
        return appliedAt != null;
    }
}
