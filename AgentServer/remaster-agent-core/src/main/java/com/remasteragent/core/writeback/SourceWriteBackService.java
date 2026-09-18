package com.remasteragent.core.writeback;

import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.PatchRecord;
import com.remasteragent.common.domain.SourceWriteBack;
import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.core.codec.JsonCodec;
import com.remasteragent.core.config.CoreProperties;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.tools.hash.ContentHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 变更回写：把沙箱里<b>已经验证过</b>的产出落回源工程。
 *
 * <h2>为什么需要一个显式动作，而不是任务成功就自动写</h2>
 * <p>本项目的源工程在整条链路里是<b>只读</b>的（索引、检索、复制源三处读路径之外没有任何写入口）。
 * 这不是疏漏，而是「模型改坏代码」与「人还需要原始版本」之间的安全边界的全部：
 * 沙箱的失败方式是无限的，一旦就地改，回滚就得靠 git checkout 那些说不清的场景。
 * 所以回写必须是<b>人点的</b>，且必须留下审计（备份目录 + 文件指纹）。
 *
 * <h2>回写的边界：只落本地工作区，不碰远端</h2>
 * <p>「回写到 GitHub」这句话要拆开看：源工程目录通常本身就是一份 git 工作区，
 * 写回本地文件即等同于写进仓库工作区；而 commit / push 是 git 的语义、是人的动作 ——
 * Agent 不该持有远端凭据，也不该替人决定什么进版本历史。本服务因此<b>只写文件</b>，
 * 最多给出建议的提交信息。
 *
 * <h2>内容来源：沙箱里的实际文件，不是重新拼 patch</h2>
 * <p>拷的是沙箱工作目录里那份真实文件（编译 + 单测都跑在它上面），而不是拿 {@code patch.diff}
 * 去 {@code git apply}。unified diff 对上下文漂移极敏感，源工程只要被动过一行就 apply 失败；
 * 而沙箱里的那份是「已经验证过的成品」，直接覆盖既可靠又可解释。
 *
 * <h2>预检不通过就整体拒绝</h2>
 * <p>没有「写一部分」这种中间态：一旦部分写入，源工程就处于「既不是改写前、也不是改写后」的
 * 第三状态，比两边都不做更糟 —— 人无从判断该回滚还是该继续。所以任何一项不过，
 * 都返回同一份报告并<b>一个字节都不写</b>。
 */
@Component
public class SourceWriteBackService {

    private static final Logger log = LoggerFactory.getLogger(SourceWriteBackService.class);

    /** 允许回写的文件后缀 —— 与改写能力对齐：Java 源码与 Maven 构建文件。 */
    private static final Set<String> ALLOWED_SUFFIXES = Set.of(".java", ".xml");

    private final TaskStore taskStore;
    private final CoreProperties properties;
    private final JsonCodec json;
    private final WorkingTreeInspector workingTree;

    /** 正在回写的任务 —— 防同一个人双击两次按钮造成并发写。 */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    /** 生产入口：工作区检查走真实 git。 */
    @Autowired
    public SourceWriteBackService(TaskStore taskStore, CoreProperties properties, JsonCodec json) {
        this(taskStore, properties, json, WorkingTreeInspector.git());
    }

    /** 单测入口：可注入工作区检查桩件，不依赖 git 二进制。 */
    public SourceWriteBackService(TaskStore taskStore, CoreProperties properties, JsonCodec json,
                                  WorkingTreeInspector workingTree) {
        this.taskStore = taskStore;
        this.properties = properties;
        this.json = json;
        this.workingTree = workingTree == null ? WorkingTreeInspector.NONE : workingTree;
    }

    // ------------------------------------------------------------------
    // 预检
    // ------------------------------------------------------------------

    /**
     * 只检查、不写盘。
     *
     * <p>它同时被 {@link #apply} 复用，所以不存在「预检通过 → 中间状态变了 → 直接写」的窗口：
     * 真正的写盘路径一定会重新走一遍全部检查。
     */
    public WriteBackReport preflight(long taskId) {
        List<WriteBackReport.Blocked> blocked = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        MigrationTask task = taskStore.findTask(taskId).orElse(null);
        if (task == null) {
            return new WriteBackReport(taskId, null, null, null, List.of(),
                    List.of(blocked("TASK_NOT_FOUND", "任务不存在: #" + taskId)), warnings, null, null);
        }

        Path projectRoot = Path.of(task.projectRoot()).toAbsolutePath().normalize();
        Path workspace = properties.workspaceRootPath().resolve("task-" + taskId);

        if (task.status() != TaskStatus.SUCCEEDED) {
            blocked.add(blocked("TASK_NOT_SUCCEEDED",
                    "任务当前状态是 " + task.status() + "，只有 SUCCEEDED 的任务才能回写 —— "
                            + "没有通过编译与单测的产出不该进源工程"));
        }
        if (!Files.isDirectory(workspace)) {
            blocked.add(blocked("WORKSPACE_GONE",
                    "沙箱工作目录已不存在（" + workspace + "）—— 通常是任务结束后超过了保留期被回收。"
                            + "重跑一次任务即可重新生成沙箱"));
        }
        if (!Files.isDirectory(projectRoot)) {
            blocked.add(blocked("PROJECT_ROOT_GONE", "源工程目录不存在: " + projectRoot));
        }

        // 工作区检查：脏工作区一律拒绝（理由见 WorkingTreeInspector）
        WorkingTreeInspector.Result tree = workingTree.inspect(projectRoot);
        if (tree.gitRepo() && !tree.clean()) {
            blocked.add(blocked("DIRTY_WORKING_TREE",
                    "源工程工作区不干净（" + tree.detail() + "）—— 请先 git commit 或 git stash，"
                            + "否则 AI 的改动会与你的手工修改混在一起，无法单独回滚"));
        } else if (!tree.gitRepo()) {
            warnings.add(tree.detail() + "（回写后无法用 git 撤销，请留意备份目录）");
        }

        List<WriteBackReport.FileEntry> files = planFiles(task, projectRoot, workspace, blocked);

        String backupDir = properties.workspaceRootPath()
                .resolve("backups").resolve("task-" + taskId).toString();
        return new WriteBackReport(taskId, projectRoot.toString(), workspace.toString(),
                backupDir, files, List.copyOf(blocked), List.copyOf(warnings),
                suggestCommitMessage(task, files), null);
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    /**
     * 执行回写：重新预检 → 备份 → 覆盖 → 落审计。
     *
     * <p>顺序上<b>先备份再覆盖</b>是硬要求：备份失败就直接返回，绝不在没有退路的情况下动源文件。
     */
    public WriteBackReport apply(long taskId) {
        if (!inFlight.add(taskId)) {
            return WriteBackReportGuard.busy(taskId);
        }
        try {
            WriteBackReport report = preflight(taskId);
            if (!report.ready()) {
                log.warn("任务 #{} 回写预检未通过，已拒绝执行: {}", taskId,
                        report.blocked().stream().map(WriteBackReport.Blocked::code).toList());
                return report;
            }

            Path projectRoot = Path.of(report.projectRoot());
            Path workspace = Path.of(report.workspace());
            Path backupDir = Path.of(report.backupDir());

            List<SourceWriteBack.AppliedFile> applied = new ArrayList<>(report.files().size());
            try {
                Files.createDirectories(backupDir);
                for (WriteBackReport.FileEntry entry : report.files()) {
                    Path source = projectRoot.resolve(entry.filePath());
                    Path fromSandbox = workspace.resolve(entry.filePath());

                    // 备份用「保留相对路径」的方式存：出事的回滚就是把备份目录整棵拷回去，
                    // 不需要把清单和路径拼回来
                    Path backupTarget = backupDir.resolve(entry.filePath());
                    Files.createDirectories(backupTarget.getParent());
                    Files.copy(source, backupTarget, StandardCopyOption.REPLACE_EXISTING);

                    Files.createDirectories(source.getParent());
                    Files.copy(fromSandbox, source, StandardCopyOption.REPLACE_EXISTING);
                    applied.add(new SourceWriteBack.AppliedFile(entry.filePath(),
                            Files.size(source), ContentHash.sha256(Files.readAllBytes(source))));
                }
            } catch (IOException e) {
                log.error("任务 #{} 回写失败，已中止: {}", taskId, e.getMessage());
                return WriteBackReportGuard.failed(report, "IO_FAILED",
                        "写回过程中出错，已中止：" + e.getMessage()
                                + "；源文件可用备份目录中的内容还原：" + backupDir);
            }

            taskStore.insertWriteBack(taskId, report.projectRoot(), backupDir.toString(),
                    json.write(applied), applied.size());
            log.info("任务 #{} 产出已回写到源工程: {} 个文件，备份在 {}",
                    taskId, applied.size(), backupDir);

            return new WriteBackReport(taskId, report.projectRoot(), report.workspace(),
                    backupDir.toString(),
                    report.files(), List.of(), report.warnings(),
                    report.suggestedCommitMessage(), Instant.now());
        } finally {
            inFlight.remove(taskId);
        }
    }

    // ------------------------------------------------------------------
    // 报告组装
    // ------------------------------------------------------------------

    /**
     * 列出待回写的文件，并逐文件校验基线。
     *
     * <h2>为什么基线取「每个文件最早那条 patch」</h2>
     * <p>回退重写会让同一个文件产生多条 patch，而每条 patch 的 {@code original_hash}
     * 是<b>当时工作目录里那一版</b>的指纹 —— 第 2 轮的 patch 记录的是「第 1 轮产出」的哈希，
     * 不是源工程的。只有最早那条 patch 的哈希对着的才是源工程里的原始内容，
     * 所以基线校验必须按文件取最早那条，否则每个发生过回退的文件都会被误判成「被人动过」。
     */
    private List<WriteBackReport.FileEntry> planFiles(MigrationTask task, Path projectRoot,
                                                      Path workspace,
                                                      List<WriteBackReport.Blocked> blocked) {
        List<PatchRecord> patches = taskStore.findPatches(task.id());
        if (patches.isEmpty()) {
            blocked.add(blocked("NO_PATCH",
                    "该任务没有产出任何改写 —— 没有东西可以回写（可能是规划为空或改写被跳过）"));
            return List.of();
        }

        // 每个文件取「最早那条 patch」作为基线（理由见方法说明）
        Map<String, PatchRecord> baselineByFile = new LinkedHashMap<>();
        for (PatchRecord patch : patches) {
            if (patch.filePath() == null || patch.filePath().isBlank()) {
                continue;
            }
            PatchRecord existing = baselineByFile.get(patch.filePath());
            if (existing == null || patch.id() < existing.id()) {
                baselineByFile.put(patch.filePath(), patch);
            }
        }
        // 目录序输出，报告读起来稳定（不依赖数据库返回顺序）
        List<String> filePaths = baselineByFile.keySet().stream().sorted(Comparator.naturalOrder()).toList();

        List<WriteBackReport.FileEntry> files = new ArrayList<>(filePaths.size());
        for (String filePath : filePaths) {
            if (!isAllowed(filePath)) {
                blocked.add(blocked("FILE_NOT_ALLOWED",
                        "拒绝回写非源码/构建文件：" + filePath));
                continue;
            }
            Path sandboxFile = workspace.resolve(filePath).normalize();
            if (!sandboxFile.startsWith(workspace) || !Files.isRegularFile(sandboxFile)) {
                blocked.add(blocked("SANDBOX_FILE_MISSING",
                        "沙箱里找不到该文件，无法确定要写入的内容：" + filePath));
                continue;
            }
            Path sourceFile = projectRoot.resolve(filePath).normalize();
            if (!sourceFile.startsWith(projectRoot) || !Files.isRegularFile(sourceFile)) {
                blocked.add(blocked("SOURCE_FILE_MISSING",
                        "源工程里找不到该文件（可能是新增文件，本项目不新建文件）：" + filePath));
                continue;
            }

            String expected = baselineByFile.get(filePath).originalHash();
            String actual = ContentHash.sha256(readString(sourceFile));
            boolean baseOk = expected == null || expected.equals(actual);
            if (!baseOk) {
                blocked.add(blocked("BASE_MISMATCH",
                        "源文件已被改动，与改写时的基线不一致：" + filePath
                                + " —— 请确认这份改还要不要，或先恢复该文件"));
            }
            files.add(new WriteBackReport.FileEntry(filePath,
                    sizeOf(sandboxFile), ContentHash.sha256(readString(sandboxFile)), baseOk));
        }
        return files;
    }

    private static boolean isAllowed(String filePath) {
        String lower = filePath.toLowerCase();
        return ALLOWED_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    /**
     * 建议的提交信息。
     *
     * <p>只是<b>建议</b>：写回止于工作区，提交与否是人决定的。给一段现成的信息不是替人做决定，
     * 而是省掉「回去翻任务详情、手动列出改了哪些文件」这一步 —— 本项目的立场一贯是
     * 「Agent 把不确定的部分交还给人，把确定的机械劳动做掉」。
     */
    private static String suggestCommitMessage(MigrationTask task, List<WriteBackReport.FileEntry> files) {
        String subject;
        if (task.name() != null && !task.name().isBlank()) {
            subject = "refactor: " + task.name();
        } else {
            Path root = Path.of(task.projectRoot());
            String project = root.getFileName() == null ? task.projectRoot() : root.getFileName().toString();
            subject = "refactor: 将 " + project + " 迁移到 JDK " + task.targetJdk();
        }
        return subject + "\n\n"
                + "由 RemasterAgent 任务 #" + task.id() + " 自动改写，共 " + files.size()
                + " 个文件；编译与单测已在沙箱内验证通过。\n"
                + "改写内容可对照任务详情页的补丁列表逐条 review。";
    }

    private static WriteBackReport empty(long taskId, String projectRoot, String workspace,
                                         WriteBackReport.Blocked blocked, List<String> warnings) {
        return new WriteBackReport(taskId, projectRoot, workspace, null,
                List.of(), List.of(blocked), warnings, null, null);
    }

    private static WriteBackReport.Blocked blocked(String code, String message) {
        return new WriteBackReport.Blocked(code, message);
    }

    private static String readString(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 读不出来 → 返回不可能与任何哈希相等的值，让基线校验判为不一致（保守方向）
            return null;
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }

    /** 报告的空壳构造与「忙/失败」分支 —— 放在这里是为了让 action 方法的控制流保持直白。 */
    private static final class WriteBackReportGuard {

        private WriteBackReportGuard() {
        }

        static WriteBackReport busy(long taskId) {
            return new WriteBackReport(taskId, null, null, null, List.of(),
                    List.of(new WriteBackReport.Blocked("BUSY", "该任务正在回写中，请稍候再试")),
                    List.of(), null, null);
        }

        static WriteBackReport failed(WriteBackReport report, String code, String message) {
            List<WriteBackReport.Blocked> blocked = new ArrayList<>(report.blocked());
            blocked.add(new WriteBackReport.Blocked(code, message));
            return new WriteBackReport(report.taskId(), report.projectRoot(), report.workspace(),
                    report.backupDir(), report.files(), List.copyOf(blocked), report.warnings(),
                    report.suggestedCommitMessage(), null);
        }
    }

    /** 供 Controller 判断「这个任务有没有回写过」，也用于详情页展示。 */
    public Optional<SourceWriteBack> latestWriteBack(long taskId) {
        List<SourceWriteBack> all = taskStore.findWriteBacks(taskId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }
}
