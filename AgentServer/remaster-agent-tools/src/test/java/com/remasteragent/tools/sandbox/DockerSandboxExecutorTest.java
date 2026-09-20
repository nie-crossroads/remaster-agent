package com.remasteragent.tools.sandbox;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 Docker 沙箱的两条契约：
 * <ol>
 *   <li><b>隔离标志必须齐全且不可关</b>——这是「真隔离」和 local 的本质区别，必须在单测里钉死；</li>
 *   <li><b>逻辑 mvn 必须被翻译成离线调用</b>——网络已断，不离线就跑不起来。</li>
 * </ol>
 *
 * <p>命令拼接是纯函数（不依赖 docker 是否安装），所以这部分测试在任何环境都能跑。
 * 「无 docker 时 execute 优雅降级」那条用 {@code assumeFalse(dockerAvailable())} 守卫，
 * 只在确实没装 docker 的开发机上执行，避免在有 docker 的机器上变成环境相关用例。
 */
class DockerSandboxExecutorTest {

    private static final Path FAKE_WORKSPACE = Path.of("/srv/remaster/task-1");
    private static final Path FAKE_REPO = Path.of("/opt/maven/repo");
    private static final Path FAKE_SETTINGS = Path.of("/opt/maven/settings.xml");

    /** 构造一个 docker 模式的配置（mount 路径用假值，测试只校验命令字符串，不真跑 docker）。 */
    private DockerSandboxExecutor executor() {
        SandboxProperties props = new SandboxProperties(
                "docker", 1024, 600, 40_000,
                "/tmp/fake-maven-home", FAKE_SETTINGS.toString(), FAKE_REPO.toString(),
                null);
        return new DockerSandboxExecutor(props, 40_000);
    }

    @Test
    @DisplayName("docker run 携带完整隔离标志")
    void commandCarriesIsolationFlags() {
        DockerSandboxExecutor ex = executor();
        List<String> cmd = ex.buildDockerCommand(
                SandboxRequest.builder(FAKE_WORKSPACE, List.of("mvn", "test")).build(),
                FAKE_WORKSPACE, Path.of("/tmp/cid"));

        assertTrue(cmd.contains("docker"));
        assertTrue(cmd.contains("--network") && cmd.contains("none"), "必须断网");
        assertTrue(cmd.contains("--read-only"), "根文件系统必须只读");
        assertTrue(cmd.contains("--cap-drop") && cmd.contains("ALL"), "必须丢弃全部 Linux capabilities");
        assertTrue(cmd.contains("--security-opt") && cmd.contains("no-new-privileges"));
        assertTrue(cmd.contains("--pids-limit") && cmd.contains("256"), "必须限制进程数");
        assertTrue(cmd.contains("--memory") && cmd.contains("1024m"), "必须限制内存");
        assertTrue(cmd.contains("--memory-swap") && cmd.contains("1024m"), "必须禁 swap");
        assertTrue(cmd.contains("--user") && cmd.contains("1000:1000"), "必须非 root 运行");
        assertTrue(cmd.contains("maven:3.9-eclipse-temurin-21"), "默认镜像");
    }

    @Test
    @DisplayName("逻辑 mvn 命令被翻译成容器内离线调用")
    void logicalMvnIsTranslated() {
        DockerSandboxExecutor ex = executor();
        List<String> translated = ex.decorateForContainer(List.of("mvn", "test"));

        assertEquals(List.of("mvn", "-B", "-o", "-s", "/m2/settings.xml",
                "-Dmaven.repo.local=/m2/repository", "test"), translated);
        assertTrue(translated.contains("-o"), "网络已断，必须离线");
        assertTrue(translated.contains("-Dmaven.repo.local=/m2/repository"), "依赖来自只读挂载的仓库");
        assertTrue(translated.contains("-s"), "必须指定容器内 settings");
    }

    @Test
    @DisplayName("非 mvn 命令原样透传")
    void nonMvnPassesThrough() {
        DockerSandboxExecutor ex = executor();
        assertEquals(List.of("java", "-version"), ex.decorateForContainer(List.of("java", "-version")));
    }

    @Test
    @DisplayName("工作目录可写、本地仓库与 settings 只读挂载进容器")
    void mountsAreCorrect() {
        DockerSandboxExecutor ex = executor();
        // 执行器内部会把宿主侧仓库/settings 路径规范化为绝对路径，断言须与之对齐（Windows 上尤甚）
        Path repoAbs = FAKE_REPO.toAbsolutePath().normalize();
        Path settingsAbs = FAKE_SETTINGS.toAbsolutePath().normalize();
        List<String> cmd = ex.buildDockerCommand(
                SandboxRequest.builder(FAKE_WORKSPACE, List.of("mvn", "test")).build(),
                FAKE_WORKSPACE, Path.of("/tmp/cid"));

        assertTrue(cmd.contains(FAKE_WORKSPACE + ":/workspace:rw"), "工作目录须可写（编译要落 target）");
        assertTrue(cmd.contains(repoAbs + ":/m2/repository:ro"), "本地仓库须只读（防污染宿主仓库）");
        assertTrue(cmd.contains(settingsAbs + ":/m2/settings.xml:ro"), "settings 须只读");
    }

    @Test
    @DisplayName("本机无 docker 时 execute 优雅失败而非抛异常")
    void executeDegradesGracefullyWhenDockerMissing() {
        Assumptions.assumeFalse(dockerAvailable(), "仅在本机无 docker 时验证优雅降级路径");
        DockerSandboxExecutor ex = executor();
        SandboxResult result = ex.execute(
                SandboxRequest.builder(Path.of("."), List.of("mvn", "test"))
                        .timeout(Duration.ofSeconds(5))
                        .build());

        assertFalse(result.succeeded(), "没有 docker 不应成功");
        assertTrue(result.exitCode() < 0 || result.stderr().contains("docker"),
                "应给出明确的 docker 不可用信息，而不是静默或崩溃");
    }

    private static boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "--version").start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
