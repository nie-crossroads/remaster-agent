package com.remasteragent.eval.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 找到可用的 Maven 可执行文件。
 *
 * <p>存在的理由是一个很具体的坑：Windows 上根本没有叫 {@code mvn} 的可执行文件，
 * 只有 {@code mvn.cmd}。而 Git Bash 里调 {@code mvn} 又会被路径转换搞出
 * {@code ClassNotFoundException}（本项目已经踩过）。所以这个工具类把
 * 「怎么调 Maven」这件事收在一处，并把解析结果打进日志 ——
 * 否则评测结论会依赖「你当时是在哪个 shell 里敲的命令」。
 *
 * <p>解析顺序（先到先用）：
 * <ol>
 *   <li>命令行显式指定的 {@code --mvn=...}</li>
 *   <li>环境变量 {@code REMASTER_MAVEN}</li>
 *   <li>{@code MAVEN_HOME}/bin/mvn[.cmd]</li>
 *   <li>直接交给 PATH 去找 {@code mvn}</li>
 * </ol>
 */
public final class MavenExecutable {

    private MavenExecutable() {
    }

    /** 解析出可执行文件路径；找不到时退回 {@code "mvn"} 交给 PATH。 */
    public static String resolve(String explicit, java.util.Map<String, String> env) {
        List<String> candidates = new ArrayList<>();
        if (explicit != null && !explicit.isBlank()) {
            candidates.add(explicit);
        }
        addIfPresent(candidates, env.get("REMASTER_MAVEN"));
        String mavenHome = env.get("MAVEN_HOME");
        if (mavenHome != null && !mavenHome.isBlank()) {
            Path bin = Path.of(mavenHome, "bin");
            candidates.add(bin.resolve(binaryName()).toString());
        }
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path.toAbsolutePath().toString();
            }
        }
        return candidates.isEmpty() ? "mvn" : candidates.get(candidates.size() - 1);
    }

    /** 当前平台上的 mvn 可执行文件名。 */
    public static String binaryName() {
        return isWindows() ? "mvn.cmd" : "mvn";
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void addIfPresent(List<String> candidates, String value) {
        Optional.ofNullable(value).filter(v -> !v.isBlank()).ifPresent(candidates::add);
    }
}
