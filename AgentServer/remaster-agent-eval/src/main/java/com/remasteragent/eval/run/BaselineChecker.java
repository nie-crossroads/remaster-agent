package com.remasteragent.eval.run;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基线预检：确认每个样本工程在<b>迁移之前</b>就是绿的。
 *
 * <h2>为什么这一步不能省</h2>
 * <p>评测结论是「改写后的单测通过率」。如果样本工程本来就有一半单测是红的，
 * 那么通过率里就混进了「与 Agent 无关的既有失败」—— 报告看起来能读，
 * 其实回答的不是我们想问的问题。所以基线必须单独验一次，而且必须<b>失败就中止</b>，
 * 不能采样跳过：那等于默认接受一个不干净的分母。
 *
 * <h2>两个实现细节，都是这个项目踩过的坑</h2>
 * <ol>
 *   <li><b>显式管编码。</b>子进程输出若按严格 UTF-8 解，一个坏字节就会丢整份日志，
 *       于是「为什么红」变成无从查起。这里给 {@code MAVEN_OPTS} 钉上
 *       {@code -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8}，
 *       读取端用 {@link CodingErrorAction#REPLACE} 兜底。</li>
 *   <li><b>只留尾部。</b>Maven 的输出可以很长，但真正有用的诊断全在末尾
 *       （错误块 + 汇总行）。日志原样留着只会淹没关键信息。</li>
 * </ol>
 */
public final class BaselineChecker {

    /** 保留的日志尾部长度。够放一个错误块加汇总行。 */
    private static final int TAIL_CHARS = 6_000;

    /** {@code Tests run: 51, Failures: 0, Errors: 0, Skipped: 0} —— 取最后一次出现。 */
    private static final Pattern SUREFIRE_SUMMARY =
            Pattern.compile("Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");

    private final String mvnExecutable;
    private final Duration timeout;
    private final boolean offline;

    /**
     * @param mvnExecutable Maven 可执行文件（见 {@link com.remasteragent.eval.support.MavenExecutable}）
     * @param timeout       单个工程的超时
     * @param offline       是否加 {@code -o}。评测要求可复现，默认离线 ——
     *                      依赖必须已在本地仓库里，否则说明环境没准备好，应该报出来
     */
    public BaselineChecker(String mvnExecutable, Duration timeout, boolean offline) {
        this.mvnExecutable = mvnExecutable;
        this.timeout = timeout;
        this.offline = offline;
    }

    /**
     * 对给定工程逐个跑 {@code mvn test}。
     *
     * @param projects 相对 {@code repoRoot} 的工程目录，保持出现顺序
     */
    public Report check(List<String> projects, java.nio.file.Path repoRoot) {
        Map<String, Result> results = new LinkedHashMap<>();
        for (String project : projects) {
            results.put(project, checkOne(project, repoRoot.resolve(project)));
        }
        return new Report(results);
    }

    /** 单个工程的预检结果。 */
    public record Result(
            String project,
            boolean green,
            int exitCode,
            long durationMs,
            int testsRun,
            int failures,
            int errors,
            String tail
    ) {

        /** 便于输出的一句话结论。 */
        public String summary() {
            if (testsRun < 0) {
                return green ? "绿（未解析到用例数）" : "红（退出码 " + exitCode + "）";
            }
            return (green ? "绿" : "红") + " —— " + testsRun + " 例，失败 " + failures + "，错误 " + errors;
        }
    }

    /** 整批预检结果。 */
    public record Report(Map<String, Result> byProject) {

        public boolean allGreen() {
            return byProject.values().stream().allMatch(Result::green);
        }

        public List<Result> failures() {
            return byProject.values().stream().filter(r -> !r.green()).toList();
        }
    }

    private Result checkOne(String project, java.nio.file.Path projectPath) {
        List<String> command = new ArrayList<>();
        command.add(mvnExecutable);
        if (offline) {
            command.add("-o");
        }
        command.add("-B");
        command.add("-Dstyle.color=never");
        command.add("-f");
        command.add(projectPath.resolve("pom.xml").toString());
        command.add("test");

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.directory(projectPath.toFile());
        // 与沙箱保持一致：输出一律按 UTF-8 产出。中文诊断在中文 Windows 上默认走 GBK，
        // 不钉住的话日志会乱码，而「看不懂为什么红」比「红」更难处理。
        builder.environment().merge("MAVEN_OPTS",
                "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8",
                (existing, added) -> existing + " " + added);

        long startedAt = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return new Result(project, false, -1, 0L, -1, -1, -1, "启动 Maven 失败: " + e.getMessage());
        }

        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> drain(process, output), "baseline-reader-" + project);
        reader.setDaemon(true);
        reader.start();

        int exitCode;
        try {
            if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                joinQuietly(reader);
                return new Result(project, false, -1, millisSince(startedAt), -1, -1, -1,
                        "超过 " + timeout.toMinutes() + " 分钟未结束，已强制终止\n" + tail(output));
            }
            exitCode = process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Result(project, false, -1, millisSince(startedAt), -1, -1, -1, "被中断");
        }
        joinQuietly(reader);

        String text = output.toString();
        int[] counts = parseSurefireSummary(text);
        return new Result(project, exitCode == 0, exitCode, millisSince(startedAt),
                counts[0], counts[1], counts[2], tail(output));
    }

    /** 逐行读，不让子进程因为管道写满而卡死。 */
    private static void drain(Process process, StringBuilder sink) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(),
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (sink) {
                    sink.append(line).append('\n');
                }
            }
        } catch (IOException ignored) {
            // 子进程被强杀时这里会抛，属于预期路径，不需要额外处理。
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(Duration.ofSeconds(5).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** @return {@code [testsRun, failures, errors]}；解析不到时全为 -1（不是 0）。 */
    private static int[] parseSurefireSummary(String text) {
        Matcher matcher = SUREFIRE_SUMMARY.matcher(text);
        int[] counts = {-1, -1, -1};
        while (matcher.find()) {
            counts[0] = Integer.parseInt(matcher.group(1));
            counts[1] = Integer.parseInt(matcher.group(2));
            counts[2] = Integer.parseInt(matcher.group(3));
        }
        return counts;
    }

    private static String tail(StringBuilder sink) {
        String text;
        synchronized (sink) {
            text = sink.toString();
        }
        return text.length() <= TAIL_CHARS ? text : "…（前 " + (text.length() - TAIL_CHARS) + " 字符已省略）\n"
                + text.substring(text.length() - TAIL_CHARS);
    }

    private static long millisSince(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
