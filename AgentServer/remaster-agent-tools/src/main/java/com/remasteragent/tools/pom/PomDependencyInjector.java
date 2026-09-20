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
 * 把缺失的 {@code <dependency>} 注入 {@code pom.xml} —— <b>不调模型</b>、坐标由 {@link JakartaArtifactCatalog} 给出。
 *
 * <h2>为什么用文本替换而非 DOM</h2>
 * <p>与 {@link PomJavaVersionRewriter} 同一套取舍：DOM 序列化会<b>抹掉注释与原始缩进</b>，
 * 产出的几十行噪声 diff 人没法审、回滚也没法看。这里只做<b>定点插入</b>——找到
 * {@code <dependencies>}，把新的 {@code <dependency>} 块插到它闭合标签之前；没有就新建一个块。
 * 已有依赖绝不改动，diff 因而只是「+N 行依赖」。</p>
 *
 * <h2>幂等 + 自检</h2>
 * <ul>
 *   <li><b>幂等</b>：已存在的 {@code groupId:artifactId} 不会重复注入（多模块父 pom 继承场景、重复跑都不怕）。</li>
 *   <li><b>合法性自检</b>：改写前后各用一次 XML 解析校验，解析不了就拒绝动手，改完不合法就放弃本次改写——
 *       宁可不改也不能交出坏文件（与护栏同一思路）。</li>
 * </ul>
 *
 * <p>无状态、纯函数式：同样的输入永远得到同样输出，可单测。
 */
public final class PomDependencyInjector {

    /** 依赖块的统一缩进（与 Maven 惯例一致：依赖 4 格、子元素 8 格）。 */
    private static final String INDENT = "    ";

    /** 单条依赖块的正则起点，用于判定「某个 groupId:artifactId 是否已在某个 dependency 块内」。 */
    private static final Pattern DEP_START = Pattern.compile("<dependency\\b");

    private PomDependencyInjector() {
    }

    /**
     * 一次注入的结果。
     *
     * @param changed 是否真的改了 pom
     * @param content 注入后的内容（未改动时与输入相同）
     * @param added   实际新增的依赖坐标列表（供落补丁说明与日志）
     */
    public record Result(boolean changed, String content, List<String> added) {
    }

    /**
     * 把 {@code artifacts} 中 pom 里尚不存在的依赖注入进去。
     *
     * @throws IllegalArgumentException 输入不是合法 XML（拒绝在解析不了的文件上做文本手术）
     * @throws IllegalStateException   改写后的 pom 不是合法 XML（自我保护：宁可失败也不交坏文件）
     */
    public static Result inject(String pomXml, Set<JakartaArtifactCatalog.Artifact> artifacts) {
        if (pomXml == null || pomXml.isBlank()) {
            throw new IllegalArgumentException("pom 内容为空");
        }
        if (!isWellFormed(pomXml)) {
            throw new IllegalArgumentException("pom 不是合法 XML，拒绝改写");
        }

        Set<JakartaArtifactCatalog.Artifact> toAdd = new LinkedHashSet<>();
        for (JakartaArtifactCatalog.Artifact a : artifacts) {
            if (a == null || a.groupId() == null || a.artifactId() == null || a.version() == null) {
                continue;
            }
            if (!containsDependency(pomXml, a)) {
                toAdd.add(a);
            }
        }
        if (toAdd.isEmpty()) {
            return new Result(false, pomXml, List.of());
        }

        StringBuilder blocks = new StringBuilder();
        List<String> added = new ArrayList<>();
        for (JakartaArtifactCatalog.Artifact a : toAdd) {
            blocks.append(INDENT).append("<dependency>\n")
                    .append(INDENT).append(INDENT).append("<groupId>").append(a.groupId()).append("</groupId>\n")
                    .append(INDENT).append(INDENT).append("<artifactId>").append(a.artifactId()).append("</artifactId>\n")
                    .append(INDENT).append(INDENT).append("<version>").append(a.version()).append("</version>\n");
            if (a.scope() != null && !a.scope().isBlank() && !"compile".equals(a.scope())) {
                blocks.append(INDENT).append(INDENT).append("<scope>").append(a.scope()).append("</scope>\n");
            }
            blocks.append(INDENT).append("</dependency>\n");
            added.add(a.coords());
        }

        String result = insertIntoDependencies(pomXml, blocks.toString());
        if (!isWellFormed(result)) {
            throw new IllegalStateException("注入后的 pom 不是合法 XML，已放弃本次改写");
        }
        return new Result(true, result, List.copyOf(added));
    }

    /**
     * pom 是否已包含某个依赖（按 {@code groupId:artifactId} 判定，与版本/作用域无关）。
     *
     * <p>在「按依赖块」的粒度内比对：只在同一 {@code <dependency>...</dependency>} 块内同时命中
     * groupId 与 artifactId 才算已存在，避免把「同文件里另一个依赖的 groupId」误判成匹配。
     */
    public static boolean containsDependency(String pomXml, JakartaArtifactCatalog.Artifact artifact) {
        if (pomXml == null || artifact == null) {
            return false;
        }
        Matcher matcher = DEP_START.matcher(pomXml);
        while (matcher.find()) {
            int start = matcher.start();
            int end = pomXml.indexOf("</dependency>", start);
            if (end < 0) {
                break;
            }
            String block = pomXml.substring(start, end + "</dependency>".length());
            boolean hasGroup = block.contains("<groupId>" + artifact.groupId() + "</groupId>");
            boolean hasArtifact = block.contains("<artifactId>" + artifact.artifactId() + "</artifactId>");
            if (hasGroup && hasArtifact) {
                return true;
            }
        }
        return false;
    }

    /** 把依赖块插入 pom：有 {@code <dependencies>} 就插到其闭合标签前，否则在合适位置新建块。 */
    private static String insertIntoDependencies(String pomXml, String blocks) {
        int depsEnd = pomXml.indexOf("</dependencies>");
        if (depsEnd >= 0) {
            return pomXml.substring(0, depsEnd) + blocks + pomXml.substring(depsEnd);
        }
        // 没有现成的 <dependencies>：在 <build> 之前插入（典型的 pom 顺序是 依赖在前、构建在后），
        // 否则退化到 </project> 之前。两种锚点都没有就意味着这不是一份正常 pom，交给上层报错。
        int buildIdx = pomXml.indexOf("<build");
        if (buildIdx >= 0) {
            String block = INDENT + "<dependencies>\n" + blocks + INDENT + "</dependencies>\n\n";
            return pomXml.substring(0, buildIdx) + block + pomXml.substring(buildIdx);
        }
        int projectEnd = pomXml.indexOf("</project>");
        if (projectEnd >= 0) {
            String block = INDENT + "<dependencies>\n" + blocks + INDENT + "</dependencies>\n";
            return pomXml.substring(0, projectEnd) + block + pomXml.substring(projectEnd);
        }
        throw new IllegalArgumentException("pom 里找不到 <dependencies> 或 </project>，无法插入依赖");
    }

    /** 是否是合法 XML（关掉 DTD 与外部实体，避免联网取 DTD / XXE，与 PomJavaVersionRewriter 同款）。 */
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
