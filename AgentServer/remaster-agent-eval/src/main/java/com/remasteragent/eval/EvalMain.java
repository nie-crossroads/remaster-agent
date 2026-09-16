package com.remasteragent.eval;

import com.remasteragent.eval.api.ApiClient;
import com.remasteragent.eval.catalog.CaseCatalog;
import com.remasteragent.eval.catalog.EvalCase;
import com.remasteragent.eval.report.EvalReport;
import com.remasteragent.eval.report.HtmlReport;
import com.remasteragent.eval.report.MarkdownReport;
import com.remasteragent.eval.run.BaselineChecker;
import com.remasteragent.eval.run.EvalRun;
import com.remasteragent.eval.run.EvalRunner;
import com.remasteragent.eval.run.RunStore;
import com.remasteragent.eval.support.MavenExecutable;
import com.remasteragent.eval.support.RepoRoot;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测 harness 的命令行入口。
 *
 * <pre>
 *   catalog    列出用例清单（就绪 / 计划 / 负样本各多少）
 *   baseline   对就绪用例涉及的每个工程跑一次 mvn test，确认迁移前就是绿的
 *   run        提交就绪用例，轮询到终态，把原始结果落到 eval-results/&lt;runId&gt;/
 *   report     由已落盘的结果渲染报告（Markdown + 自包含 HTML）
 *   collect    只从数据库取数（B3）
 * </pre>
 *
 * <p><b>为什么 {@code report} 与 {@code run} 是分开的命令</b>：
 * 跑一轮要真烧模型调用，改一次报告模板就重跑一遍是不可接受的。所以
 * {@code run} 只负责「跑 + 落盘原始数据」，{@code report} 只负责「读盘 + 渲染」。
 * 这条边界让「改模板」变成一个零成本的迭代 —— 而报告模板总是要改很多遍的。
 */
public final class EvalMain {

    private static final String DEFAULT_API = "http://localhost:8080";

    private static final int EXIT_OK = 0;
    /** 用法错误。 */
    private static final int EXIT_USAGE = 1;
    /** 功能还没实现（B3 尚未交付）。 */
    private static final int EXIT_NOT_IMPLEMENTED = 2;
    /** 数据/环境校验失败（样本缺文件、基线不绿）。 */
    private static final int EXIT_PRECHECK_FAILED = 3;

    public static void main(String[] args) {
        bindUtf8Console();
        try {
            System.exit(dispatch(args));
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("✗ " + e.getMessage());
            System.exit(EXIT_PRECHECK_FAILED);
        }
    }

    /**
     * 把 {@code System.out/err} 显式钉成 UTF-8。
     *
     * <p>这是本项目踩过三次的坑的又一次现身：中文 Windows 上 {@code native.encoding} 是 GBK，
     * stdout 一旦被重定向（{@code > log}、被 Maven 接走、被 CI 收集），JDK 就沿用 GBK，
     * 于是「✓ / ✗」这种 GBK 里没有的字符会变成 {@code ?} —— 而它恰好站在
     * 「通过 / 不通过」这种最不能看错的位置。与沙箱那边必须给 {@code MAVEN_OPTS} 钉
     * {@code -Dstdout.encoding=UTF-8} 是同一件事，只是这次钉的是自己。
     *
     * <p>直接绑 {@link FileDescriptor#out} 而不是包装现有的 {@code System.out}：
     * 从 Maven {@code exec:java} 里跑的时候，现有的那个已经被 Maven 换过一层，
     * 再包一层还是会被它按平台编码写出去。
     */
    private static void bindUtf8Console() {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }

    private static int dispatch(String[] args) {
        List<String> positional = new ArrayList<>();
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--")) {
                int eq = arg.indexOf('=');
                if (eq < 0) {
                    options.put(arg.substring(2), "true");
                } else {
                    options.put(arg.substring(2, eq), arg.substring(eq + 1));
                }
            } else {
                positional.add(arg);
            }
        }

        if (positional.isEmpty()) {
            printUsage();
            return EXIT_USAGE;
        }
        String command = positional.get(0);
        Path repoRoot = options.containsKey("repo")
                ? Path.of(options.get("repo")).toAbsolutePath().normalize()
                : RepoRoot.locateFromWorkingDirectory();

        return switch (command) {
            case "catalog" -> catalog(repoRoot, options);
            case "baseline" -> baseline(repoRoot, options);
            case "run" -> run(repoRoot, options);
            case "report" -> report(repoRoot, options);
            case "collect" -> {
                System.out.println("子命令 collect 属于 B3 的后续批次，尚未实现。");
                System.out.println("当前报告所需指标全部来自 /api/tasks/{id} 的 metrics，无需另查数据库。");
                yield EXIT_NOT_IMPLEMENTED;
            }
            default -> {
                System.err.println("未知子命令: " + command);
                printUsage();
                yield EXIT_USAGE;
            }
        };
    }

    // ------------------------------------------------------------------
    // catalog
    // ------------------------------------------------------------------

    private static int catalog(Path repoRoot, Map<String, String> options) {
        CaseCatalog catalog = loadCatalog(repoRoot, options);
        System.out.println("用例清单: " + repoRoot.relativize(catalog.source()));
        System.out.println("  总数     : " + catalog.all().size());
        System.out.println("  就绪     : " + catalog.runnable().size());
        System.out.println("  计划中   : " + catalog.planned().size());
        System.out.println("  负样本   : " + catalog.negatives().size()
                + "（其中就绪 " + catalog.negatives().stream().filter(EvalCase::ready).count() + "）");
        System.out.println("  涉及工程 : " + catalog.projects().size() + " 个");

        System.out.println();
        System.out.println("就绪用例:");
        for (EvalCase evalCase : catalog.runnable()) {
            System.out.printf("  %-34s %-9s %s%n",
                    evalCase.id(),
                    evalCase.isNegative() ? "期望失败" : "期望成功",
                    evalCase.title());
        }

        List<String> problems = catalog.validateReady(repoRoot);
        if (!problems.isEmpty()) {
            System.out.println();
            System.out.println("✗ 就绪用例的目录自检未通过:");
            problems.forEach(p -> System.out.println("  - " + p));
            return EXIT_PRECHECK_FAILED;
        }
        System.out.println();
        System.out.println("✓ 全部就绪用例的工程与目标文件都存在");
        return EXIT_OK;
    }

    // ------------------------------------------------------------------
    // baseline
    // ------------------------------------------------------------------

    private static int baseline(Path repoRoot, Map<String, String> options) {
        CaseCatalog catalog = loadCatalog(repoRoot, options);
        List<EvalCase> selected = select(catalog, options);

        List<String> problems = catalog.validateReady(repoRoot);
        if (!problems.isEmpty()) {
            System.out.println("✗ 就绪用例的目录自检未通过，先修样本:");
            problems.forEach(p -> System.out.println("  - " + p));
            return EXIT_PRECHECK_FAILED;
        }

        // 正样本工程必须绿；负样本工程必须红。后者同样是一条得钉住的契约：
        // 一个「本来就能编过」的负样本，会让「期望失败」这条期望失去意义 ——
        // 那时 Agent 成功改好了它，却因为与期望不符被判 UNEXPECTED，反而是评测集在骗人。
        List<String> positiveProjects = selected.stream()
                .filter(c -> !c.isNegative()).map(EvalCase::project).distinct().toList();
        List<String> negativeProjects = selected.stream()
                .filter(EvalCase::isNegative).map(EvalCase::project).distinct().toList();

        String mvn = MavenExecutable.resolve(options.get("mvn"), System.getenv());
        boolean offline = !options.containsKey("online");
        Duration timeout = Duration.ofMinutes(longOption(options, "baseline-timeout", 10L));

        System.out.println("基线预检: " + positiveProjects.size() + " 个正样本工程"
                + (negativeProjects.isEmpty() ? "" : "，" + negativeProjects.size() + " 个负样本工程（期望红）"));
        System.out.println("  Maven  : " + mvn + (offline ? "（离线）" : "（联网）"));
        System.out.println();

        BaselineChecker checker = new BaselineChecker(mvn, timeout, offline);
        BaselineChecker.Report positives = checker.check(positiveProjects, repoRoot);
        BaselineChecker.Report negatives = checker.check(negativeProjects, repoRoot);

        int failures = 0;
        for (BaselineChecker.Result result : positives.byProject().values()) {
            boolean ok = result.green();
            failures += ok ? 0 : 1;
            System.out.printf("  %-40s %-6s %s  (%d ms)%n",
                    result.project(), "期望绿", result.summary(), result.durationMs());
        }
        for (BaselineChecker.Result result : negatives.byProject().values()) {
            boolean ok = !result.green();
            failures += ok ? 0 : 1;
            System.out.printf("  %-40s %-6s %s  (%d ms)%n",
                    result.project(), "期望红", result.summary(), result.durationMs());
        }

        List<BaselineChecker.Result> broken = new ArrayList<>(positives.failures());
        negatives.byProject().values().stream().filter(BaselineChecker.Result::green).forEach(broken::add);

        if (!broken.isEmpty()) {
            System.out.println();
            System.out.println("✗ 有工程的基线与用例的期望不符 —— 评测结论会因此失真，必须先修样本:");
            broken.forEach(result -> {
                System.out.println();
                System.out.println("--- " + result.project() + " ---");
                System.out.println(result.tail());
            });
            return EXIT_PRECHECK_FAILED;
        }
        System.out.println();
        System.out.println("✓ 基线全部符合预期（正样本绿、负样本红）");
        return EXIT_OK;
    }

    // ------------------------------------------------------------------
    // run
    // ------------------------------------------------------------------

    private static int run(Path repoRoot, Map<String, String> options) {
        CaseCatalog catalog = loadCatalog(repoRoot, options);
        List<EvalCase> selected = select(catalog, options);
        if (selected.isEmpty()) {
            System.out.println("没有选中任何就绪用例（用 --case=<id> 指定单条，或去掉该参数跑全部）");
            return EXIT_OK;
        }

        List<String> problems = catalog.validateReady(repoRoot);
        if (!problems.isEmpty()) {
            System.out.println("✗ 就绪用例的目录自检未通过，先修样本:");
            problems.forEach(p -> System.out.println("  - " + p));
            return EXIT_PRECHECK_FAILED;
        }

        String api = options.getOrDefault("api", DEFAULT_API);
        Duration poll = Duration.ofSeconds(longOption(options, "poll", 2L));
        Duration taskTimeout = Duration.ofMinutes(longOption(options, "timeout", 15L));

        String runId = RunStore.newRunId();
        Path runDir = RunStore.createRunDir(repoRoot, runId);
        System.out.println("评测运行 " + runId + " → " + repoRoot.relativize(runDir));
        System.out.println("  API   : " + api);
        System.out.println("  用例  : " + selected.size() + " 条（串行）");
        System.out.println("  上限  : 单任务 " + taskTimeout.toMinutes() + " 分钟");
        System.out.println();

        List<EvalRun> runs = new ArrayList<>();
        try (ApiClient client = new ApiClient(api, Duration.ofSeconds(30))) {
            EvalRunner runner = new EvalRunner(client, poll, taskTimeout, message -> System.out.println("  " + message));
            int index = 0;
            for (EvalCase evalCase : selected) {
                index++;
                System.out.println("[" + index + "/" + selected.size() + "] " + evalCase.id());
                EvalRun result = runner.run(evalCase, repoRoot);
                runs.add(result);
                System.out.println("    → " + describe(result));
                // 每条都落盘一次：中途被 Ctrl-C 也不会把已跑完的结论一起丢掉。
                RunStore.writeRuns(runDir, runs);
            }
        }

        RunStore.writeText(runDir.resolve("manifest.txt"), manifest(runId, runDir, catalog, selected, api, options));
        System.out.println();
        System.out.println(summaryLine(runs));
        System.out.println("原始结果: " + repoRoot.relativize(RunStore.writeRuns(runDir, runs)));
        System.out.println("（报告渲染是 B3 批次的任务，见 docs/PHASE4_EVAL_DESIGN.md §5）");
        return EXIT_OK;
    }

    // ------------------------------------------------------------------
    // report
    // ------------------------------------------------------------------

    /**
     * 渲染报告。
     *
     * <p>刻意只读盘、不联网：改一次模板就重跑一轮评测是不可接受的 ——
     * 重跑要真烧模型调用，而模板总是要改很多遍的。
     */
    private static int report(Path repoRoot, Map<String, String> options) {
        Path runsJson = options.containsKey("run")
                ? Path.of(options.get("run")).toAbsolutePath().normalize()
                : RunStore.latestRunsJson(repoRoot);
        if (Files.isDirectory(runsJson)) {
            runsJson = runsJson.resolve("runs.json");
        }
        if (!Files.isRegularFile(runsJson)) {
            throw new IllegalStateException("找不到结果文件: " + runsJson);
        }

        List<EvalRun> runs = RunStore.readRuns(runsJson);
        EvalReport report = new EvalReport(runs);
        String runId = runsJson.getParent().getFileName().toString();

        Path mdFile = RunStore.writeText(runsJson.getParent().resolve("report.md"),
                MarkdownReport.render(report, runId));
        Path htmlFile = RunStore.writeText(runsJson.getParent().resolve("report.html"),
                HtmlReport.render(report, runId));

        System.out.println("报告来源: " + repoRoot.relativize(runsJson));
        System.out.println("  用例   : " + report.total() + " 条（可用 " + report.usable()
                + "，harness 故障 " + report.harnessErrors() + "）");
        System.out.println("  如期望 : " + report.asExpected().text());
        System.out.println("  编译   : " + report.compile().text());
        System.out.println("  单测   : " + report.tests().text());
        System.out.println("  成本   : " + String.format(java.util.Locale.ROOT, "%.4f", report.totalCost())
                + " 元 / " + report.llmCalls() + " 次调用");
        System.out.println();
        System.out.println("Markdown 报告: " + repoRoot.relativize(mdFile));
        System.out.println("HTML 报告    : " + repoRoot.relativize(htmlFile));
        return EXIT_OK;
    }

    private static String describe(EvalRun result) {
        return switch (result.verdict()) {
            case AS_EXPECTED -> "如期望（" + result.status() + "）";
            case UNEXPECTED -> "与期望不符：期望 " + result.expect() + "，实际 " + result.status();
            case HARNESS_ERROR -> "harness 故障，本条不计入聚合：" + result.harnessError();
        };
    }

    /**
     * 命令行摘要 —— 不是报告。
     *
     * <p>刻意只给计数，不给比率：这些数还没有经过 {@code EvalReport} 的口径校验
     * （分母为 0 该显示什么、加权还是平均）。在命令行这里顺手编一个比率，
     * 等于绕开口径纪律给自己造一个更好看的数字。
     */
    private static String summaryLine(List<EvalRun> runs) {
        Map<EvalRun.Verdict, Integer> counts = new EnumMap<>(EvalRun.Verdict.class);
        for (EvalRun run : runs) {
            counts.merge(run.verdict(), 1, Integer::sum);
        }
        long harnessErrors = counts.getOrDefault(EvalRun.Verdict.HARNESS_ERROR, 0);
        return "本轮 " + runs.size() + " 条：如期望 " + counts.getOrDefault(EvalRun.Verdict.AS_EXPECTED, 0)
                + "，与期望不符 " + counts.getOrDefault(EvalRun.Verdict.UNEXPECTED, 0)
                + "，harness 故障 " + harnessErrors;
    }

    private static String manifest(String runId, Path runDir, CaseCatalog catalog,
                                   List<EvalCase> selected, String api, Map<String, String> options) {
        return "runId: " + runId + "\n"
                + "startedAt: " + java.time.OffsetDateTime.now() + "\n"
                + "api: " + api + "\n"
                + "catalog: " + catalog.source() + "\n"
                + "catalogReadyCases: " + catalog.runnable().size() + "\n"
                + "selectedCases: " + selected.size() + "\n"
                + "selectedIds: " + selected.stream().map(EvalCase::id).toList() + "\n"
                + "options: " + options + "\n"
                + "note: 报告里任何一个数字都可以顺着 runs.json 里的 taskId 回到 trace_span / llm_call / patch 复盘\n";
    }

    // ------------------------------------------------------------------
    // 公共辅助
    // ------------------------------------------------------------------

    private static CaseCatalog loadCatalog(Path repoRoot, Map<String, String> options) {
        Path catalogFile = options.containsKey("catalog")
                ? Path.of(options.get("catalog")).toAbsolutePath().normalize()
                : RepoRoot.catalogFile(repoRoot);
        return CaseCatalog.load(catalogFile);
    }

    /** 按 {@code --case=a,b} 过滤；不指定则取全部就绪用例。 */
    private static List<EvalCase> select(CaseCatalog catalog, Map<String, String> options) {
        String requested = options.get("case");
        if (requested == null || requested.isBlank()) {
            return catalog.runnable();
        }
        List<EvalCase> selected = new ArrayList<>();
        for (String id : requested.split(",")) {
            String trimmed = id.trim();
            if (!trimmed.isEmpty()) {
                selected.add(catalog.require(trimmed));
            }
        }
        return selected;
    }

    private static long longOption(Map<String, String> options, String key, long fallback) {
        String raw = options.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " 需要整数，收到: " + raw);
        }
    }

    private static void printUsage() {
        System.out.println("""
                RemasterAgent 评测 harness

                用法: mvn -o -pl remaster-agent-eval exec:java "-Dexec.args=<子命令> [选项]"

                子命令:
                  catalog    列出用例清单（就绪 / 计划 / 负样本）并自检样本文件
                  baseline   对就绪用例涉及的每个工程跑 mvn test，确认迁移前基线绿
                  run        提交就绪用例并轮询到终态，把原始结果落到 eval-results/<runId>/
                  report     由已落盘的结果渲染报告（B3）
                  collect    只从数据库取数（B3）

                选项:
                  --repo=<目录>            仓库根（默认从当前目录向上找 catalog.yaml）
                  --catalog=<文件>         用例清单路径
                  --case=<id>[,<id>...]    只跑指定用例
                  --api=<url>              API 地址，默认 http://localhost:8080
                  --mvn=<文件>             Maven 可执行文件（默认按 REMASTER_MAVEN → MAVEN_HOME → PATH 解析）
                  --timeout=<分钟>         单任务上限，默认 15
                  --poll=<秒>              轮询间隔，默认 2
                  --baseline-timeout=<分>  单工程基线预检上限，默认 10
                  --online                 基线预检允许联网（默认离线，保证可复现）
                """);
    }

    private EvalMain() {
    }
}
