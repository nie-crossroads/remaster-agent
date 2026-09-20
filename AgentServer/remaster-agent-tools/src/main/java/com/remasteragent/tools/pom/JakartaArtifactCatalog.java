package com.remasteragent.tools.pom;

import com.remasteragent.tools.ast.JdkRemovalScanner;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 从 JDK 移除的 {@code javax.*} 包 → Jakarta EE 构件坐标的<b>白名单映射表</b>。
 *
 * <h2>它解决的是「坐标迁移」（阶段 5 第④层）里最危险的一步</h2>
 * <p>当源码把 {@code javax.annotation.Resource} 改写成 {@code jakarta.annotation.Resource}（或模型改写）后，
 * pom 里<b>必须</b>有对应的 Jakarta 依赖，{@code mvn test} 才能编过。这一步是典型的「半确定」动作：
 * 该换谁、换到哪个坐标，由一张受控的映射表决定——而不是让模型去猜 Maven 坐标。</p>
 *
 * <h2>为什么版本被固化为常量、不查最新版</h2>
 * <p>与设计稿 {@code PHASE5_SDK_UPGRADE.md}「阶段 A」一致：<b>把「选版本」移出 Agent</b>。
 * 联网查 {@code versions-maven-plugin} 或 Maven Central 元数据会让结果随运行时间变化、不可复现、
 * 也没法单测。这里锁死的是 Jakarta EE 10 这一代（与 JDK 21 匹配的命名空间版本），
 * 是「确定的输入 → 确定的产出」，可单测、可归因。</p>
 *
 * <h2>白名单，不是全局替换</h2>
 * <p>只列出「从 JDK 移除、且有 Jakarta 替代坐标」的包。<b>刻意排除</b> {@code javax.crypto} /
 * {@code javax.sql} / {@code javax.naming} / {@code javax.annotation.processing} 这些<b>仍在 JDK 里</b>、
 * 不应换成 jakarta 的包——全局 {@code javax → jakarta} 替换会把它们也误伤，那是真实项目里最隐蔽的坑之一。
 * 对 {@code javax.xml.rpc} / {@code org.omg.*} 这类<b>整个被移除、没有 Jakarta 替代</b>的包，表里不收，
 * 因此不会注入任何依赖（它们本就无可注入，需人工或⑤类迁移处理）。</p>
 *
 * <h2>作用域（scope）的口径</h2>
 * <p>服务端 API（servlet / annotation / ws / bind / ejb …）标 {@code provided}：实现由容器或
 * Spring Boot starter 在运行时提供，编译期可见即可，避免污染运行时 classpath。
 * 只有 {@code jakarta.mail} 这类「本身就是实现 jar」的标 {@code compile}——它既是 API 也是实现。</p>
 */
public final class JakartaArtifactCatalog {

    /** Jakarta 构件坐标（artifactId 维度，不含 groupId 里的命名空间噪声）。 */
    public record Artifact(String groupId, String artifactId, String version, String scope) {
        /** 给人看的标识，如 {@code jakarta.servlet:jakarta.servlet-api:6.0.0:provided}。 */
        public String coords() {
            return groupId + ":" + artifactId + ":" + version
                    + (scope == null || scope.isBlank() || "compile".equals(scope) ? "" : ":" + scope);
        }
    }

    /**
     * 被移除的 {@code javax.*} 包前缀 → Jakarta 构件。
     * key 为包前缀：import 等于 key 或以 {@code key + "."} 开头即命中；
     * 多个 key 同时匹配时取<b>最长</b>前缀（如 {@code javax.xml.ws.soap} 优先于 {@code javax.xml.ws}）。
     *
     * <p>版本均对齐 Jakarta EE 10（与 JDK 21 命名空间一致）。scope 见类注释。</p>
     */
    private static final Map<String, Artifact> BY_PACKAGE = Map.ofEntries(
            Map.entry("javax.xml.ws", new Artifact("jakarta.xml.ws", "jakarta.xml.ws-api", "4.0.1", "provided")),
            Map.entry("javax.xml.ws.soap", new Artifact("jakarta.xml.ws", "jakarta.xml.ws-api", "4.0.1", "provided")),
            Map.entry("javax.jws", new Artifact("jakarta.jws", "jakarta.jws-api", "3.0.0", "provided")),
            Map.entry("javax.xml.bind", new Artifact("jakarta.xml.bind", "jakarta.xml.bind-api", "4.0.2", "provided")),
            Map.entry("javax.xml.soap", new Artifact("jakarta.xml.soap", "jakarta.xml.soap-api", "3.0.2", "provided")),
            Map.entry("javax.activation", new Artifact("jakarta.activation", "jakarta.activation-api", "2.1.3", "provided")),
            Map.entry("javax.annotation", new Artifact("jakarta.annotation", "jakarta.annotation-api", "2.1.1", "provided")),
            Map.entry("javax.transaction", new Artifact("jakarta.transaction", "jakarta.transaction-api", "2.0.1", "provided")),
            Map.entry("javax.persistence", new Artifact("jakarta.persistence", "jakarta.persistence-api", "3.1.0", "provided")),
            Map.entry("javax.validation", new Artifact("jakarta.validation", "jakarta.validation-api", "3.0.2", "provided")),
            Map.entry("javax.servlet", new Artifact("jakarta.servlet", "jakarta.servlet-api", "6.0.0", "provided")),
            Map.entry("javax.ejb", new Artifact("jakarta.ejb", "jakarta.ejb-api", "4.0.1", "provided")),
            Map.entry("javax.mail", new Artifact("com.sun.mail", "jakarta.mail", "2.0.1", "compile"))
    );

    /** 仍在 JDK 内、<b>不应</b>被当成「需换 jakarta」的包前缀。 */
    private static final Set<String> EXCLUDED_PREFIXES = Set.of(
            "javax.annotation.processing", "javax.annotation.meta",
            "javax.crypto", "javax.sql", "javax.naming", "javax.net",
            "javax.xml.parsers", "javax.xml.transform", "javax.xml.xpath",
            "javax.xml.stream", "javax.xml.datatype", "javax.xml.namespace",
            "javax.script", "javax.swing", "javax.xml.crypto");

    private JakartaArtifactCatalog() {
    }

    /**
     * 把一个 {@code javax.*} import 解析成要注入的 Jakarta 构件。
     *
     * @return 命中白名单返回构件；仍在 JDK 里、或没有 Jakarta 替代的返回空
     */
    public static Optional<Artifact> forImport(String importFqn) {
        if (importFqn == null || importFqn.isBlank()) {
            return Optional.empty();
        }
        for (String excluded : EXCLUDED_PREFIXES) {
            if (importFqn.equals(excluded) || importFqn.startsWith(excluded + ".")) {
                return Optional.empty();
            }
        }
        Artifact hit = null;
        int hitLen = -1;
        for (Map.Entry<String, Artifact> entry : BY_PACKAGE.entrySet()) {
            String pkg = entry.getKey();
            if (importFqn.equals(pkg) || importFqn.startsWith(pkg + ".")) {
                if (pkg.length() > hitLen) {
                    hit = entry.getValue();
                    hitLen = pkg.length();
                }
            }
        }
        return Optional.ofNullable(hit);
    }

    /**
     * 一批 import 对应的全部 Jakarta 构件（去重）。
     *
     * <p>典型用法：节点扫描某模块子树收集到的所有被移除 import，交给本方法得到「该模块需注入的依赖集合」。
     */
    public static Set<Artifact> artifactsForImports(java.util.Collection<String> importFqns) {
        Set<Artifact> result = new LinkedHashSet<>();
        if (importFqns == null) {
            return result;
        }
        for (String fqn : importFqns) {
            forImport(fqn).ifPresent(result::add);
        }
        return result;
    }

    /**
     * 整个工程的移除风险地图里，是否存在「需要注入 Jakarta 依赖」的 import。
     *
     * <p>供 {@code PlanNode} 判断要不要插 {@code DEPENDENCY_UPGRADE} 节点：没有任何移除包能映射到
     * Jakarta 构件时，插了节点也是空跑，白占一条 DAG。
     */
    public static boolean anyArtifactNeeded(JdkRemovalScanner.ProjectRemovalRisks risks) {
        if (risks == null || risks.byFile() == null) {
            return false;
        }
        for (var entry : risks.byFile().entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            for (JdkRemovalScanner.RemovalRisk risk : entry.getValue()) {
                if (forImport(risk.importFqn()).isPresent()) {
                    return true;
                }
            }
        }
        return false;
    }
}
