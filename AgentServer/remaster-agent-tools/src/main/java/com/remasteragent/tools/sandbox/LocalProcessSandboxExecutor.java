package com.remasteragent.tools.sandbox;

import com.remasteragent.tools.maven.MavenToolchain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 本机受限子进程沙箱 —— 在没有 Docker 的环境下执行模型生成的代码。
 *
 * <h2>它能做什么</h2>
 * <ul>
 *   <li><b>限时</b>：超时后连同子进程树一起强杀（Windows 上 {@code mvn.cmd} 会再拉起 java 进程，
 *       只杀父进程会留下孤儿进程继续跑）</li>
 *   <li><b>限内存</b>：通过 {@code MAVEN_OPTS=-Xmx} 限制 fork 出去的 JVM 堆</li>
 *   <li><b>限范围</b>：在独立工作目录里执行，原仓库不被就地修改</li>
 *   <li><b>不卡死</b>：输出重定向到文件，避免管道缓冲区写满导致进程静默挂住</li>
 * </ul>
 *
 * <h2>它不能做什么（重要，别对外宣称有隔离）</h2>
 * <p><b>这不是安全沙箱。</b>子进程仍以当前用户身份运行，能读写文件系统、能访问网络。
 * 它防的是「意外的资源耗尽」，不是「恶意的代码」。所以现阶段只能对自有可信工程执行，
 * 绝不能把来路不明的仓库丢进来。真正的隔离要等 Docker 实现
 * （{@code --network none} + 只读挂载 + 非 root 用户）。
 *
 * <p>换 Docker 实现时不需要改动上层：契约只要求「执行一条命令、拿回结果」。
 */
public class LocalProcessSandboxExecutor implements SandboxExecutor {

    private static final Logger log = LoggerFactory.getLogger(LocalProcessSandboxExecutor.class);

    /** 沙箱内部产物目录，放在工作目录下，不污染被测工程自身的结构。 */
    private static final String SANDBOX_DIR = ".remaster-sandbox";
    private static final String STDOUT_LOG = SANDBOX_DIR + "/stdout.log";
    private static final String STDERR_LOG = SANDBOX_DIR + "/stderr.log";

    private static final Duration KILL_GRACE = Duration.ofSeconds(15);

    private final MavenToolchain toolchain;
    private final int maxOutputChars;

    public LocalProcessSandboxExecutor(MavenToolchain toolchain, int maxOutputChars) {
        this.toolchain = toolchain;
        this.maxOutputChars = maxOutputChars;
        toolchain.verify();
    }

    @Override
    public String mode() {
        return "local";
    }

    @Override
    public SandboxResult execute(SandboxRequest request) {
        Path workspace = request.workspace();
        if (!Files.isDirectory(workspace)) {
            throw new IllegalArgumentException("沙箱工作目录不存在: " + workspace);
        }

        List<String> command = toolchain.decorate(request.command());
        Path stdoutLog = workspace.resolve(STDOUT_LOG);
        Path stderrLog = workspace.resolve(STDERR_LOG);
        try {
            Files.createDirectories(stdoutLog.getParent());
            // 每次执行前清掉上次的日志，否则会把上一轮的失败信息当成这一轮的
            Files.deleteIfExists(stdoutLog);
            Files.deleteIfExists(stderrLog);
            Files.createFile(stdoutLog);
            Files.createFile(stderrLog);
        } catch (IOException e) {
            throw new UncheckedIOException("准备沙箱日志文件失败", e);
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspace.toFile());
        builder.redirectOutput(ProcessBuilder.Redirect.to(stdoutLog.toFile()));
        builder.redirectError(ProcessBuilder.Redirect.to(stderrLog.toFile()));

        Map<String, String> environment = builder.environment();
        environment.putAll(jvmOverrides(request.memoryLimitMb()));
        // 外部注入的 JAVA_TOOL_OPTIONS 可能带进调试端口、代理等意外配置，沙箱里一律清掉
        environment.remove("JAVA_TOOL_OPTIONS");
        environment.remove("JDK_JAVA_OPTIONS");
        environment.putAll(request.environment());

        log.info("沙箱执行 [{}] 目录={} 超时={}s 内存上限={}MB",
                mode(), workspace, request.timeout().toSeconds(), request.memoryLimitMb());
        log.debug("沙箱命令: {}", command);

        long startedAt = System.currentTimeMillis();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            long duration = System.currentTimeMillis() - startedAt;
            return new SandboxResult(-1, "", "无法启动进程: " + e.getMessage(), duration, false);
        }

        boolean timedOut = false;
        try {
            boolean finished = process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                timedOut = true;
                log.warn("沙箱执行超时，强制终止进程树: {}", command);
                destroyProcessTree(process);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyProcessTree(process);
            throw new IllegalStateException("沙箱执行被中断", e);
        }

        long duration = System.currentTimeMillis() - startedAt;
        int exitCode = timedOut ? -1 : process.exitValue();

        // 被强杀后子进程可能还有残留写操作，稍等一下再读，避免读到半截文件
        if (timedOut) {
            sleepQuietly(Duration.ofMillis(300));
        }

        String stdout = SandboxIo.readTruncated(stdoutLog, maxOutputChars);
        String stderr = SandboxIo.readTruncated(stderrLog, maxOutputChars);

        SandboxResult result = new SandboxResult(exitCode, stdout, stderr, duration, timedOut);
        log.info("沙箱执行结束 [{}] 退出码={} 耗时={}ms", mode(), exitCode, duration);
        return result;
    }

    /**
     * 强制结束进程树。
     *
     * <p>这一点在 Windows 上特别重要：{@code mvn.cmd} 是一个批处理脚本，
     * 它本身会 fork 出真正的 JVM。只杀批处理的宿主进程，JVM 会变成孤儿继续跑满 CPU ——
     * 表现就是「超时了但机器还是卡的」。
     */
    private void destroyProcessTree(Process process) {
        process.descendants().forEach(handle -> {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        });
        process.destroyForcibly();
        try {
            if (!process.waitFor(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("进程在 {} 秒内没有退出，可能仍有残留", KILL_GRACE.toSeconds());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 沙箱内 Maven JVM 的环境覆盖项。
     *
     * <p><b>编码三件套，缺一不可</b>：
     * <ul>
     *   <li>{@code -Dfile.encoding=UTF-8} 管「读写文件用的默认字符集」</li>
     *   <li>{@code -Dstdout.encoding=UTF-8} / {@code -Dstderr.encoding=UTF-8} 管 {@code System.out} / {@code System.err}</li>
     * </ul>
     * JDK 18（JEP 400）之后这两者<b>解耦</b>了：只设 {@code file.encoding} 时，
     * {@code System.err} 仍按控制台代码页输出（中文 Windows 上是 GBK）。
     * 而 {@code javac} 的编译错误恰恰走 {@code System.err} —— 不显式设置，
     * 「程序包 com.example.oracle 不存在」会以 GBK 落进日志，再被按 UTF-8 解码成乱码，
     * 模型就只剩「文件:行,列」可读，回退重写的效果大打折扣。
     *
     * <p>{@code stdout/stderr.encoding} 看上去和 {@code file.encoding} 重复，
     * 极易被后来人当冗余清理掉 —— 所以这里是包级可见的独立方法，由单测锁住。
     *
     * <p>{@code MAVEN_ARGS} 置空是为了屏蔽外部环境残留的参数。
     */
    static Map<String, String> jvmOverrides(int memoryLimitMb) {
        return Map.of(
                "MAVEN_OPTS", "-Xmx" + memoryLimitMb + "m"
                        + " -Dfile.encoding=UTF-8"
                        + " -Dstdout.encoding=UTF-8"
                        + " -Dstderr.encoding=UTF-8",
                "MAVEN_ARGS", "");
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
