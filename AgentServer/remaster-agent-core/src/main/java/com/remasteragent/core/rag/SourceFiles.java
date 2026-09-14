package com.remasteragent.core.rag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 源码文件枚举 —— 索引（{@link CodeIndexer}）与规划（{@code PlanNode}）共用同一套「什么算源码」的规则。
 *
 * <p>抽出来是为了让两处**永远一致**：如果索引时排除了 {@code target/} 而规划时没排除，
 * PLAN 就会规划一个索引里根本不存在的文件，检索与规划对不上。规则只有一处，就不会漂移。
 */
public final class SourceFiles {

    /** 不是源码、或属于生成物的目录 —— 都不该参与索引与规划。 */
    private static final Set<String> IGNORED_DIRS = Set.of(
            "target", "build", "out", "node_modules", ".git", ".idea", ".gradle", ".remaster-workspaces");

    private SourceFiles() {
    }

    /**
     * 列出工程内的 Java 源文件（升序，最多 {@code max} 个）。
     *
     * <p>排序 + 截断而不是随机截断：大工程里「先看到哪批文件」会影响索引与规划的确定性 ——
     * 同样的输入应该得到同样的结果。
     */
    public static List<Path> listJavaFiles(Path root, int max) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !inIgnoredDir(root, path))
                    .sorted()
                    .limit(max)
                    .toList();
        }
    }

    /** 相对工程根的路径，统一用 {@code /} 分隔（跨平台一致，且与 DB 里存的路径格式一致）。 */
    public static String relativePath(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    public static boolean inIgnoredDir(Path root, Path file) {
        for (Path segment : root.relativize(file)) {
            if (IGNORED_DIRS.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }
}
