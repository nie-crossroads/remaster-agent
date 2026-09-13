package com.remasteragent.web.api;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 创建任务时的输入校验 —— <b>这是本服务的安全边界</b>。
 *
 * <h2>为什么这段不能靠注解</h2>
 * <p>{@code @NotBlank} 只能挡住「没传」。真正的问题是「传了一个指向 /etc 的路径」：
 * 这个接口会让 Worker 去读写本机文件系统上的工程目录，所以路径必须在服务端
 * 自己解析、自己确认。少了这几条检查，整个服务就是一个任意文件读写入口 ——
 * 而这在本地开发时完全看不出来。
 *
 * <h2>为什么从 Controller 里拎出来</h2>
 * <p>安全相关的分支（目录穿越、绝对路径、非 .java 目标）必须能被逐条单测。
 * 挂在 Controller 的私有方法里就只能通过起 MockMvc 才能验证，成本高到没人会写 ——
 * 而这类代码恰恰是最需要回归测试的：它平时不报错，一出错就是安全事故。
 */
public final class ProjectPathValidator {

    /** 沙箱用 Maven 构建被测工程，没有 pom.xml 的目录根本跑不起来，不如在入口就挡掉。 */
    private static final String POM_FILE = "pom.xml";

    private static final String JAVA_SUFFIX = ".java";

    /**
     * 校验后的输入。
     *
     * @param projectRoot 规范化后的绝对路径
     * @param entryFile   规范化后的相对路径，<b>统一为正斜杠</b>
     */
    public record ResolvedInput(Path projectRoot, String entryFile) {
    }

    private ProjectPathValidator() {
    }

    /**
     * 校验工程根目录与目标文件。
     *
     * @throws IllegalArgumentException 任一检查不通过；消息面向使用者，会直接返回给前端
     */
    public static ResolvedInput validate(String rawProjectRoot, String rawEntryFile) {
        Path projectRoot = resolveProjectRoot(rawProjectRoot);
        String entryFile = resolveEntryFile(projectRoot, rawEntryFile);
        return new ResolvedInput(projectRoot, entryFile);
    }

    private static Path resolveProjectRoot(String raw) {
        Path root;
        try {
            root = Paths.get(raw).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("projectRoot 不是合法路径: " + raw);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("projectRoot 不是已存在的目录: " + root);
        }
        if (!Files.isRegularFile(root.resolve(POM_FILE))) {
            throw new IllegalArgumentException(
                    "projectRoot 下找不到 " + POM_FILE + "，沙箱无法构建该工程: " + root);
        }
        return root;
    }

    private static String resolveEntryFile(Path projectRoot, String raw) {
        Path entry;
        try {
            entry = Paths.get(raw);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("entryFile 不是合法路径: " + raw);
        }
        if (entry.isAbsolute()) {
            throw new IllegalArgumentException("entryFile 必须是相对 projectRoot 的路径: " + raw);
        }

        Path resolved = projectRoot.resolve(entry).normalize();
        // 目录穿越防护：归一化掉 .. 之后仍必须落在 projectRoot 之内。
        // 这一条是真正的安全边界 —— 少了它，entryFile 填 ../../xxx 就能读写工程之外的路径。
        if (!resolved.startsWith(projectRoot)) {
            throw new IllegalArgumentException("entryFile 越出了 projectRoot: " + raw);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException("entryFile 不存在或不是文件: " + raw);
        }
        if (!raw.endsWith(JAVA_SUFFIX)) {
            throw new IllegalArgumentException("entryFile 必须是 " + JAVA_SUFFIX + " 文件: " + raw);
        }
        // 统一存成正斜杠形式：这个路径会被用于沙箱内定位、diff 展示、前端显示与 URL 拼接，
        // Windows 的反斜杠在这几处都是麻烦（转义、比较、路径分隔符语义不同）
        return projectRoot.relativize(resolved).toString().replace('\\', '/');
    }
}
