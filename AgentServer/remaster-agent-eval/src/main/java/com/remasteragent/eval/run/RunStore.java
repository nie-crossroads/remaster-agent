package com.remasteragent.eval.run;

import com.fasterxml.jackson.core.type.TypeReference;
import com.remasteragent.eval.util.EvalJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 评测产物的落盘与回读。
 *
 * <p>目录形如：
 * <pre>
 * eval-results/
 * └── 20260916-1630/
 *     ├── manifest.txt   # 这次跑的命令行、时间、用例数
 *     ├── runs.json      # 逐条原始结果（含 taskId）
 *     ├── report.md      # 汇总报告（B3）
 *     └── report.html
 * </pre>
 *
 * <h2>为什么 {@code runs.json} 必须留着 {@code taskId}</h2>
 * <p>报告里任何一个数字都要能顺着 {@code taskId} 回到
 * {@code trace_span} / {@code llm_call} / {@code patch} 去复盘。
 * 只存汇总数的报告是不可质疑的 —— 而不可质疑的报告在面试里是负资产。
 *
 * <p>更要紧的是：把「跑」与「渲染」拆成两步落盘（{@code run} 写、
 * {@code report} 读），意味着<b>换报告模板不用重跑、不用重新烧 token</b>。
 * 这是这一层最重要的性质。
 */
public final class RunStore {

    private static final DateTimeFormatter RUN_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final TypeReference<List<EvalRun>> RUN_LIST = new TypeReference<>() {
    };

    private RunStore() {
    }

    /** 产物根目录（不入库，见 .gitignore）。 */
    public static Path resultsRoot(Path repoRoot) {
        return repoRoot.resolve("eval-results");
    }

    public static String newRunId() {
        return LocalDateTime.now().format(RUN_ID_FORMAT);
    }

    /** 新建并返回本次运行的目录。 */
    public static Path createRunDir(Path repoRoot, String runId) {
        Path dir = resultsRoot(repoRoot).resolve(runId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("创建结果目录失败: " + dir, e);
        }
        return dir;
    }

    /** 写逐条结果。 */
    public static Path writeRuns(Path runDir, List<EvalRun> runs) {
        return writeText(runDir.resolve("runs.json"), EvalJson.toJson(runs));
    }

    /** 回读逐条结果（{@code collect} / {@code report} 用）。 */
    public static List<EvalRun> readRuns(Path runsJson) {
        try {
            String text = Files.readString(runsJson, StandardCharsets.UTF_8);
            return EvalJson.read(text, RUN_LIST);
        } catch (IOException e) {
            throw new UncheckedIOException("读取评测结果失败: " + runsJson, e);
        }
    }

    /** 找出最近一次运行的 runs.json —— 让 {@code report} 可以省掉 --run 参数。 */
    public static Path latestRunsJson(Path repoRoot) {
        Path root = resultsRoot(repoRoot);
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("还没有任何评测结果: " + root);
        }
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isDirectory)
                    .sorted(java.util.Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .map(p -> p.resolve("runs.json"))
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("结果目录下没有 runs.json: " + root));
        } catch (IOException e) {
            throw new UncheckedIOException("扫描结果目录失败: " + root, e);
        }
    }

    public static Path writeText(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写文件失败: " + file, e);
        }
        return file;
    }
}
