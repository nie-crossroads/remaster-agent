package com.remasteragent.tools.pom;

import com.remasteragent.tools.ast.Spring3BreakingApiScanner;
import com.remasteragent.tools.pom.JakartaArtifactCatalog.Artifact;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「Spring Boot 3 破坏性 API」→ 迁移后<b>必须补上的依赖坐标</b>的固化映射表。
 *
 * <h2>它补的是 {@link JakartaArtifactCatalog} 覆盖不到的那一半</h2>
 * <p>{@link JakartaArtifactCatalog} 回答「javax 换成 jakarta 后，pom 要加什么」；
 * 但还有一类缺口与命名空间无关 —— <b>框架换了底层实现，classpath 里得有新的库</b>。
 * 最典型的就是 {@code HttpComponentsClientHttpRequestFactory}：Spring Framework 6 把它底层
 * 从 Apache HttpClient 4 换成 HttpClient 5，源码改成 {@code org.apache.hc.client5.*} 之后，
 * pom 里若没有 {@code org.apache.httpcomponents.client5:httpclient5}，编译器会直接报
 * 「无法访问 org.apache.hc.client5.http.classic.HttpClient」—— 这类错误<b>改源码解决不了</b>，
 * 只能补依赖。</p>
 *
 * <h2>与 {@link Spring3BreakingApiScanner} 的分工</h2>
 * <p>扫描器说「哪些文件的哪些用法有问题」，本表说「这些用法对应要补什么依赖」。
 * 两者靠 {@code ruleId} 对齐，所以扫描器加规则而这里忘了补条目时，结果是「提示了但不补依赖」——
 * 至少不会瞎加依赖。反过来，这里出现的每条依赖都必须能追溯到某条规则。</p>
 *
 * <h2>为什么又是一张固化表</h2>
 * <p>与 {@link JakartaArtifactCatalog} 完全同一条理由：把「选版本」移出 Agent。
 * 版本锁死为常量，可单测、可归因，不随运行时间漂移。</p>
 *
 * <p>这里复用 {@link Artifact} 这个「构件坐标」类型 —— 它是通用的
 * {@code groupId:artifactId:version:scope} 四元组，并不是 jakarta 专用。</p>
 */
public final class SpringBoot3DependencyCatalog {

    /**
     * Apache HttpClient 5。
     *
     * <p>版本 5.2.x 与 Spring Boot 3.2 的 BOM 管理版本一致，显式声明是为了不依赖
     * parent 是否真的被升上去（PARENT_UPGRADE 可能因为执行器不可用而跳过）。</p>
     */
    public static final Artifact HTTPCLIENT5 =
            new Artifact("org.apache.httpcomponents.client5", "httpclient5", "5.2.1", null);

    /** 规则 → 该规则命中后必须补的依赖。 */
    private static final Map<String, Artifact> BY_RULE = Map.of(
            "SPRING6_HTTPCOMPONENTS_FACTORY", HTTPCLIENT5,
            "HTTPCLIENT4_LEGACY", HTTPCLIENT5
    );

    private SpringBoot3DependencyCatalog() {
    }

    /** 一批扫描命中对应的全部依赖（去重）。 */
    public static Set<Artifact> forFindings(Collection<Spring3BreakingApiScanner.Finding> findings) {
        Set<Artifact> result = new LinkedHashSet<>();
        if (findings == null) {
            return result;
        }
        for (Spring3BreakingApiScanner.Finding finding : findings) {
            Artifact artifact = BY_RULE.get(finding.ruleId());
            if (artifact != null) {
                result.add(artifact);
            }
        }
        return result;
    }

    /**
     * 全工程扫描结果里是否存在「需要补依赖」的命中。
     *
     * <p>供 {@code PlanNode} 判断要不要插 DEPENDENCY_UPGRADE 节点：一条都不需要时插了就是空跑。</p>
     */
    public static boolean anyNeeded(Map<String, List<Spring3BreakingApiScanner.Finding>> byFile) {
        if (byFile == null) {
            return false;
        }
        for (List<Spring3BreakingApiScanner.Finding> findings : byFile.values()) {
            if (!forFindings(findings).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
