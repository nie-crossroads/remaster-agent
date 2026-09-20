package com.remasteragent.tools.pom;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 {@code pom.xml} 里的 <b>Spring Boot parent / BOM</b> 升级到目标大版本。
 *
 * <h2>这是阶段 5 第②层（parent/BOM 升级）</h2>
 * <p>Spring Boot 2.7 → 3.x 是「一处牵一片」的变更：换掉 {@code spring-boot-starter-parent} 的版本，
 * 整个 BOM 托管的 {@code spring-boot-starter-*} 依赖会一次性从 <b>javax</b> 命名空间跳到 <b>jakarta</b> 命名空间，
 * 同时强制要求 Java 17+。这一步是「坐标迁移（第④层）」「用法迁移（第⑤层）」能成立的前提——
 * 不先升 parent，注入 {@code jakarta.*} 依赖只会跟旧 SB2 的 {@code javax} 栈打架。</p>
 *
 * <h2>为什么不用 DOM / XML 库改而用文本替换</h2>
 * <p>与 {@link PomJavaVersionRewriter} 同一个理由：DOM 序列化会抹掉注释与原始格式，产出的 diff 是几十行噪声，
 * 人没法审。这里只做<b>定点替换</b>：定位到 {@code spring-boot-starter-parent} / {@code spring-boot-maven-plugin}
 * 各自块里的 {@code <version>}，换掉那个数字，其余一个字节不动。</p>
 *
 * <h2>确定性、不调模型</h2>
 * <p>目标版本是<b>固化常量</b>（{@link #TARGET_VERSION}），不查 Maven Central、不联网。这符合项目一贯纪律——
 * 「选版本」留给人和配置，「改对 + 证明能跑」留给 Agent。同样的输入永远得到同样的输出，可单测。</p>
 *
 * <h2>只动 parent 版本，不升业务依赖</h2>
 * <p>这里只升 parent 与构建插件（{@code spring-boot-maven-plugin}）的版本。直接依赖（如 mybatis、pagehelper
 * 这类 starter）的版本跃迁属于第③层，不在本类职责内——刻意不混进来，是为了让「升级失败」能精确定位到
 * 「parent 层」还是「依赖层」。</p>
 *
 * <p>无状态、无依赖，纯函数式实现。</p>
 */
public final class SpringBootParentUpgrader {

    /** 目标大版本：与本项目自身的 Spring Boot 版本对齐，固化下来不联网查最新。 */
    public static final String TARGET_VERSION = "3.5.16";

    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern VERSION = Pattern.compile("<version>([^<]*)</version>");

    private SpringBootParentUpgrader() {
    }

    /**
     * 一次改写的结果。
     *
     * @param changed  是否真的改了内容
     * @param content  改写后的内容（未改动时与输入相同）
     * @param changes  逐条改动说明，如 {@code spring-boot-starter-parent 版本: 2.7.17 → 3.5.16}
     */
    public record Result(boolean changed, String content, List<String> changes) {
    }

    /** 这份 pom 是否需要升级 Spring Boot parent（声明了 {@code spring-boot-starter-parent} 且大版本 < 3）。 */
    public static boolean needsUpgrade(String pomXml) {
        Optional<String> current = currentParentVersion(pomXml);
        if (current.isEmpty()) {
            return false;
        }
        String v = current.get().trim();
        if (v.startsWith("${")) {
            // 版本是属性引用，无法安全自动升级（覆盖它会破坏间接层），交给人工处理
            return false;
        }
        Integer major = parseMajor(v);
        return major != null && major < 3;
    }

    /** 该 pom 当前声明的 {@code spring-boot-starter-parent} 版本（取不到为空）。 */
    public static Optional<String> currentParentVersion(String pomXml) {
        return blockVersion(pomXml, "spring-boot-starter-parent", "parent");
    }

    /** 升级 parent 版本 + {@code spring-boot-maven-plugin} 版本到 {@link #TARGET_VERSION}。 */
    public static Result upgrade(String pomXml) {
        return upgrade(pomXml, TARGET_VERSION);
    }

    /**
     * 升级 parent 版本 + 构建插件版本到 {@code targetVersion}。
     *
     * @throws IllegalArgumentException 输入不是合法 XML（拒绝在解析不了的文件上做文本手术）
     */
    public static Result upgrade(String pomXml, String targetVersion) {
        if (pomXml == null || pomXml.isBlank()) {
            throw new IllegalArgumentException("pom 内容为空");
        }
        if (!isWellFormed(pomXml)) {
            throw new IllegalArgumentException("pom 不是合法 XML，拒绝改写");
        }

        String working = pomXml;
        List<String> changes = new ArrayList<>();

        Optional<String> parentVer = currentParentVersion(working);
        if (parentVer.isPresent() && !parentVer.get().trim().equals(targetVersion)
                && !parentVer.get().trim().startsWith("${")) {
            working = replaceBlockVersion(working, "spring-boot-starter-parent", "parent",
                    targetVersion, "spring-boot-starter-parent", changes);
        }

        Optional<String> pluginVer = blockVersion(working, "spring-boot-maven-plugin", "plugin");
        if (pluginVer.isPresent() && !pluginVer.get().trim().equals(targetVersion)
                && !pluginVer.get().trim().startsWith("${")) {
            working = replaceBlockVersion(working, "spring-boot-maven-plugin", "plugin",
                    targetVersion, "spring-boot-maven-plugin", changes);
        }

        if (!isWellFormed(working)) {
            // 自检失败说明替换逻辑有 bug，宁可不改也不能交出坏文件
            throw new IllegalStateException("改写后的 pom 不是合法 XML，已放弃本次改写");
        }
        return new Result(!changes.isEmpty(), working, List.copyOf(changes));
    }

    // ------------------------------------------------------------------
    // 内部：文本处理
    // ------------------------------------------------------------------

    /** 读取某个 artifactId 所在块（parent / plugin）内的首个 {@code <version>} 文本。 */
    private static Optional<String> blockVersion(String xml, String artifactId, String tag) {
        int aidx = xml.indexOf("<artifactId>" + artifactId + "</artifactId>");
        if (aidx < 0) {
            return Optional.empty();
        }
        int start = xml.lastIndexOf("<" + tag, aidx);
        int end = xml.indexOf("</" + tag + ">", aidx);
        if (start < 0 || end < 0) {
            return Optional.empty();
        }
        Matcher m = VERSION.matcher(xml);
        while (m.find()) {
            if (m.start() >= start && m.end() <= end && !insideComment(m.start(), xml)) {
                return Optional.of(m.group(1).trim());
            }
        }
        return Optional.empty();
    }

    /** 把某个 artifactId 所在块内的首个 {@code <version>} 替换成 {@code target}，保留其余文本不变。 */
    private static String replaceBlockVersion(String xml, String artifactId, String tag,
                                              String target, String label, List<String> changes) {
        int aidx = xml.indexOf("<artifactId>" + artifactId + "</artifactId>");
        if (aidx < 0) {
            return xml;
        }
        int start = xml.lastIndexOf("<" + tag, aidx);
        int end = xml.indexOf("</" + tag + ">", aidx);
        if (start < 0 || end < 0) {
            return xml;
        }
        Matcher m = VERSION.matcher(xml);
        StringBuilder out = new StringBuilder(xml.length());
        int cursor = 0;
        boolean replaced = false;
        while (m.find()) {
            out.append(xml, cursor, m.start());
            if (!replaced && m.start() >= start && m.end() <= end && !insideComment(m.start(), xml)) {
                out.append("<version>").append(target).append("</version>");
                changes.add(label + " 版本: " + m.group(1).trim() + " → " + target);
                replaced = true;
            } else {
                out.append(xml, m.start(), m.end());
            }
            cursor = m.end();
        }
        out.append(xml, cursor, xml.length());
        return replaced ? out.toString() : xml;
    }

    private static boolean insideComment(int position, String xml) {
        Matcher m = COMMENT.matcher(xml);
        while (m.find()) {
            if (position >= m.start() && position < m.end()) {
                return true;
            }
        }
        return false;
    }

    private static Integer parseMajor(String v) {
        try {
            return Integer.parseInt(v.split("\\.")[0]);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 是否是合法 XML。关掉 DTD 与外部实体：pom 常带 {@code <!DOCTYPE>}，默认配置会联网取 DTD、
     * 而外部实体是 XXE 经典入口；这里只要「能不能解析」这一个布尔结论，不需要任何外部资源。
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
}
