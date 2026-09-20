package com.remasteragent.tools.pom;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把直接依赖的坐标 / 版本，按一张<b>固化的白名单</b>改写为 SB3 兼容形态 —— <b>不调模型</b>。
 *
 * <h2>它覆盖「阶段 5」的哪几层</h2>
 * <p>与 {@link JakartaArtifactCatalog}（第④层的「注入 jakarta 依赖」）互补，本类处理第④层剩下的
 * 「<b>坐标改名</b>」与第③层的「<b>直接依赖版本提升</b>」：</p>
 * <ul>
 *   <li><b>坐标改名</b>：{@code mysql:mysql-connector-java} → {@code com.mysql:mysql-connector-j}。
 *       SB3 的 {@code spring-boot-starter-parent} 管理的驱动坐标已改名，旧坐标虽仍能解析，但保留旧名会让
 *       依赖树里出现「同一驱动两个坐标」的隐患。</li>
 *   <li><b>版本提升</b>：{@code mybatis-spring-boot-starter} 的 2.3.x 线是给 SB2.7 用的（内部仍 javax 命名空间、
 *       自动配置类路径在 SB3 已迁移），必须升到 3.x 才能在 SB3 下加载自动配置。
 *       （{@code pagehelper-spring-boot-starter} 2.0.0 本身已是 SB3 线，无需动，故不在表里。）</li>
 * </ul>
 *
 * <h2>为什么版本被固化为常量</h2>
 * <p>与 {@link JakartaArtifactCatalog} 同一思路：把「选版本」移出 Agent（见 {@code PHASE5_SDK_UPGRADE.md}
 * 阶段 A）。目标是「确定的输入 → 确定的产出」，可单测、可归因、不随运行时间漂移。</p>
 *
 * <h2>文本改写，不是 DOM</h2>
 * <p>与 {@link PomDependencyInjector} / {@link PomJavaVersionRewriter} 同一套取舍：只做定点替换，
 * 保留注释与原始缩进，diff 干净可读。改写前后各做一次 XML 良构自检，宁可失败也不交坏文件。</p>
 */
public final class PomDependencyUpgrader {

    /** 坐标改名：旧 {@code "groupId:artifactId"} → 新 {@code "groupId:artifactId"}（版本保留原声明值）。 */
    private static final Map<String, String> COORDINATE_RENAMES = Map.of(
            "mysql:mysql-connector-java", "com.mysql:mysql-connector-j");

    /** 直接依赖版本提升：旧 {@code "groupId:artifactId"} → 目标版本（只动显式声明了 {@code <version>} 的依赖）。 */
    private static final Map<String, String> VERSION_BUMPS = Map.of(
            "org.mybatis.spring.boot:mybatis-spring-boot-starter", "3.0.3");

    /** 单个 {@code <dependency>...</dependency>} 块（含属性也吃，但这里依赖块无属性）。 */
    private static final Pattern DEP_BLOCK = Pattern.compile("<dependency\\b[^>]*>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern GROUP = Pattern.compile("<groupId>([^<]+)</groupId>");
    private static final Pattern ART = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern VER = Pattern.compile("<version>([^<]+)</version>");

    private PomDependencyUpgrader() {
    }

    /** 一次升级的结果。 */
    public record Result(boolean changed, String content, List<String> notes) {
    }

    /**
     * 把 pom 里命中的旧坐标 / 旧版本，改写为 SB3 兼容形态。
     *
     * @throws IllegalArgumentException 输入不是合法 XML（拒绝在解析不了的文件上做文本手术）
     * @throws IllegalStateException   改写后的 pom 不是合法 XML（自我保护：宁可失败也不交坏文件）
     */
    public static Result upgrade(String pomXml) {
        if (pomXml == null || pomXml.isBlank()) {
            throw new IllegalArgumentException("pom 内容为空");
        }
        if (!isWellFormed(pomXml)) {
            throw new IllegalArgumentException("pom 不是合法 XML，拒绝改写");
        }
        Matcher m = DEP_BLOCK.matcher(pomXml);
        StringBuffer sb = new StringBuffer();
        boolean changed = false;
        List<String> notes = new ArrayList<>();
        while (m.find()) {
            String block = m.group(0);
            String newBlock = rewriteBlock(block, notes);
            if (!newBlock.equals(block)) {
                changed = true;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(newBlock));
        }
        m.appendTail(sb);
        String result = sb.toString();
        if (!isWellFormed(result)) {
            throw new IllegalStateException("升级后的 pom 不是合法 XML，已放弃本次改写");
        }
        return new Result(changed, result, List.copyOf(notes));
    }

    /** 某份 pom 是否包含需要改写的旧坐标 / 旧版本（供 PLAN 判断是否插入 DEPENDENCY_UPGRADE 节点）。 */
    public static boolean anyUpgradeNeeded(String pomXml) {
        if (pomXml == null || !isWellFormed(pomXml)) {
            return false;
        }
        Matcher m = DEP_BLOCK.matcher(pomXml);
        while (m.find()) {
            String block = m.group(0);
            Matcher g = GROUP.matcher(block);
            Matcher a = ART.matcher(block);
            if (!g.find() || !a.find()) {
                continue;
            }
            String key = g.group(1).trim() + ":" + a.group(1).trim();
            if (COORDINATE_RENAMES.containsKey(key) || VERSION_BUMPS.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private static String rewriteBlock(String block, List<String> notes) {
        Matcher g = GROUP.matcher(block);
        Matcher a = ART.matcher(block);
        if (!g.find() || !a.find()) {
            return block;
        }
        String group = g.group(1).trim();
        String art = a.group(1).trim();
        String key = group + ":" + art;
        String newBlock = block;

        // 1) 坐标改名（保留原 version）
        String newKey = COORDINATE_RENAMES.get(key);
        if (newKey != null) {
            String[] parts = newKey.split(":", 2);
            newBlock = newBlock.replace("<groupId>" + group + "</groupId>", "<groupId>" + parts[0] + "</groupId>");
            newBlock = newBlock.replace("<artifactId>" + art + "</artifactId>", "<artifactId>" + parts[1] + "</artifactId>");
            notes.add("rename " + key + " -> " + newKey);
            key = newKey;
        }

        // 2) 版本提升（在改名后的坐标上判定，只动显式声明了 <version> 的依赖）
        String targetVer = VERSION_BUMPS.get(key);
        if (targetVer != null) {
            Matcher v = VER.matcher(newBlock);
            if (v.find()) {
                String curVer = v.group(1).trim();
                if (!curVer.equals(targetVer)) {
                    newBlock = newBlock.substring(0, v.start(1)) + targetVer + newBlock.substring(v.end(1));
                    notes.add("bump " + key + " " + curVer + " -> " + targetVer);
                }
            }
        }
        return newBlock;
    }

    /** 是否是合法 XML（关掉 DTD 与外部实体，避免联网取 DTD / XXE，与 PomDependencyInjector 同款）。 */
    private static boolean isWellFormed(String xml) {
        try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new org.xml.sax.InputSource(new java.io.StringReader("")));
            builder.setErrorHandler(null);
            builder.parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
