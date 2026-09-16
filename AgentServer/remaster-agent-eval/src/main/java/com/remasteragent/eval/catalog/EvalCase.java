package com.remasteragent.eval.catalog;

import java.nio.file.Path;
import java.util.List;

/**
 * 一条评测用例 —— 一个迁移任务。
 *
 * <p>粒度是「工程 × 目标文件」而不是「一个工程」：任务模型
 * （{@code CreateTaskRequest}）就只有 {@code projectRoot} + {@code entryFile} + {@code targetJdk}
 * 三个字段，而 VERIFY 跑的是<b>整个工程</b>的 {@code mvn test}。所以一个工程里的多个目标文件
 * 会各自成为一个任务 —— 这正是本次评测「24 个任务 / 6 个工程」的由来。
 *
 * @param id        唯一标识，报告里作为行主键
 * @param project   工程根目录，相对仓库根
 * @param entryFile 目标文件，相对 {@code project}
 * @param targetJdk 目标 JDK
 * @param expect    期望结局
 * @param ready     样本是否已就绪（false = 计划中，runner 会跳过）
 * @param title     这条用例在考什么
 * @param patterns   覆盖的遗留模式标签，报告按它分组
 * @param notes     样本设计上的关键约束
 */
public record EvalCase(
        String id,
        String project,
        String entryFile,
        int targetJdk,
        ExpectedOutcome expect,
        boolean ready,
        String title,
        List<String> patterns,
        String notes
) {

    /** 期望结局。刻意只有两个值：任务级只有成功/失败两种「应有的样子」。 */
    public enum ExpectedOutcome {
        SUCCEEDED,
        FAILED
    }

    public EvalCase {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("用例 id 不能为空");
        }
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("用例 " + id + " 缺 project");
        }
        if (entryFile == null || entryFile.isBlank()) {
            throw new IllegalArgumentException("用例 " + id + " 缺 entryFile");
        }
        // 与 CreateTaskRequest 的约束保持一致：非 .java 会被 API 直接 400 拒掉，
        // 在这里先拦下来，报错信息才指得到 catalog 而不是「提交时莫名 400」。
        if (!entryFile.endsWith(".java")) {
            throw new IllegalArgumentException("用例 " + id + " 的 entryFile 必须以 .java 结尾: " + entryFile);
        }
        if (targetJdk < 17) {
            throw new IllegalArgumentException("用例 " + id + " 的 targetJdk 至少为 17: " + targetJdk);
        }
        if (patterns == null) {
            patterns = List.of();
        }
    }

    /** 负样本：期望失败。它的作用是给成功率一个有意义的分母。 */
    public boolean isNegative() {
        return expect == ExpectedOutcome.FAILED;
    }

    /** 工程根目录的绝对路径。 */
    public Path projectPath(Path repoRoot) {
        return repoRoot.resolve(project).normalize();
    }

    /** 目标文件的绝对路径。 */
    public Path entryFilePath(Path repoRoot) {
        return projectPath(repoRoot).resolve(entryFile).normalize();
    }
}
