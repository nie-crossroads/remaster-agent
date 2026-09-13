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

    /** 编译错误行，形如 "ERROR] /path/Foo.java:[12,34] cannot find symbol"。 */
    private static final Pattern COMPILE_ERROR = Pattern.compile(
            "\\[ERROR].*?\\.java:\\[\\d+,\\d+].*");

    /** Maven 判定编译失败的标志。 */
    private static final String COMPILATION_ERROR_MARKER = "COMPILATION ERROR";

    /** 默认最多带回多少行编译错误 —— 太多了会稀释 prompt，反而降低重写质量。 */
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

        String failureExcerpt = buildFailureExcerpt(compilationFailed, console, tests);

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
     * <p>只带三类信息：失败的测试用例名、编译错误的定位行。整段 Maven 日志喂回去
     * 既贵又会把关键信息淹掉 —— prompt 里塞一万行没用的 INFO，模型照样抓不住重点。
     */
    private static String buildFailureExcerpt(boolean compilationFailed, String console, TestSummary tests) {
        StringBuilder sb = new StringBuilder();

        if (!tests.failedNames().isEmpty()) {
            sb.append("失败的测试用例:\n");
            tests.failedNames().stream().limit(20)
                    .forEach(name -> sb.append("  - ").append(name).append('\n'));
        }

        if (compilationFailed || sb.length() == 0) {
            List<String> errorLines = extractCompileErrors(console, DEFAULT_MAX_ERROR_LINES);
            if (!errorLines.isEmpty()) {
                sb.append("编译错误:\n");
                errorLines.forEach(line -> sb.append("  ").append(line).append('\n'));
            }
        }

        return sb.toString();
    }

    /** 从控制台输出里抠出编译错误行（仅在这些错误没有对应报告文件时可用的兜底手段）。 */
    public static List<String> extractCompileErrors(String console, int maxLines) {
        List<String> errors = new ArrayList<>();
        if (console == null || console.isBlank()) {
            return errors;
        }
        Matcher matcher = COMPILE_ERROR.matcher(console);
        while (matcher.find() && errors.size() < maxLines) {
            errors.add(matcher.group().trim());
        }
        return errors;
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
