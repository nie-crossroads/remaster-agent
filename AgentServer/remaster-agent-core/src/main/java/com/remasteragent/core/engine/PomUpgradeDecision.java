package com.remasteragent.core.engine;

import com.remasteragent.core.rag.SourceFiles;
import com.remasteragent.tools.pom.PomJavaVersionRewriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 「这个工程要不要先升编译级别」的判定 —— 编排层唯一一处做这个判断的地方。
 *
 * <p>抽成独立类而不是各写一遍，理由是它被两个拓扑分支共用：
 * 有 PLAN 时由 {@code PlanNode} 决定是否插节点，没有 PLAN（阶段 1 三步拓扑）时由
 * {@code DagScheduler} 决定。两处若各写一份，最可能的漂移是「一处认为需要、另一处认为不需要」，
 * 表现为同一个工程换种拓扑就升级失败 —— 而这类不一致极难在单测里被发现。
 *
 * <h2>为什么扫全仓而不是只看根 pom</h2>
 * <p>判定与执行必须看同一批文件：{@code PomRewriteNode} 改的是<b>全仓每一份</b> pom.xml，
 * 而这里若只读根 pom，多模块工程就会出现「根 pom 已 release=21、子模块还是 8」被判成
 * 「无需升级」，于是根本不插节点 —— 子模块的编译级别永远升不上去。
 * 后果还会以误导性的样子出现：模型按提示词写出 record / 文本块，子模块编译失败，
 * 反馈却指向「你的代码写错了」，白烧整轮回退配额。
 *
 * <p>代价是这里要 walk 一次目录。pom 文件数量极少，与「一次全量 mvn test」相比可以忽略。
 */
public final class PomUpgradeDecision {

    private static final Logger log = LoggerFactory.getLogger(PomUpgradeDecision.class);

    private PomUpgradeDecision() {
    }

    /**
     * 工程是否需要升级编译级别。
     *
     * @return 需要时返回一句可读的原因（写进日志，回答「为什么多了一个节点」），
     *         并<b>点名是哪一份 pom</b> 拖低了级别；不需要、或无法判定时返回 {@link Optional#empty()}
     */
    public static Optional<String> reason(Path workspace, int targetJdk) {
        if (workspace == null || !Files.isRegularFile(workspace.resolve("pom.xml"))) {
            // 非 Maven 工程不在规划阶段抢话：让它按常规流程失败，报错信息由 REWRITE/VERIFY 给出，
            // 比在这里抛一句「找不到 pom」更贴近用户实际看到的现场
            return Optional.empty();
        }

        List<Path> poms;
        try {
            poms = SourceFiles.listPomFiles(workspace);
        } catch (Exception e) {
            log.warn("枚举 pom.xml 失败，跳过编译级别判定: {}", e.getMessage());
            return Optional.empty();
        }

        // 两个候选原因，按信息量排序取第一个：「有明确级别且偏低」比「压根没声明级别」更值得先报
        String lowestPath = null;
        int lowestLevel = Integer.MAX_VALUE;
        String undeclaredPath = null;

        for (Path pom : poms) {
            String relative = SourceFiles.relativePath(workspace, pom);
            String content;
            try {
                content = Files.readString(pom, StandardCharsets.UTF_8);
            } catch (Exception e) {
                // 读不了就跳过这一份：判定是「要不要插节点」的优化，不该因为一份怪 pom
                // 把整条链路卡在这里。真有问题时 POM_REWRITE 会带着具体文件名失败
                log.warn("读取 {} 失败，跳过它对判定的影响: {}", relative, e.getMessage());
                continue;
            }
            if (!PomJavaVersionRewriter.needsUpgrade(content, targetJdk)) {
                continue;
            }
            Integer min = PomJavaVersionRewriter.readLevel(content).minLevel();
            if (min == null) {
                if (undeclaredPath == null) {
                    undeclaredPath = relative;
                }
            } else if (min < lowestLevel) {
                lowestLevel = min;
                lowestPath = relative;
            }
        }

        if (lowestPath != null) {
            return Optional.of(lowestPath + " 编译级别 " + lowestLevel + " 低于目标 JDK " + targetJdk);
        }
        if (undeclaredPath != null) {
            return Optional.of(undeclaredPath + " 未声明可解析的编译级别，将插入 maven.compiler.release=" + targetJdk);
        }
        return Optional.empty();
    }
}
