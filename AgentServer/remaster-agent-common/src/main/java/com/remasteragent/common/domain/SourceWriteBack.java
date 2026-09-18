package com.remasteragent.common.domain;

import java.time.Instant;
import java.util.List;

/**
 * 一次「变更回写源工程」的审计记录。
 *
 * <p>它回答的是「谁在什么时候把哪些文件写回了哪里、出事了去哪找原件」——
 * 这四件事必须一次说全：只记文件清单，回滚时找不到备份；只记时间，事后对不上是哪个任务干的。
 *
 * @param id          自增主键
 * @param taskId      所属任务
 * @param projectRoot 被写回的源工程根目录（写回时的绝对路径）
 * @param backupDir   备份目录；回滚就是把这里的文件拷回原位。为空说明这次没备份（异常情况）
 * @param files       文件清单：{@code [{filePath, bytes, sha256}]}，sha256 是写回<b>后</b>的哈希，
 *                    事后可核对「磁盘上现在这份是不是当时写的那份」
 * @param fileCount   文件数（列表页直接显示，不必解析 files）
 * @param appliedAt   写回时间
 */
public record SourceWriteBack(
        Long id,
        long taskId,
        String projectRoot,
        String backupDir,
        List<AppliedFile> files,
        int fileCount,
        Instant appliedAt
) {

    /**
     * 一个被写回的文件。
     *
     * @param filePath 相对工程根
     * @param bytes    写回内容的字节数
     * @param sha256   写回后内容的 SHA-256
     */
    public record AppliedFile(String filePath, long bytes, String sha256) {
    }
}
