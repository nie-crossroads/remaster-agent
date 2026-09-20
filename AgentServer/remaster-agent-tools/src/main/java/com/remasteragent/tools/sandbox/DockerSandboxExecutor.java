package com.remasteragent.tools.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Docker 容器沙箱 —— 真·隔离地执行模型生成的代码。
 *
 * <h2>隔离来自哪里</h2>
 * <ul>
 *   <li><b>无网络</b>：{@code --network none}。模型生成的代码无法外联，也无法下载新依赖，
 *       所以依赖必须预先在挂载进来的本地仓库里（只读）。</li>
 *   <li><b>不可变根文件系统</b>：{@code --read-only} + {@code --tmpfs /tmp}。容器镜像层只读，
 *       只有工作目录与 /tmp 可写，恶意代码改不了系统。</li>
 *   <li><b>降权</b>：{@code --user} 非 root（默认 1000:1000）+ {@code --security-opt no-new-privileges}
 *       + {@code --cap-drop ALL}。即使逃逸也拿不到特权。</li>
 *   <li><b>资源封顶</b>：{@code --memory}/{--memory-swap} 禁 swap、{@code --pids-limit} 防 fork 炸弹、
 *       {@code --cpus} 可选限核。</li>
 * </ul>
 *
 * <h2>与 local 实现的边界</h2>
 * <p>{@link LocalProcessSandboxExecutor} 只是「限时限内存」，不隔离；这里是真隔离。因此在部署服务器、
 * 向用户开放自传仓库时，必须切到 docker。代价是部署机要装 Docker，且镜像须预装 JDK21 + Maven，
 * 且本地仓库必须预先预热（离线依赖都在里面）。
 *
 * <h2>超时如何处理</h2>
 * <p>{@code docker run} 是前台进程，但强杀它并不会自动停掉容器（容器由 daemon 托管）。
 * 因此用 {@code --cidfile} 记录容器 ID，超时时 {@code docker kill} + {@code docker rm -f} 兜底。
 *
 * <h2>启动期快速失败</h2>
 * <p>{@link #verify()} 在装配时被调用：本机没有 docker、或要挂载的仓库/settings 不存在，
 * 直接让应用起不来，而不是等到第一次跑沙箱才暴露。
 */
public class DockerSandboxExecutor implements SandboxExecutor {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxExecutor.class);

    /** 沙箱内部产物目录，放在工作目录下，不污染被测工程自身的结构。 */
    private static final String SANDBOX_DIR = ".remaster-sandbox";
    private static final String STDOUT_LOG = SANDBOX_DIR + "/stdout.log";
    private static final String STDERR_LOG = SANDBOX_DIR + "/stderr.log";

    private static final Duration KILL_GRACE = Duration.ofSeconds(15);
    private static final String DEFAULT_IMAGE = "maven:3.9-eclipse-temurin-21";
    private static final String DEFAULT_USER = "1000:1000";
    private static final int DEFAULT_PIDS_LIMIT = 256;
    private static final String DEFAULT_CONTAINER_WORKSPACE = "/workspace";
    private static final String DEFAULT_CONTAINER_MAVEN_REPO = "/m2/repository";
    private static final String DEFAULT_CONTAINER_SETTINGS = "/m2/settings.xml";

    private final String image;
    private final String user;
    private final Path mavenRepoHost;
    private final Path settingsHost;
    private final int pidsLimit;
    private final String cpus; // nullable：不限核
    private final String containerWorkspace;
    private final String containerMavenRepo;
    private final String containerSettings;
    private final int memoryLimitMb;
    private final int maxOutputChars;

    public DockerSandboxExecutor(SandboxProperties properties, int maxOutputChars) {
        DockerSandboxProperties d = properties.docker();
        this.image = (d != null && d.image() != null && !d.image().isBlank()) ? d.image() : DEFAULT_IMAGE;
        this.user = (d != null && d.user() != null && !d.user().isBlank()) ? d.user() : DEFAULT_USER;

        String repoRaw = (d != null && d.mavenRepo() != null && !d.mavenRepo().isBlank())
                ? d.mavenRepo() : properties.mavenLocalRepo();
        String settingsRaw = (d != null && d.settings() != null && !d.settings().isBlank())
                ? d.settings() : properties.mavenSettings();
        if (repoRaw == null || repoRaw.isBlank()) {
            throw new IllegalStateException("Docker 沙箱缺少本地仓库路径（MAVEN_LOCAL_REPO / remaster.sandbox.docker.maven-repo）");
        }
        if (settingsRaw == null || settingsRaw.isBlank()) {
            throw new IllegalStateException("Docker 沙箱缺少 settings.xml 路径（MAVEN_SETTINGS / remaster.sandbox.docker.settings）");
        }
        this.mavenRepoHost = Path.of(repoRaw).toAbsolutePath().normalize();
        this.settingsHost = Path.of(settingsRaw).toAbsolutePath().normalize();

        this.pidsLimit = (d != null && d.pidsLimit() != null) ? d.pidsLimit() : DEFAULT_PIDS_LIMIT;
        this.cpus = (d != null && d.cpus() != null && !d.cpus().isBlank()) ? d.cpus() : null;
        this.containerWorkspace = (d != null && d.containerWorkspace() != null && !d.containerWorkspace().isBlank())
                ? d.containerWorkspace() : DEFAULT_CONTAINER_WORKSPACE;
        this.containerMavenRepo = (d != null && d.containerMavenRepo() != null && !d.containerMavenRepo().isBlank())
                ? d.containerMavenRepo() : DEFAULT_CONTAINER_MAVEN_REPO;
        this.containerSettings = (d != null && d.containerSettings() != null && !d.containerSettings().isBlank())
                ? d.containerSettings() : DEFAULT_CONTAINER_SETTINGS;
        this.memoryLimitMb = properties.memoryLimitMb() == null ? 1024 : properties.memoryLimitMb();
        this.maxOutputChars = maxOutputChars;
    }

    @Override
    public String mode() {
        return "docker";
    }

    /**
     * 启动期快速失败：没有 docker 就别让应用起来，免得第一次跑沙箱才暴露。
     * 挂载路径不存在也在此一并检查。
     */
    public void verify() {
        try {
            Process p = new ProcessBuilder("docker", "--version").redirectErrorStream(true).start();
            boolean finished = p.waitFor(15, TimeUnit.SECONDS);
            if (!finished || p.exitValue() != 0) {
                throw new IllegalStateException("docker --version 未能正常返回，Docker 沙箱不可用");
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "本机未找到 docker 命令，无法使用 docker 沙箱模式。请安装 Docker，"
                            + "或把 SANDBOX_MODE 设回 local。", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("docker 可用性检查被中断", e);
        }
        if (!Files.isDirectory(mavenRepoHost)) {
            throw new IllegalStateException("Docker 沙箱要挂载的本地仓库目录不存在: " + mavenRepoHost
                    + "（检查 MAVEN_LOCAL_REPO / remaster.sandbox.docker.maven-repo）");
        }
        if (!Files.isRegularFile(settingsHost)) {
            throw new IllegalStateException("Docker 沙箱要挂载的 settings.xml 不存在: " + settingsHost
                    + "（检查 MAVEN_SETTINGS / remaster.sandbox.docker.settings）");
        }
    }

    @Override
    public SandboxResult execute(SandboxRequest request) {
        Path workspace = request.workspace();
        if (!Files.isDirectory(workspace)) {
            throw new IllegalArgumentException("沙箱工作目录不存在: " + workspace);
        }
        Path workspaceAbs = workspace.toAbsolutePath().normalize();

        Path stdoutLog = workspace.resolve(STDOUT_LOG);
        Path stderrLog = workspace.resolve(STDERR_LOG);
        Path cidfile;
        try {
            Files.createDirectories(stdoutLog.getParent());
            // 每次执行前清掉上次的日志，否则会把上一轮的失败信息当成这一轮的
            Files.deleteIfExists(stdoutLog);
            Files.deleteIfExists(stderrLog);
            Files.createFile(stdoutLog);
            Files.createFile(stderrLog);
            cidfile = Files.createTempFile("remaster-sandbox-", ".cid");
        } catch (IOException e) {
            throw new UncheckedIOException("准备 Docker 沙箱辅助文件失败", e);
        }

        List<String> command = buildDockerCommand(request, workspaceAbs, cidfile);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspace.toFile());
        builder.redirectOutput(ProcessBuilder.Redirect.to(stdoutLog.toFile()));
        builder.redirectError(ProcessBuilder.Redirect.to(stderrLog.toFile()));
        Map<String, String> environment = builder.environment();
        // 外部注入的 JAVA_TOOL_OPTIONS 可能带进调试端口、代理等意外配置，沙箱里一律清掉
        environment.remove("JAVA_TOOL_OPTIONS");
        environment.remove("JDK_JAVA_OPTIONS");
        environment.putAll(request.environment());

        log.info("沙箱执行 [docker] 镜像={} 目录={} 超时={}s 内存={}MB 用户={}",
                image, workspaceAbs, request.timeout().toSeconds(), memoryLimitMb, user);
        log.debug("沙箱命令: {}", command);

        long startedAt = System.currentTimeMillis();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            long duration = System.currentTimeMillis() - startedAt;
            deleteSilently(cidfile);
            return new SandboxResult(-1, "", "无法启动 docker 进程: " + e.getMessage(), duration, false);
        }

        boolean timedOut = false;
        try {
            boolean finished = process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                timedOut = true;
                log.warn("Docker 沙箱执行超时，强制终止容器: {}", command);
                killContainer(cidfile, process);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killContainer(cidfile, process);
            throw new IllegalStateException("Docker 沙箱执行被中断", e);
        } finally {
            deleteSilently(cidfile);
        }

        long duration = System.currentTimeMillis() - startedAt;
        int exitCode = timedOut ? -1 : process.exitValue();

        // 被强杀后容器可能还有残留写操作，稍等一下再读，避免读到半截文件
        if (timedOut) {
            sleepQuietly(Duration.ofMillis(300));
        }

        String stdout = SandboxIo.readTruncated(stdoutLog, maxOutputChars);
        String stderr = SandboxIo.readTruncated(stderrLog, maxOutputChars);

        SandboxResult result = new SandboxResult(exitCode, stdout, stderr, duration, timedOut);
        log.info("Docker 沙箱执行结束 退出码={} 耗时={}ms", exitCode, duration);
        return result;
    }

    /**
     * 拼出完整的 {@code docker run} 命令。
     *
     * <p>隔离标志（{@code --network none} / {@code --read-only} / {@code --cap-drop ALL} /
     * {@code --security-opt no-new-privileges}）是<b>硬编码、不可由配置关掉</b>的——
     * 关掉任一项都会削弱隔离；只有资源上限与挂载路径来自配置。
     */
    List<String> buildDockerCommand(SandboxRequest request, Path workspaceAbs, Path cidfile) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("--rm");
        cmd.add("--network");
        cmd.add("none");
        cmd.add("--read-only");
        cmd.add("--tmpfs");
        cmd.add("/tmp");
        cmd.add("--security-opt");
        cmd.add("no-new-privileges");
        cmd.add("--cap-drop");
        cmd.add("ALL");
        cmd.add("--pids-limit");
        cmd.add(String.valueOf(pidsLimit));
        cmd.add("--memory");
        cmd.add(memoryLimitMb + "m");
        cmd.add("--memory-swap");
        cmd.add(memoryLimitMb + "m");
        if (cpus != null) {
            cmd.add("--cpus");
            cmd.add(cpus);
        }
        cmd.add("--user");
        cmd.add(user);
        cmd.add("--cidfile");
        cmd.add(cidfile.toString());
        cmd.add("--workdir");
        cmd.add(containerWorkspace);
        cmd.add("--volume");
        cmd.add(workspaceAbs + ":" + containerWorkspace + ":rw");
        cmd.add("--volume");
        cmd.add(mavenRepoHost + ":" + containerMavenRepo + ":ro");
        cmd.add("--volume");
        cmd.add(settingsHost + ":" + containerSettings + ":ro");
        cmd.add(image);
        cmd.addAll(decorateForContainer(request.command()));
        return cmd;
    }

    /**
     * 把逻辑命令里的 {@code mvn} 翻译成容器内调用：补齐离线模式、settings 与本地仓库。
     *
     * <p>网络已被 {@code --network none} 切断，所以必须 {@code -o} 离线、且依赖来自只读挂载的仓库。
     * 其余命令（非 mvn）原样透传——本项目目前只用 mvn。
     */
    List<String> decorateForContainer(List<String> logicalCommand) {
        List<String> out = new ArrayList<>();
        if (!logicalCommand.isEmpty() && "mvn".equals(logicalCommand.get(0))) {
            out.add("mvn");
            out.add("-B");
            out.add("-o");
            out.add("-s");
            out.add(containerSettings);
            out.add("-Dmaven.repo.local=" + containerMavenRepo);
            out.addAll(logicalCommand.subList(1, logicalCommand.size()));
        } else {
            out.addAll(logicalCommand);
        }
        return out;
    }

    /**
     * 超时时强制终止容器。
     *
     * <p>只杀 {@code docker run} 前台进程不够——容器由 daemon 托管，会继续跑。
     * 用 {@code --cidfile} 拿到容器 ID，{@code docker kill} + {@code docker rm -f} 兜底。
     */
    private void killContainer(Path cidfile, Process runProcess) {
        String cid = readCid(cidfile);
        if (cid != null) {
            runDocker(List.of("kill", cid));
            runDocker(List.of("rm", "-f", cid));
        }
        runProcess.destroyForcibly();
        try {
            if (!runProcess.waitFor(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("docker run 进程在 {} 秒内未退出", KILL_GRACE.getSeconds());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readCid(Path cidfile) {
        try {
            if (!Files.exists(cidfile)) {
                return null;
            }
            String cid = Files.readString(cidfile).trim();
            return cid.isBlank() ? null : cid;
        } catch (IOException e) {
            log.warn("读取 cidfile 失败: {}", cidfile, e);
            return null;
        }
    }

    /** 执行一条 docker 管理命令（kill/rm），忽略结果——超时清理是尽力而为。 */
    private static void runDocker(List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.addAll(args);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.waitFor(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (IOException e) {
            log.warn("执行 docker {} 失败（忽略）: {}", args, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteSilently(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理辅助文件失败不影响主流程
        }
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
