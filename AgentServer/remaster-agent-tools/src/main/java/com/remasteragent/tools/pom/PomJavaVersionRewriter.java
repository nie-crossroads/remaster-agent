package com.remasteragent.tools.pom;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 {@code pom.xml} 的<b>编译级别</b>改写到目标 JDK。
 *
 * <h2>为什么不用 DOM / XML 库改而用文本替换</h2>
 * <p>DOM 解析再序列化会<b>丢掉注释与原始格式</b>。pom 恰恰是注释最多、格式最需要人读的文件
 * （本项目自己的样例工程就在 pom 里写了「release 为什么是 21」的整段注释）。
 * 一个把注释抹掉的改写器，即便语义正确，产出的 diff 也是几十行噪声 ——
 * 人没法审，回滚也没法看。所以这里只做<b>定点文本替换</b>：找到了就换掉那个数字，别的一个字节都不动，
 * diff 自然就是「-8 +21」一行。
 *
 * <p>但「文本替换」不等于放弃校验：改写前后各用一次 XML 解析做前置检查（解析不了就拒绝动手）
 * 与事后自检（改完必须仍是合法 XML）。这跟 {@code ProposalGuardrail} 是同一种思路 ——
 * 便宜的动作可以有，但必须有一道不便宜的校验兜住它。
 *
 * <h2>改哪些位置</h2>
 * <ol>
 *   <li>属性：{@code maven.compiler.release} / {@code maven.compiler.source} /
 *       {@code maven.compiler.target} / {@code java.version}</li>
 *   <li>{@code maven-compiler-plugin} 的 {@code <configuration>} 里的
 *       {@code <release>} / {@code <source>} / {@code <target>}</li>
 *   <li>以上一条都没找到时：往 {@code <properties>} 里<b>插入</b> {@code maven.compiler.release}
 *       —— 不带这一步，一份「没声明过编译级别」的 pom 会永远升不动</li>
 * </ol>
 *
 * <h2>刻意不碰的两种值</h2>
 * <ul>
 *   <li>值本身是属性引用（如 {@code <maven.compiler.release>${java.version}</maven.compiler.release>}）
 *       —— 覆盖它会破坏间接层，改错了比不改更难查。跳过并在报告里说明。</li>
 *   <li>XML 注释里的同名标签 —— pom 里的注释经常在解释「为什么不是 8」，
 *       连着注释一起改会让人读到自相矛盾的说明。</li>
 * </ul>
 *
 * <p>无状态、无依赖，纯函数式实现：同样的输入永远得到同样的输出，可单测。
 */
public final class PomJavaVersionRewriter {

    /** 会改写的属性名。 */
    private static final List<String> PROPERTY_TAGS = List.of(
            "maven.compiler.release", "maven.compiler.source", "maven.compiler.target", "java.version");

    /** compiler-plugin 的 configuration 里会被改写的标签名。 */
    private static final List<String> PLUGIN_TAGS = List.of("release", "source", "target");

    private static final String COMPILER_PLUGIN_ARTIFACT = "maven-compiler-plugin";

    /** XML 注释区间 {@code <!-- ... -->}（跨行）。 */
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");

    private PomJavaVersionRewriter() {
    }

    /**
     * 一次改写的结果。
     *
     * @param changed 是否真的改了内容
     * @param content 改写后的内容（未改动时与输入相同）
     * @param changes 逐条改动说明，如 {@code maven.compiler.release: 8 → 21}；供落 patch 说明与日志
     */
    public record Result(boolean changed, String content, List<String> changes) {
    }

    /**
     * 读 pom 里声明过的编译级别。
     *
     * @param release / {@code source} / {@code target} / {@code java.version} 的原始文本，
     *                取不到为 null；刻意保留原始文本（如 {@code 1.8}、{@code ${java.version}}），
     *                便于调用方判断「这个值能不能被安全地改写」
     */
    public record Declared(String release, String source, String target, String javaVersion) {

        /** 是否一条声明都没有。 */
        public boolean isEmpty() {
            return release == null && source == null && target == null && javaVersion == null;
        }

        /** 声明的级别里最小的那个（把 {@code 1.8} 归一成 8）；全都无法解析时为 null。 */
        public Integer minLevel() {
            Integer min = null;
            for (String raw : new String[]{release, source, target, javaVersion}) {
                Integer parsed = parseLevel(raw);
                if (parsed != null && (min == null || parsed < min)) {
                    min = parsed;
                }
            }
            return min;
        }
    }

    /**
     * 这份 pom 是否需要升级到 {@code targetJdk}。
     *
     * <p>判定为需要升级的两种情况：① 声明过的级别里有低于目标值的；② 完全没声明编译级别
     * （默认级别由 Maven 决定，通常是 1.6/1.8，必然低于 17/21）。
     */
    public static boolean needsUpgrade(String pomXml, int targetJdk) {
        Declared declared = readLevel(pomXml);
        if (declared.isEmpty()) {
            return true;
        }
        Integer min = declared.minLevel();
        if (min == null) {
            // 声明存在但值不是字面数字（多半是属性引用）。此时改不动，也不该谎称「已达标」——
            // 交给调用方按「无法判定」处理；这里返回 false 表示「不需要动它」，
            // 真正的说明由 rewrite 的结果给出。
            return false;
        }
        return min < targetJdk;
    }

    /**
     * 改写编译级别到 {@code targetJdk}。
     *
     * @throws IllegalArgumentException 输入不是合法 XML（拒绝在解析不了的文件上做文本手术）
     */
    public static Result rewrite(String pomXml, int targetJdk) {
        if (pomXml == null || pomXml.isBlank()) {
            throw new IllegalArgumentException("pom 内容为空");
        }
        if (!isWellFormed(pomXml)) {
            throw new IllegalArgumentException("pom 不是合法 XML，拒绝改写");
        }

        String working = pomXml;
        List<String> changes = new ArrayList<>();
        List<int[]> comments = commentRanges(working);

        // ① 属性
        for (String tag : PROPERTY_TAGS) {
            working = replaceTagOutsideComments(working, tag, targetJdk, comments, changes);
        }

        // ② compiler-plugin 的 configuration —— 重新算注释区间：上一步可能改变了偏移
        int[] pluginRange = compilerPluginRange(working);
        if (pluginRange != null) {
            comments = commentRanges(working);
            for (String tag : PLUGIN_TAGS) {
                working = replaceTagInRange(working, tag, targetJdk, pluginRange, comments, changes);
            }
        }

        // ③ 一条都没声明过 → 插入属性
        if (changes.isEmpty() && readLevel(pomXml).isEmpty()) {
            working = insertProperty(working, targetJdk);
            changes.add("新增属性 maven.compiler.release = " + targetJdk);
        }

        if (!isWellFormed(working)) {
            // 自检失败说明替换逻辑有 bug，宁可不改也不能交出坏文件
            throw new IllegalStateException("改写后的 pom 不是合法 XML，已放弃本次改写");
        }
        return new Result(!changes.isEmpty(), working, List.copyOf(changes));
    }

    /** 读当前声明的编译级别。 */
    public static Declared readLevel(String pomXml) {
        if (pomXml == null) {
            return new Declared(null, null, null, null);
        }
        String stripped = COMMENT.matcher(pomXml).replaceAll("");
        String release = tagText(stripped, "maven.compiler.release", 0, stripped.length());
        String source = tagText(stripped, "maven.compiler.source", 0, stripped.length());
        String target = tagText(stripped, "maven.compiler.target", 0, stripped.length());
        String javaVersion = tagText(stripped, "java.version", 0, stripped.length());

        // compiler-plugin 的 configuration 与属性是<b>两个独立来源</b>，Maven 里前者会覆盖后者，
        // 所以判「需不需要升级」时必须两个都看，取更低的那个 ——
        // 只看属性会漏掉「属性已是 21、但 plugin 配置里还钉着 8」这种真实存在的写法，
        // 而漏读的后果是谎称「编译级别已达标」，然后放任每个文件在 8 上编译 record 失败。
        int[] pluginRange = compilerPluginRange(stripped);
        if (pluginRange != null) {
            release = lowest(release, tagText(stripped, "release", pluginRange[0], pluginRange[1]));
            source = lowest(source, tagText(stripped, "source", pluginRange[0], pluginRange[1]));
            target = lowest(target, tagText(stripped, "target", pluginRange[0], pluginRange[1]));
        }
        return new Declared(release, source, target, javaVersion);
    }

    /**
     * 两个来源里的「更低者」。两边都能解析时取小的；只有一边能解析时取那一边；
     * 都不能解析时保留属性侧 —— 那一侧的具体值会在 {@code rewrite} 的 changes 里被明确说明
     * （「值不是字面数字，已跳过」），不会被静默吞掉。
     */
    private static String lowest(String property, String plugin) {
        Integer left = parseLevel(property);
        Integer right = parseLevel(plugin);
        if (left != null && right != null) {
            return left <= right ? property : plugin;
        }
        if (left != null) {
            return property;
        }
        return right != null ? plugin : firstNonNull(property, plugin);
    }

    /** {@code 21} → 21，{@code 1.8} → 8；无法解析返回 null。 */
    public static Integer parseLevel(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.startsWith("1.")) {
            value = value.substring(2);
        }
        if (value.startsWith("${") || value.isEmpty()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 内部：文本处理
    // ------------------------------------------------------------------

    private static String replaceTagOutsideComments(String xml, String tag, int targetJdk,
                                                    List<int[]> comments, List<String> changes) {
        return replaceTagInRange(xml, tag, targetJdk, new int[]{0, xml.length()}, comments, changes);
    }

    private static String replaceTagInRange(String xml, String tag, int targetJdk, int[] range,
                                            List<int[]> comments, List<String> changes) {
        Pattern pattern = tagPattern(tag);
        Matcher matcher = pattern.matcher(xml);
        StringBuilder out = new StringBuilder(xml.length());
        int cursor = 0;
        int replaced = 0;
        while (matcher.find()) {
            if (matcher.start() < range[0] || matcher.end() > range[1]) {
                continue;
            }
            if (insideComments(matcher.start(), comments)) {
                continue;
            }
            String currentRaw = matcher.group(1);
            Integer current = parseLevel(currentRaw);
            if (current == null) {
                changes.add(tag + " 的值不是字面数字（" + currentRaw.trim() + "），已跳过");
                continue;
            }
            if (current == targetJdk) {
                continue;
            }
            out.append(xml, cursor, matcher.start());
            out.append('<').append(tag).append('>').append(targetJdk).append("</").append(tag).append('>');
            cursor = matcher.end();
            replaced++;
            if (replaced == 1) {
                changes.add(prefixOf(xml, matcher.start(), tag) + " " + current + " → " + targetJdk);
            }
        }
        if (cursor == 0) {
            return xml;
        }
        out.append(xml, cursor, xml.length());
        return out.toString();
    }

    /** 说明里带上限定前缀（属性 / plugin 配置），让人一眼看出改的是哪个位置。 */
    private static String prefixOf(String xml, int position, String tag) {
        boolean property = PROPERTY_TAGS.contains(tag);
        String name = property ? tag : "maven-compiler-plugin.<" + tag + ">";
        return name + ":";
    }

    private static Pattern tagPattern(String tag) {
        return Pattern.compile("<" + Pattern.quote(tag) + "\\s*>([^<]*)</" + Pattern.quote(tag) + "\\s*>");
    }

    /** 取出某个标签在给定区间内的文本内容（第一处）。 */
    private static String tagText(String xml, String tag, int from, int to) {
        Matcher matcher = tagPattern(tag).matcher(xml);
        while (matcher.find()) {
            if (matcher.start() >= from && matcher.end() <= to && !insideComments(matcher.start(), commentRanges(xml))) {
                return matcher.group(1).trim();
            }
        }
        return null;
    }

    /** {@code maven-compiler-plugin} 所在 {@code <plugin>} 块的位置区间；找不到返回 null。 */
    private static int[] compilerPluginRange(String xml) {
        int artifactIdx = xml.indexOf("<artifactId>" + COMPILER_PLUGIN_ARTIFACT + "</artifactId>");
        if (artifactIdx < 0) {
            return null;
        }
        int start = xml.lastIndexOf("<plugin", artifactIdx);
        int end = xml.indexOf("</plugin>", artifactIdx);
        if (start < 0 || end < 0) {
            return null;
        }
        return new int[]{start, end};
    }

    /** 所有 XML 注释的区间。 */
    private static List<int[]> commentRanges(String xml) {
        List<int[]> ranges = new ArrayList<>();
        Matcher matcher = COMMENT.matcher(xml);
        while (matcher.find()) {
            ranges.add(new int[]{matcher.start(), matcher.end()});
        }
        return ranges;
    }

    private static boolean insideComments(int position, List<int[]> comments) {
        for (int[] range : comments) {
            if (position >= range[0] && position < range[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 往 {@code <properties>} 里插入编译级别属性。
     *
     * <p>没有 {@code <properties>} 块时，插在「第一个看起来是工程元信息结束的位置」——
     * 依次尝试 {@code <dependencies>} / {@code <modules>} / {@code <build>} / {@code </project>}。
     * 顺序不是随意的：Maven 对 pom 里的元素顺序<b>没有</b>强制要求，但人读 pom 的习惯是
     * 「坐标 → 属性 → 依赖 → 构建」，插在属性该在的位置，产出的 diff 才不需要人重新理解文件结构。
     */
    private static String insertProperty(String xml, int targetJdk) {
        String property = "<maven.compiler.release>" + targetJdk + "</maven.compiler.release>";
        int propertiesEnd = xml.indexOf("</properties>");
        if (propertiesEnd >= 0) {
            // 退回到上一个非空白字符之后插入，再自己补「换行 + 缩进」：
            // 直接插在 </properties> 前面会把原有的缩进留在新内容之后，
            // 于是最后一行属性会多出一段莫名其妙的前导空格。
            int insertAt = propertiesEnd;
            while (insertAt > 0 && Character.isWhitespace(xml.charAt(insertAt - 1))) {
                insertAt--;
            }
            return xml.substring(0, insertAt) + "\n        " + property + "\n    "
                    + xml.substring(propertiesEnd);
        }
        for (String anchor : List.of("<dependencies", "<modules", "<build")) {
            int idx = xml.indexOf(anchor);
            if (idx >= 0) {
                return xml.substring(0, idx) + "    <properties>\n        " + property
                        + "\n    </properties>\n\n" + xml.substring(idx);
            }
        }
        int projectEnd = xml.indexOf("</project>");
        if (projectEnd < 0) {
            throw new IllegalArgumentException("pom 里找不到 </project>，无法插入属性");
        }
        return xml.substring(0, projectEnd) + "    <properties>\n        " + property
                + "\n    </properties>\n" + xml.substring(projectEnd);
    }

    /**
     * 是否是合法 XML。
     *
     * <p>关掉 DTD 与外部实体：pom 通常带 {@code <!DOCTYPE ...>} 声明，默认配置下解析它会去
     * 联网取 DTD（离线环境会直接失败），而允许外部实体更是 XXE 的经典入口。
     * 这里只需要「能不能解析」这一个布尔结论，不需要任何外部资源。
     */
    private static boolean isWellFormed(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            builder.setErrorHandler(null);
            Document ignored = builder.parse(new InputSource(new StringReader(xml)));
            return ignored != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 收集 pom 里出现过的、无法解析为数字的编译级别声明（供报告说明「为什么没升」）。 */
    public static Set<String> unparsableDeclarations(String pomXml) {
        Set<String> found = new LinkedHashSet<>();
        Declared declared = readLevel(pomXml);
        for (String raw : new String[]{declared.release(), declared.source(), declared.target(),
                declared.javaVersion()}) {
            if (raw != null && parseLevel(raw) == null) {
                found.add(raw);
            }
        }
        return found;
    }

    private static String firstNonNull(String left, String right) {
        return left != null ? left : right;
    }
}
