package com.remasteragent.core.writeback;

import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.core.codec.JsonCodec;
import com.remasteragent.core.config.CoreProperties;
import com.remasteragent.core.store.InMemoryTaskStore;
import com.remasteragent.tools.hash.ContentHash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SourceWriteBackService} 的确定性单测。
 *
 * <p>不连数据库、不跑 git、不调模型：存储用 {@link InMemoryTaskStore}，
 * 工作区检查用桩件，源工程与沙箱都是 {@link TempDir} 下的真目录。
 * 这样「预检拦得住 / 放行后写得对 / 审计留得下」三条主线都能毫秒级验证。
 */
class SourceWriteBackServiceTest {

    private static final String JAVA_FILE = "src/main/java/com/example/Legacy.java";
    private static final String ORIGINAL = "public class Legacy { int x; }\n";
    private static final String REWRITTEN = "public record Legacy(int x) {}\n";

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------
    // 固定装置
    // ------------------------------------------------------------------

    private static CoreProperties properties(String workspaceRoot) {
        return CoreProperties.withoutGateTimeout(
                2, List.of("test"), workspaceRoot,
                false, false, false, true, Duration.ofHours(24));
    }

    /** 工作区干净、且确实是 git 仓库 —— 预检放行的默认前提。 */
    private static WorkingTreeInspector cleanGit() {
        return root -> new WorkingTreeInspector.Result(true, true, "git 工作区干净");
    }

    private static WorkingTreeInspector dirtyGit() {
        return root -> new WorkingTreeInspector.Result(true, false, "git 工作区有 3 处未提交改动");
    }

    private static WorkingTreeInspector notGit() {
        return root -> new WorkingTreeInspector.Result(false, true, "不是 git 工作区，回写后无版本控制兜底");
    }

    /**
     * 搭一份最小现场：源工程、沙箱副本、任务（可选状态）、一条改写补丁。
     *
     * @param status         任务状态
     * @param sourceContent  源工程里那份文件的内容（决定基线校验过不过）
     * @param baselineHash   补丁里记的「改写前指纹」；null 表示让服务跳过比对
     * @param extraPatch     true 表示同一个文件再补一条「更晚」的补丁，用来验证取最早那条
     */
    private Fixture fixture(TaskStatus status, String sourceContent, String baselineHash, boolean extraPatch)
            throws IOException {
        Path projectRoot = tmp.resolve("source-project");
        Path workspaceRoot = tmp.resolve("workspaces");
        Files.createDirectories(projectRoot);

        long taskId = 1L;
        Path workspace = workspaceRoot.resolve("task-" + taskId);
        Path sandboxFile = workspace.resolve(JAVA_FILE);
        Path sourceFile = projectRoot.resolve(JAVA_FILE);
        Files.createDirectories(sandboxFile.getParent());
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sandboxFile, REWRITTEN, StandardCharsets.UTF_8);
        Files.writeString(sourceFile, sourceContent, StandardCharsets.UTF_8);

        InMemoryTaskStore store = new InMemoryTaskStore();
        long id = store.createTask(projectRoot.toString(), JAVA_FILE, 21, "回写单测");
        assertEquals(taskId, id, "单测假设首个任务 id 为 1（沙箱目录名依赖它）");
        if (status != TaskStatus.PENDING) {
            store.updateTaskStatus(id, status, null);
        }
        long nodeId = store.insertNode(id, "rewrite:" + JAVA_FILE, NodeType.REWRITE, List.of(), 0);
        store.insertPatch(nodeId, JAVA_FILE, "--- a\n+++ b\n", baselineHash);
        if (extraPatch) {
            // 第 2 轮补丁：它的 originalHash 对着的是「第 1 轮产出」，不是源工程 ——
            // 服务必须无视它，否则每个发生过回退的文件都会被误判成「被人动过」
            store.insertPatch(nodeId, JAVA_FILE, "--- a\n+++ b\n", ContentHash.sha256(REWRITTEN));
        }

        SourceWriteBackService service = new SourceWriteBackService(
                store, properties(workspaceRoot.toString()), new JsonCodec(), cleanGit());
        return new Fixture(service, store, projectRoot, workspace, sourceFile, sandboxFile, id);
    }

    private record Fixture(SourceWriteBackService service, InMemoryTaskStore store,
                           Path projectRoot, Path workspace, Path sourceFile, Path sandboxFile, long taskId) {
    }

    private static boolean hasBlock(WriteBackReport report, String code) {
        return report.blocked().stream().anyMatch(blocked -> blocked.code().equals(code));
    }

    // ------------------------------------------------------------------
    // 预检：拦路项
    // ------------------------------------------------------------------

    @Test
    @DisplayName("任务未成功时拒绝回写 —— 没通过编译与单测的产出不该进源工程")
    void preflightRejectsWhenTaskNotSucceeded() throws IOException {
        Fixture f = fixture(TaskStatus.RUNNING, ORIGINAL, ContentHash.sha256(ORIGINAL), false);

        WriteBackReport report = f.service().preflight(f.taskId());

        assertFalse(report.ready());
        assertTrue(hasBlock(report, "TASK_NOT_SUCCEEDED"), "拦路项代号: " + report.blocked());
    }

    @Test
    @DisplayName("沙箱已被回收时明确报出 WORKSPACE_GONE，并提示重跑")
    void preflightRejectsWhenWorkspaceGone() throws IOException {
        Fixture f = fixture(TaskStatus.SUCCEEDED, ORIGINAL, ContentHash.sha256(ORIGINAL), false);
        deleteRecursively(f.workspace());

        WriteBackReport report = f.service().preflight(f.taskId());

        assertFalse(report.ready());
        assertTrue(hasBlock(report, "WORKSPACE_GONE"), "拦路项代号: " + report.blocked());
    }

    @Test
    @DisplayName("源工程工作区不干净时拒绝 —— 否则 AI 的改动会和人的手工修改混在一起")
    void preflightRejectsDirtyWorkingTree() throws IOException {
        Fixture clean = fixture(TaskStatus.SUCCEEDED, ORIGINAL, ContentHash.sha256(ORIGINAL), false);
        SourceWriteBackService service = new SourceWriteBackService(
                clean.store(), properties(tmp.resolve("workspaces").toString()), new JsonCodec(), dirtyGit());

        WriteBackReport report = service.preflight(clean.taskId());

        assertFalse(report.ready());
        assertTrue(hasBlock(report, "DIRTY_WORKING_TREE"), "拦路项代号: " + report.blocked());
        assertTrue(report.blocked().stream()
                        .anyMatch(b -> b.message().contains("git stash")),
                "说明里必须给出怎么解决: " + report.blocked());
    }

    @Test
    @DisplayName("源文件被改动过（基线不符）时拒绝，并逐文件标出 baseOk=false")
    void preflightRejectsBaseMismatch() throws IOException {
        // 补丁里记的基线是 ORIGINAL，但源工程里已经是别的版本 —— 说明被人改过
        Fixture f = fixture(TaskStatus.SUCCEEDED, "public class Legacy { /* 人改过 */ }\n",
                ContentHash.sha256(ORIGINAL), false);

        WriteBackReport report = f.service().preflight(f.taskId());

        assertFalse(report.ready());
        assertTrue(hasBlock(report, "BASE_MISMATCH"), "拦路项代号: " + report.blocked());
        assertEquals(1, report.files().size());
        assertFalse(report.files().get(0).baseOk());
    }

    @Test
    @DisplayName("非源码/构建文件一律拒绝回写")
    void preflightRejectsDisallowedSuffix() throws IOException {
        Path projectRoot = tmp.resolve("p2");
        Path workspaceRoot = tmp.resolve("w2");
        Files.createDirectories(workspaceRoot.resolve("task-1/src"));
        Files.writeString(workspaceRoot.resolve("task-1/README.md"), "改过了\n", StandardCharsets.UTF_8);

        InMemoryTaskStore store = new InMemoryTaskStore();
        long id = store.createTask(projectRoot.toString(), "README.md", 21, null);
        store.updateTaskStatus(id, TaskStatus.SUCCEEDED, null);
        long nodeId = store.insertNode(id, "rewrite:README.md", NodeType.REWRITE, List.of(), 0);
        store.insertPatch(nodeId, "README.md", "diff", null);

        SourceWriteBackService service = new SourceWriteBackService(
                store, properties(workspaceRoot.toString()), new JsonCodec(), cleanGit());
        WriteBackReport report = service.preflight(id);

        assertFalse(report.ready());
        assertTrue(hasBlock(report, "FILE_NOT_ALLOWED"), "拦路项代号: " + report.blocked());
        assertTrue(report.files().isEmpty(), "被拒的文件不该出现在待写清单里");
    }

    @Test
    @DisplayName("没有任何产出时明确报出 NO_PATCH，而不是给一份空清单")
    void preflightRejectsWhenNoPatch() throws IOException {
        Path projectRoot = tmp.resolve("p3");
        Path workspaceRoot = tmp.resolve("w3");
        Files.createDirectories(projectRoot);
        Files.createDirectories(workspaceRoot.resolve("task-1"));

        InMemoryTaskStore store = new InMemoryTaskStore();
        long id = store.createTask(projectRoot.toString(), JAVA_FILE, 21, null);
        store.updateTaskStatus(id, TaskStatus.SUCCEEDED, null);

        SourceWriteBackService service = new SourceWriteBackService(
                store, properties(workspaceRoot.toString()), new JsonCodec(), cleanGit());
        WriteBackReport report = service.preflight(id);

        assertFalse(report.ready());
        assertTrue(hasBlock(report, "NO_PATCH"), "拦路项代号: " + report.blocked());
    }

    @Test
    @DisplayName("任务不存在时给出 TASK_NOT_FOUND 而不是抛异常")
    void preflightHandlesMissingTask() throws IOException {
        SourceWriteBackService service = new SourceWriteBackService(
                new InMemoryTaskStore(), properties(tmp.resolve("w4").toString()), new JsonCodec(), cleanGit());

        WriteBackReport report = service.preflight(999L);

        assertFalse(report.ready());
        assertTrue(hasBlock(report, "TASK_NOT_FOUND"));
    }

    // ------------------------------------------------------------------
    // 预检：放行
    // ------------------------------------------------------------------

    @Test
    @DisplayName("全部通过时给出待写清单、建议提交信息，且不写盘（appliedAt 为 null）")
    void preflightPassesAndWritesNothing() throws IOException {
        Fixture f = fixture(TaskStatus.SUCCEEDED, ORIGINAL, ContentHash.sha256(ORIGINAL), false);

        WriteBackReport report = f.service().preflight(f.taskId());

        assertTrue(report.ready(), "拦路项: " + report.blocked());
        assertEquals(1, report.files().size());
        WriteBackReport.FileEntry entry = report.files().get(0);
        assertEquals(JAVA_FILE, entry.filePath());
        assertTrue(entry.baseOk());
        assertEquals(ContentHash.sha256(REWRITTEN), entry.sha256(), "清单里的哈希应是「将要写入的内容」");
        assertNotNull(report.suggestedCommitMessage());
        assertTrue(report.suggestedCommitMessage().contains("回写单测"), "任务名应进建议提交信息");
        assertNull(report.appliedAt(), "预检不得写盘");
        assertEquals(ORIGINAL, Files.readString(f.sourceFile()), "预检不得改动源文件");
    }

    @Test
    @DisplayName("回退过的文件按「最早那条补丁」取基线，不会被误判成被人动过")
    void baselineComesFromEarliestPatch() throws IOException {
        Fixture f = fixture(TaskStatus.SUCCEEDED, ORIGINAL, ContentHash.sha256(ORIGINAL), true);

        WriteBackReport report = f.service().preflight(f.taskId());

        assertTrue(report.ready(), "拦路项: " + report.blocked());
        assertEquals(1, report.files().size(), "同一文件的多条补丁应合成一个待写项");
        assertTrue(report.files().get(0).baseOk());
    }

    @Test
    @DisplayName("目录不是 git 工作区时放行，但留下「无法用 git 撤销」的提示")
    void nonGitDirectoryPassesWithWarning() throws IOException {
        Fixture f = fixture(TaskStatus.SUCCEEDED, ORIGINAL, ContentHash.sha256(ORIGINAL), false);
        SourceWriteBackService service = new SourceWriteBackService(
                f.store(), properties(tmp.resolve("workspaces").toString()), new JsonCodec(), notGit());

        WriteBackReport report = service.preflight(f.taskId());

        assertTrue(report.ready(), "非 git 目录是警告不是拦路项: " + report.blocked());
        assertFalse(report.warnings().isEmpty());
        assertTrue(report.warnings().get(0).contains("版本控制"));
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    @Test
    @DisplayName("回写成功：内容落盘、原件进备份、审计留一行")
    void applyCopiesBacksUpAndRecordsAudit() throws IOException {
        Fixture f = fixture(TaskStatus.SUCCEEDED, ORIGINAL, ContentHash.sha256(ORIGINAL), false);

        WriteBackReport report = f.service().apply(f.taskId());

        assertTrue(report.applied(), "拦路项: " + report.blocked());
        assertNotNull(report.appliedAt());
        // 1) 源文件已是改写后内容
        assertEquals(REWRITTEN, Files.readString(f.sourceFile()));
        // 2) 备份目录里保留着改写前的那一份，且相对路径一致（回滚 = 整棵拷回去）
        Path backup = Path.of(report.backupDir()).resolve(JAVA_FILE);
        assertTrue(Files.isRegularFile(backup), "备份文件不存在: " + backup);
        assertEquals(ORIGINAL, Files.readString(backup));
        // 3) 审计留痕，且哈希是「写回后」的
        var audits = f.store().findWriteBacks(f.taskId());
        assertEquals(1, audits.size());
        assertEquals(1, audits.get(0).fileCount());
        assertEquals(JAVA_FILE, audits.get(0).files().get(0).filePath());
        assertEquals(ContentHash.sha256(REWRITTEN), audits.get(0).files().get(0).sha256());
        assertEquals(f.projectRoot().toString(), audits.get(0).projectRoot());
    }

    @Test
    @DisplayName("执行路径自己重跑一遍预检：拦路项存在时一个字节都不写")
    void applyWritesNothingWhenBlocked() throws IOException {
        Fixture f = fixture(TaskStatus.SUCCEEDED, ORIGINAL, ContentHash.sha256(ORIGINAL), false);
        SourceWriteBackService service = new SourceWriteBackService(
                f.store(), properties(tmp.resolve("workspaces").toString()), new JsonCodec(), dirtyGit());

        WriteBackReport report = service.apply(f.taskId());

        assertFalse(report.applied());
        assertTrue(hasBlock(report, "DIRTY_WORKING_TREE"));
        assertEquals(ORIGINAL, Files.readString(f.sourceFile()), "拒绝时不得改动源文件");
        assertTrue(f.store().findWriteBacks(f.taskId()).isEmpty(), "拒绝时不得留下审计行");
        assertFalse(Files.exists(Path.of(report.backupDir())), "拒绝时不该建出备份目录");
    }

    @Test
    @DisplayName("latestWriteBack 返回最近一次记录，未回写过则为空")
    void latestWriteBackReflectsAudit() throws IOException {
        Fixture f = fixture(TaskStatus.SUCCEEDED, ORIGINAL, ContentHash.sha256(ORIGINAL), false);

        assertTrue(f.service().latestWriteBack(f.taskId()).isEmpty());
        f.service().apply(f.taskId());
        var latest = f.service().latestWriteBack(f.taskId());
        assertTrue(latest.isPresent());
        assertEquals(1, latest.get().fileCount());
    }

    @Test
    @DisplayName("同任务重复回写不再依赖检查：第二次因基线变更而拒绝（源文件已是改写后内容）")
    void secondApplyIsRejectedByBaseline() throws IOException {
        Fixture f = fixture(TaskStatus.SUCCEEDED, ORIGINAL, ContentHash.sha256(ORIGINAL), false);

        assertTrue(f.service().apply(f.taskId()).applied());

        // 源文件现在等于第 1 轮产出，而补丁基线仍是原始内容 —— 说明「源工程与我改写时看到的不一致」，
        // 这正是需要人介入的信号（可能是同一条改已经被人手工落过）
        WriteBackReport second = f.service().apply(f.taskId());
        assertFalse(second.applied());
        assertTrue(hasBlock(second, "BASE_MISMATCH"), "拦路项代号: " + second.blocked());
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
