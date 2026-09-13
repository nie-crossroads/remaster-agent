package com.remasteragent.web.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输入校验的单测 —— 这一组是<b>安全回归测试</b>，不是普通的功能测试。
 *
 * <p>这个接口会让 Worker 读写本机文件系统上的路径。校验漏一条，服务就变成
 * 「任意目录的任意 .java 文件读写入口」，而且是静默的：功能一切正常，
 * 只是有人能把 {@code entryFile} 填成 {@code ../../.ssh/known_hosts} 之类的东西。
 * 这类缺陷不会在开发时暴露，只会在被利用时暴露，所以必须逐条钉死。
 *
 * <p>每个用例对应一条具体规则，失败时能直接看出是哪一条被改坏了。
 */
class ProjectPathValidatorTest {

    @TempDir
    Path tmp;

    private Path projectRoot;

    @BeforeEach
    void setUp() throws IOException {
        projectRoot = tmp.resolve("legacy-demo");
        Files.createDirectories(projectRoot.resolve("src/main/java/com/example"));
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.writeString(entry(), "package com.example;\npublic class Demo {}\n", StandardCharsets.UTF_8);
    }

    private Path entry() {
        return projectRoot.resolve("src/main/java/com/example/Demo.java");
    }

    // ------------------------------------------------------------------
    // 正常路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("合法输入：返回规范化的绝对根目录 + 正斜杠相对路径")
    void validInput() {
        ProjectPathValidator.ResolvedInput input =
                ProjectPathValidator.validate(projectRoot.toString(), "src/main/java/com/example/Demo.java");

        assertEquals(projectRoot.toAbsolutePath().normalize(), input.projectRoot());
        assertEquals("src/main/java/com/example/Demo.java", input.entryFile());
    }

    @Test
    @DisplayName("相对形式的 projectRoot 会被规范化为绝对路径")
    void relativeProjectRootIsNormalized() {
        ProjectPathValidator.ResolvedInput input =
                ProjectPathValidator.validate(projectRoot.toString() + "/./sub/..", "src/main/java/com/example/Demo.java");

        assertTrue(input.projectRoot().isAbsolute());
        assertEquals(projectRoot.toAbsolutePath().normalize(), input.projectRoot());
    }

    @Test
    @DisplayName("含 ./ 的 entryFile 会被规范化，最终存成正斜杠形式")
    void entryFileIsNormalizedToOneForm() {
        ProjectPathValidator.ResolvedInput input = ProjectPathValidator.validate(
                projectRoot.toString(), "./src/main/java/com/example/./Demo.java");

        assertEquals("src/main/java/com/example/Demo.java", input.entryFile(),
                "入库前必须归一化，否则同一个文件会有多种字符串表示，去重与比对都会出错");
    }

    // ------------------------------------------------------------------
    // 目录穿越 —— 最关键的一条
    // ------------------------------------------------------------------

    @Test
    @DisplayName("安全边界：entryFile 用 ../ 逃出工程目录必须被拒绝")
    void pathTraversalIsRejected() {
        // 真的在外面放一个 .java 文件：这样「被拒绝」只可能是因为触发了穿越防护，
        // 而不可能是因为文件不存在。校验顺序哪天被改动，这条测试也不会假绿。
        Path outside = tmp.resolve("outside.java");
        writeJavaFile(outside);
        assertTrue(Files.isRegularFile(outside), "夹具必须真的落在 projectRoot 之外");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                ProjectPathValidator.validate(projectRoot.toString(), "../outside.java"));

        assertTrue(error.getMessage().contains("越出"), error.getMessage());
    }

    @Test
    @DisplayName("安全边界：绕一层的 ../ 同样被拒绝（不能只挡最浅的一层）")
    void nestedPathTraversalIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                ProjectPathValidator.validate(projectRoot.toString(),
                        "src/main/java/com/example/../../../../../../tmp/x.java"));

        assertTrue(error.getMessage().contains("越出"), error.getMessage());
    }

    @Test
    @DisplayName("安全边界：绝对路径形式的 entryFile 直接拒绝")
    void absoluteEntryFileIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                ProjectPathValidator.validate(projectRoot.toString(), entry().toAbsolutePath().toString()));

        assertTrue(error.getMessage().contains("相对"), error.getMessage());
    }

    // ------------------------------------------------------------------
    // projectRoot 检查
    // ------------------------------------------------------------------

    @Test
    @DisplayName("projectRoot 不存在：拒绝")
    void missingProjectRootIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                ProjectPathValidator.validate(tmp.resolve("nope").toString(), "A.java"));

        assertTrue(error.getMessage().contains("已存在的目录"), error.getMessage());
    }

    @Test
    @DisplayName("projectRoot 是文件而不是目录：拒绝")
    void projectRootMustBeDirectory() throws IOException {
        Path file = tmp.resolve("a-file.txt");
        Files.writeString(file, "x", StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () ->
                ProjectPathValidator.validate(file.toString(), "A.java"));
    }

    @Test
    @DisplayName("projectRoot 下没有 pom.xml：拒绝（沙箱没法构建它）")
    void projectRootWithoutPomIsRejected() throws IOException {
        Path noPom = tmp.resolve("no-pom");
        Files.createDirectories(noPom);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                ProjectPathValidator.validate(noPom.toString(), "A.java"));

        assertTrue(error.getMessage().contains("pom.xml"), error.getMessage());
    }

    // ------------------------------------------------------------------
    // entryFile 检查
    // ------------------------------------------------------------------

    @Test
    @DisplayName("entryFile 不存在：拒绝")
    void missingEntryFileIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                ProjectPathValidator.validate(projectRoot.toString(), "src/Missing.java"));

        assertTrue(error.getMessage().contains("不存在"), error.getMessage());
    }

    @Test
    @DisplayName("entryFile 不是 .java：拒绝")
    void nonJavaEntryFileIsRejected() throws IOException {
        Files.writeString(projectRoot.resolve("README.md"), "# hi", StandardCharsets.UTF_8);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                ProjectPathValidator.validate(projectRoot.toString(), "README.md"));

        assertTrue(error.getMessage().contains(".java"), error.getMessage());
    }

    @Test
    @DisplayName("entryFile 指向目录：拒绝")
    void directoryEntryFileIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                ProjectPathValidator.validate(projectRoot.toString(), "src/main/java"));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private void writeJavaFile(Path path) {
        try {
            Files.writeString(path, "package a;\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("准备测试夹具失败: " + path, e);
        }
    }
}
