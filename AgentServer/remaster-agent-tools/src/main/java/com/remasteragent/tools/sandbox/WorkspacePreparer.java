package com.remasteragent.tools.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/**
 * 沙箱工作目录准备：把被测工程复制一份出来再改。
 *
 * <p><b>为什么必须先复制。</b>模型改代码的失败方式是无限的 —— 语法错了、引用了不存在的类、
 * 把整个文件写没了。如果在原仓库上就地修改，一次失败就污染了工作区，
 * 而且 rollback 要靠 git checkout，稍微复杂一点的场景就回不去了。
 * 复制到独立目录之后，「回滚」这个动作变得平凡：删掉重来就行。
 *
 * <p>排除项是经验性的：{@code target} 是构建产物（复制它既慢又可能带上旧的 class 文件
 * 让结果失真），{@code .git} 动辄几十上百 MB 且沙箱里用不到。
 */
public final class WorkspacePreparer {

    private static final Logger log = LoggerFactory.getLogger(WorkspacePreparer.class);

    /** 不复制进沙箱的目录名。 */
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "target", "build", "out", ".git", ".idea", ".vscode", "node_modules", ".mvn");

    /** 不复制进沙箱的文件后缀。 */
    private static final Set<String> EXCLUDED_SUFFIXES = Set.of(".class", ".jar", ".war", ".log");

    private WorkspacePreparer() {
    }

    /**
     * 把一个工程复制到沙箱工作目录。
     *
     * <p>目标目录已存在时会被清空重建 —— 保证每次执行都从同一份干净的基线开始，
     * 不会因为上一轮的残留产出「看起来通过了」的假结果。
     *
     * @param sourceProjectRoot 被测工程根目录
     * @param sandboxWorkspace  目标沙箱工作目录
     * @return 沙箱工作目录
     */
    public static Path prepare(Path sourceProjectRoot, Path sandboxWorkspace) {
        if (!Files.isDirectory(sourceProjectRoot)) {
            throw new IllegalArgumentException("被测工程目录不存在: " + sourceProjectRoot);
        }

        try {
            if (Files.exists(sandboxWorkspace)) {
                deleteRecursively(sandboxWorkspace);
            }
            Files.createDirectories(sandboxWorkspace);
            copyTree(sourceProjectRoot, sandboxWorkspace);
            log.info("沙箱工作目录已就绪: {} <- {}", sandboxWorkspace, sourceProjectRoot);
            return sandboxWorkspace;
        } catch (IOException e) {
            throw new UncheckedIOException("准备沙箱工作目录失败", e);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                if (!relative.toString().isEmpty() && EXCLUDED_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                if (EXCLUDED_SUFFIXES.stream().anyMatch(name::endsWith)) {
                    return FileVisitResult.CONTINUE;
                }
                Path destination = target.resolve(source.relativize(file));
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 递归删除。用于清理上一次的沙箱目录。 */
    public static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
