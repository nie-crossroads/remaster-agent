package com.remasteragent.tools.maven;

import com.remasteragent.common.agent.VerifyResult;
import com.remasteragent.tools.sandbox.SandboxResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Maven 执行结果解析器。
 *
 * <h2>为什么不解析控制台输出</h2>
 * <p>用正则去啃 Maven 的 console 文本是最常见的错误做法：格式随版本变、多模块输出交错、
 * 中文/英文 locale 不同、并行构建时顺序还不确定。这些东西一旦变化，整个评测就悄悄失准，
 * 而失准的评测比没有评测更糟 —— 它会让你在面试里报出一个经不起追问的数字。
 *
 * <p>所以这里读的是 Maven 自己产出的<b>结构化文件</b>：
 * <ul>
 *   <li>单测结果 → {@code target/surefire-reports/TEST-*.xml}（surefire 的官方格式）</li>
 *   <li>覆盖率 → {@code target/site/jacoco/jacoco.csv}（JaCoCo 的 CSV 报告）</li>
 * </ul>
 * <p>只有当这些文件不存在时（典型场景是编译就没过，根本没跑到测试阶段），
 * 才回退去看控制台里的编译错误行 —— 因为那时也确实没有别的东西可读。
 */
public final class MavenResultParser {

    private static final Logger log = LoggerFactory.getLogger(MavenResultParser.class);

    /** surefire 报告：多模块下每个模块各有一份，所以递归找。 */
    private static final String SUREFIRE_GLOB_DIR = "surefire-reports";
    /** JaCoCo CSV 报告。 */
    private static final Path JACOCO_CSV = Path.of("target", "site", "jacoco", "jacoco.csv");

    /** Maven 的日志级别前缀，剥掉它才能拿到诊断正文。 */
    private static final Pattern LEVEL_PREFIX = Pattern.compile("^\\[(ERROR|WARNING|INFO)]\\s?");

    /**
     * 编译错误「首行」：定位 + 一句话诊断，形如 {@code /path/Foo.java:[12,34] cannot find symbol}。
     * 匹配前必须先剥掉 {@code [ERROR]} 前缀。
     */
    private static final Pattern COMPILE_ERROR_HEAD = Pattern.compile(
            "(\\S+\\.java):\\[(\\d+),(\\d+)]\\s*(.*)");

    /**
     * javac 的<b>明细续行</b>。**必须保留**：首行只说「找不到符号」，是这一行才告诉模型缺的
     * 到底是哪个符号/在哪个位置。中英文都要认 —— JDK 18+（JEP 400）起 javac 按
     * {@code stderr.encoding} 输出诊断，中文 Windows 上就是中文。
     */
    private static final Pattern COMPILE_ERROR_DETAIL = Pattern.compile(
            "(符号|位置|symbol|location)\\s*[:：].*");

    /** Maven 判定编译失败的标志。 */
    private static final String COMPILATION_ERROR_MARKER = "COMPILATION ERROR";

    /**
     * 默认最多带回多少行编译错误 —— 太多了会稀释 prompt，反而降低重写质量。
     * 取 40 而不是更小，是因为一个可用的错误块通常占 3 行（定位 + 符号 + 位置）。
     */
    private static final int DEFAULT_MAX_ERROR_LINES = 40;

    private MavenResultParser() {
    }

    /**
     * 单测统计。
     *
     * @param total       总数
     * @param passed      通过数
     * @param failed      失败数（failures + errors）
     * @param skipped     跳过数
     * @param failedNames 失败用例名，喂回给模型时只带这些就够定位问题
     */
    public record TestSummary(int total, int passed, int failed, int skipped, List<String> failedNames) {

        public static TestSummary empty() {
            return new TestSummary(0, 0, 0, 0, List.of());
        }

        /** 是否真的跑到了测试阶段。没跑到和「跑了但全过」是两件事，不能混为一谈。 */
        public boolean ran() {
            return total > 0;
        }
    }

    /**
     * 把一次沙箱执行的结果翻译成领域层的 {@link VerifyResult}。
     *
     * @param result     沙箱执行结果
     * @param projectDir 工程根目录（用来定位 target 下的报告文件）
     */
    public static VerifyResult toVerifyResult(SandboxResult result, Path projectDir) {
        String console = result.stdout() + "\n" + result.stderr();
        boolean compilationFailed = console.contains(COMPILATION_ERROR_MARKER);

        TestSummary tests = parseTests(projectDir);
        double coverage = parseLineCoverage(projectDir);

        // 编译是否通过：明确报错就是没过；否则只要跑出了测试报告也算过；
        // 都没有的情况下，以进程退出码为准（例如一个没有任何测试的空工程）。
        boolean compiled = !compilationFailed
                && (tests.ran() || result.exitCode() == 0);

        String failureExcerpt = buildFailureExcerpt(compilationFailed, console, tests, projectDir);

        return new VerifyResult(
                compiled,
                result.exitCode(),
                tests.total(),
                tests.passed(),
                tests.failed(),
                tests.skipped(),
                coverage,
                failureExcerpt,
                result.durationMs(),
                result.timedOut());
    }

    /**
     * 汇总 all surefire 报告。
     *
     * <p>用 JDK 自带的 DOM 解析器读 XML —— 这些文件是我们自己刚生成的，
     * 不存在 XXE 风险，但仍然关掉了外部实体解析，属于不需要省的安全习惯。
     */
    public static TestSummary parseTests(Path projectDir) {
        List<Path> reportFiles = findFiles(projectDir, path ->
                path.getFileName().toString().startsWith("TEST-")
                        && path.getFileName().toString().endsWith(".xml")
                        && path.getParent() != null
                        && SUREFIRE_GLOB_DIR.equals(path.getParent().getFileName().toString()));

        if (reportFiles.isEmpty()) {
            return TestSummary.empty();
        }

        int total = 0;
        int failed = 0;
        int skipped = 0;
        List<String> failedNames = new ArrayList<>();

        for (Path report : reportFiles) {
            try {
                Document doc = newSecureDocumentBuilder().parse(report.toFile());
                Element suite = doc.getDocumentElement();
                total += attr(suite, "tests");
                skipped += attr(suite, "skipped");
                failed += attr(suite, "failures") + attr(suite, "errors");
                failedNames.addAll(collectFailedCaseNames(suite));
            } catch (Exception e) {
                log.warn("解析 surefire 报告失败，跳过: {}", report, e);
            }
        }

        return new TestSummary(total, total - failed - skipped, failed, skipped, failedNames);
    }

    private static List<String> collectFailedCaseNames(Element suite) {
        List<String> names = new ArrayList<>();
        NodeList cases = suite.getElementsByTagName("testcase");
        for (int i = 0; i < cases.getLength(); i++) {
            Element testCase = (Element) cases.item(i);
            boolean bad = testCase.getElementsByTagName("failure").getLength() > 0
                    || testCase.getElementsByTagName("error").getLength() > 0;
            if (bad) {
                String className = testCase.getAttribute("classname");
                String name = testCase.getAttribute("name");
                names.add(className.isEmpty() ? name : className + "#" + name);
            }
        }
        return names;
    }

    /**
     * 汇总 JaCoCo 行覆盖率。
     *
     * @return 0~1 的比例；没有报告时返回 -1（而不是 0）——
     *         「没采到」和「覆盖率是 0」必须能区分开，否则指标会撒谎。
     */
    public static double parseLineCoverage(Path projectDir) {
        List<Path> csvFiles = findFiles(projectDir, path -> {
            Path parent = path.getParent();
            if (parent == null || !"jacoco".equals(parent.getFileName().toString())) {
                return false;
            }
            Path grandParent = parent.getParent();
            return grandParent != null
                    && "site".equals(grandParent.getFileName().toString())
                    && "jacoco.csv".equals(path.getFileName().toString());
        });

        if (csvFiles.isEmpty()) {
            log.debug("没有找到 jacoco.csv，跳过覆盖率采集");
            return -1d;
        }

        long covered = 0;
        long missed = 0;
        for (Path csv : csvFiles) {
            try {
                List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
                for (int i = 1; i < lines.size(); i++) {   // 第 0 行是表头
                    String[] columns = lines.get(i).split(",");
                    if (columns.length < 9) {
                        continue;
                    }
                    missed += Long.parseLong(columns[7].trim());
                    covered += Long.parseLong(columns[8].trim());
                }
            } catch (Exception e) {
                log.warn("解析 jacoco.csv 失败，跳过: {}", csv, e);
            }
        }

        long denominator = covered + missed;
        return denominator == 0 ? 0d : (double) covered / denominator;
    }

    /**
     * 组装喂回给 REWRITE 的失败摘要。
     *
     * <p>只带两类信息：失败的测试用例名、编译错误的**错误块**。整段 Maven 日志喂回去
     * 既贵又会把关键信息淹掉 —— prompt 里塞一万行没用的 INFO，模型照样抓不住重点。
     *
     * @param projectDir 沙箱工作目录，用于把绝对路径改写成工程内相对路径（让 prompt 更短更好读）
     */
    private static String buildFailureExcerpt(boolean compilationFailed, String console,
                                              TestSummary tests, Path projectDir) {
        StringBuilder sb = new StringBuilder();

        if (!tests.failedNames().isEmpty()) {
            sb.append("失败的测试用例:\n");
            tests.failedNames().stream().limit(20)
                    .forEach(name -> sb.append("  - ").append(name).append('\n'));
        }

        if (compilationFailed || sb.length() == 0) {
            List<String> errorBlocks = extractCompileErrors(console, projectDir, DEFAULT_MAX_ERROR_LINES);
            if (!errorBlocks.isEmpty()) {
                CompileErrorClassifier.CompileErrorReport report =
                        CompileErrorClassifier.classify(errorBlocks);
                // 自动归类后，模型拿到的不再是「一堆看不出所以然的诊断」，而是「这类错该怎么改」的定向提示；
                // 类别计数打日志，让「这一轮编译错主要卡在哪类」在监控上可见，而不是表现成「任务失败了但看不出为什么」。
                log.info("编译错误归类：总数={} jakarta漏改={} API签名={} 依赖未解析={} 测试源集={} 未归类={}",
                        report.total(), report.jakartaLeftover(), report.apiSignature(),
                        report.dependencyUnresolved(), report.testSource(), report.unclassified());
                sb.append("编译错误（已按类别自动归类，按类别定向修正；不要删改测试依赖的成员）:\n");
                for (CompileErrorClassifier.ClassifiedError error : report.errors()) {
                    sb.append("  [").append(CompileErrorClassifier.categoryLabel(error.category()))
                            .append("] ").append(error.block()).append('\n');
                    sb.append("    提示: ").append(error.hint()).append('\n');
                }
            }
        }

        return sb.toString();
    }

    /** 兼容旧签名（不做路径相对化）。 */
    public static List<String> extractCompileErrors(String console, int maxLines) {
        return extractCompileErrors(console, null, maxLines);
    }

    /**
     * 从控制台输出里抠出编译错误块。
     *
     * <p><b>为什么是「块」而不是「行」</b>：javac 的诊断是两段式的 —— 首行给定位
     * （{@code Foo.java:[12,34] 找不到符号}），紧跟的缩进行才给细节
     * （{@code 符号: 方法 formatDate(java.util.Date)}）。只留首行，模型知道「第 12 行有问题」
     * 却不知道「缺的是什么」，只能瞎猜 —— 实测会出现重试三轮毫无进展、甚至原样返回 diff 为 0 的情况。
     *
     * <p>另外两件顺手做掉的事：
     * <ul>
     *   <li><b>去重</b>：{@code compile} 与 {@code default-testCompile} 两个阶段会各报一遍同样的错误，
     *       不去重等于把一半行数预算浪费在重复内容上。</li>
     *   <li><b>相对化</b>：沙箱绝对路径（{@code E:/.../.remaster-workspaces/task-3/src/...}）又长又夹着
     *       本次任务的临时目录名，对模型是纯噪声；换成工程内相对路径，语义不变但可读性高得多。</li>
     * </ul>
     *
     * @param projectDir 沙箱工作目录（可为 null，此时不做相对化）
     * @param maxLines   返回内容的总行数上限（含明细续行）
     */
    public static List<String> extractCompileErrors(String console, Path projectDir, int maxLines) {
        if (console == null || console.isBlank() || maxLines <= 0) {
            return List.of();
        }

        List<String> finished = new ArrayList<>();
        List<String> current = null;

        for (String rawLine : console.split("\\R")) {
            // 剥掉 Maven 的日志级别前缀，拿到诊断正文（明细续行通常也带 [ERROR] 前缀）
            String body = LEVEL_PREFIX.matcher(rawLine.strip()).replaceFirst("");
            if (body.isEmpty()) {
                current = commit(finished, current);
                continue;
            }
            Matcher head = COMPILE_ERROR_HEAD.matcher(body);
            if (head.matches()) {
                current = commit(finished, current);
                current = new ArrayList<>();
                current.add(relativizePath(head.group(1), projectDir)
                        + ":[" + head.group(2) + "," + head.group(3) + "] " + head.group(4).strip());
                continue;
            }
            if (current != null && COMPILE_ERROR_DETAIL.matcher(body).matches()) {
                current.add(body);
                continue;
            }
            current = commit(finished, current);
        }
        commit(finished, current);

        // LinkedHashSet：既去重（两个编译阶段重复报同一错误）又保留首次出现顺序
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(finished));

        List<String> result = new ArrayList<>();
        int used = 0;
        for (String block : unique) {
            int lines = block.split("\n").length;
            if (used + lines > maxLines) {
                break;
            }
            result.add(block);
            used += lines;
        }
        return result;
    }

    /** 结束当前错误块：并入结果并返回 null，供循环里一行写完 {@code current = commit(...)}。 */
    private static List<String> commit(List<String> finished, List<String> current) {
        if (current != null) {
            finished.add(String.join("\n  ", current));
        }
        return null;
    }

    /**
     * 把沙箱工作目录下的绝对路径改写成工程内相对路径（统一成正斜杠）。
     *
     * <p>用 {@link Path} 双向解析而不是字符串前缀比对，因为日志里的路径形态不唯一：
     * 带盘符（{@code E:/...}）、不带盘符（{@code /work/...}）、以及 Maven 在 Windows 上的
     * {@code /E:/...}（盘符前多一个斜杠）都出现过。统一 {@code toAbsolutePath().normalize()}
     * 之后再比，前两种都对；第三种由 {@link #toComparablePath} 先抹掉那个多出来的斜杠。
     *
     * <p><b>不要拿 {@code isAbsolute()} 当守卫</b>：Windows 上 {@code Path.of("/work/x").isAbsolute()}
     * 返回 <b>false</b>（没有盘符的根路径不算绝对），拿它过滤会把正是我们要处理的那种写法直接跳过。
     *
     * <p>相对路径传进来也无妨 —— 归一化后不会落在工作目录下，自然走「保持原样」分支。
     * 解析不了就原样返回：路径美化是锦上添花，不值得为它冒抛异常的风险。
     */
    private static String relativizePath(String path, Path projectDir) {
        String forward = path.replace('\\', '/');
        if (projectDir == null) {
            return forward;
        }
        try {
            Path root = projectDir.toAbsolutePath().normalize();
            Path absolute = toComparablePath(path).toAbsolutePath().normalize();
            if (absolute.startsWith(root)) {
                return root.relativize(absolute).toString().replace('\\', '/');
            }
        } catch (Exception e) {
            log.debug("相对化路径失败，保持原样: {}", path);
        }
        return forward;
    }

    /** Maven 在 Windows 上打印的 {@code /E:/...} 形态（盘符前多一个斜杠），解析前要抹掉。 */
    private static final Pattern LEADING_SLASH_BEFORE_DRIVE = Pattern.compile("^/([A-Za-z]:[/\\\\].*)");

    /**
     * 把路径变成 {@link Path} 能解析的形态。
     *
     * <p>Windows 的 {@code Path.of("/E:/x")} 会抛 {@code InvalidPathException: Illegal char <:>}
     * —— 而 Maven 在 Windows 上恰好就是按 {@code /E:/...} 打印的，不处理的话路径相对化会静默失效
     * （异常被吞、原样返回，日志里看不出任何异常）。
     */
    private static Path toComparablePath(String raw) {
        Matcher m = LEADING_SLASH_BEFORE_DRIVE.matcher(raw);
        return Path.of(m.matches() ? m.group(1) : raw);
    }

    private static int attr(Element element, String name) {
        String value = element.getAttribute(name);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static DocumentBuilder newSecureDocumentBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    /** 在工程目录下按谓词找文件，深度受限，避免误入 node_modules 之类的深目录。 */
    private static List<Path> findFiles(Path root, java.util.function.Predicate<Path> filter) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root, 8)) {
            return stream.filter(Files::isRegularFile).filter(filter).toList();
        } catch (IOException e) {
            log.warn("遍历目录失败: {}", root, e);
            return List.of();
        }
    }
}
