package com.remasteragent.tools.ast;

import com.remasteragent.tools.pom.JakartaArtifactCatalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 检测源码里「仍残留的、本应被迁移走的旧 import」。
 *
 * <h2>判定口径与迁移规则完全一致，分两类</h2>
 * <ol>
 *   <li><b>JDK 移除包</b>（{@link JdkRemovalScanner}）：{@code javax.annotation}（含 {@code @Resource}）、
 *       {@code javax.persistence}、{@code javax.xml.ws} 等 JDK 11 起从 JDK 移除、升级后必编译失败的包；</li>
 *   <li><b>javax → jakarta 白名单项</b>（{@link JakartaArtifactCatalog}）：{@code javax.validation} 等
 *       仍在 JDK 里、但命名空间需改成 jakarta 的包。</li>
 * </ol>
 * 两者都刻意<b>排除</b>仍在 JDK 内、不应改写的包（{@code javax.crypto}/{@code javax.sql}/
 * {@code javax.xml.parsers}/{@code javax.annotation.processing} 等），命中即误报。
 *
 * <h2>这是「Java 工程壁垒兜住 LLM 不确定性」的又一道确定性安全网</h2>
 * <p>模型可能漏改某个 import，规则不能漏。{@link #leftoverLegacyImports(String)} 同时被两处使用：
 * <ul>
 *   <li>{@code RewriteNode} 用它把「改写后仍残留旧 import」判为改写失败，并带<b>精确</b>反馈
 *       （漏了哪些 import）重跑，而不是等到整仓 VERIFY 编译失败才暴露真因；</li>
 *   <li>{@code DagScheduler} 在整仓 VERIFY 失败后也用它精准挑出「工作目录里仍含旧 import 的文件」
 *       做针对性重跑，干净文件不重跑（避免 +0/-0 死循环）。</li>
 * </ul>
 * 把同一套判定收在这里，两处口径永远一致 —— 否则「模型漏改」的判定在改写期与验证期各有一套，
 * 迟早对不上。
 */
public final class LegacyImportDetector {

    private LegacyImportDetector() {
    }

    /**
     * 源码里仍残留的、本应迁移走的旧 import 全限定名清单（去重，保序）。
     *
     * <p>返回空表示已迁移干净。解析失败（拿不到 import 清单）时返回空列表，
     * 交由护栏 / 整仓编译兜底，而不是误判阻断改写。</p>
     */
    public static List<String> leftoverLegacyImports(String source) {
        Set<String> leftover = new LinkedHashSet<>();
        // 1) JDK 移除包 —— 命中即升级后编译失败（如 javax.annotation.Resource）
        for (JdkRemovalScanner.RemovalRisk risk : JdkRemovalScanner.analyze(source)) {
            leftover.add(risk.importFqn());
        }
        // 2) javax → jakarta 白名单项 —— JDK 里还在、但命名空间需改名（如 javax.validation）。
        //    用 Set 去重：javax.annotation.Resource 既命中 JDK 移除包，也命中白名单项。
        try {
            for (String imp : JavaSourceAnalyzer.imports(source)) {
                if (JakartaArtifactCatalog.forImport(imp).isPresent()) {
                    leftover.add(imp);
                }
            }
        } catch (Exception e) {
            // 解析失败时不补充白名单项：交由护栏/整仓编译兜底，避免误判阻断改写
        }
        return new ArrayList<>(leftover);
    }

    /** 是否仍残留需迁移的旧 import。 */
    public static boolean hasLeftover(String source) {
        return !leftoverLegacyImports(source).isEmpty();
    }
}
