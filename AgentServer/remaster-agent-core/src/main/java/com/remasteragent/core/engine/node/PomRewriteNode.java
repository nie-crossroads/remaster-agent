package com.remasteragent.core.engine.node;

import com.remasteragent.common.agent.PomRewriteResult;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.core.engine.NodeContext;
import com.remasteragent.core.engine.NodeExecutor;
import com.remasteragent.core.engine.NodeOutcome;
import com.remasteragent.core.rag.SourceFiles;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.tools.diff.UnifiedDiffGenerator;
import com.remasteragent.tools.hash.ContentHash;
import com.remasteragent.tools.pom.PomJavaVersionRewriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * POM_REWRITE 节点：把工程里的 {@code pom.xml} 编译级别抬到目标 JDK —— <b>不调模型</b>。
 *
 * <h2>为什么这一步不能交给大模型</h2>
 * <p>改编译级别是「有唯一正确答案」的动作：{@code <maven.compiler.release>8</...>} 就是要变成
 * 目标值。让模型去改，它会顺手「优化」一堆无关的东西（升级依赖版本、重排插件、
 * 甚至加上它记得的某个 parent），而这些改动没有任何验证兜底 ——
 * 沙箱只会说「构建失败」，却指不出是哪一处顺手改坏的。
 * 确定性的事用确定性代码做，这是本项目在护栏上的同一条纪律。
 *
 * <h2>为什么它排在所有 REWRITE 之前</h2>
 * <p>模型按提示词产出的 record / 文本块是 Java 17+ 语法，在 {@code release=8} 的工程里会被
 * {@code javac} 直接拒收。若先改代码后改 pom，每个文件都会「改对了但编译不过」，
 * 白烧整轮回退配额，而且失败反馈会把模型引向「你的代码写错了」这个错误方向。
 *
 * <h2>它只做编译级别，不做依赖升级</h2>
 * <p>依赖 SDK 版本跃迁需要联网的版本元数据（哪个版本存在、有没有破坏性变更），
 * 属于另一件事。把两件事塞进一个节点，会让「升级失败」这件事无法归因。
 *
 * <p>产出里的 {@code newContent} 是刻意保留的：沙箱重建时靠它重放（见
 * {@link PomRewriteResult} 的说明）。
 */
@Component
public class PomRewriteNode implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(PomRewriteNode.class);

    /** 任务级单节点，键里不带文件 —— 一次任务只升级一次编译级别。 */
    public static final String NODE_KEY = "pom_rewrite";

    private final TaskStore taskStore;

    public PomRewriteNode(TaskStore taskStore) {
        this.taskStore = taskStore;
    }

    @Override
    public NodeType type() {
        return NodeType.POM_REWRITE;
    }

    @Override
    public NodeOutcome execute(NodeContext context) {
        int targetJdk = context.task().targetJdk();
        Path workspace = context.workspace().normalize();

        List<Path> poms;
        try {
            poms = SourceFiles.listPomFiles(workspace);
        } catch (Exception e) {
            return NodeOutcome.fail("枚举 pom.xml 失败: " + e.getMessage());
        }
        if (poms.isEmpty()) {
            return NodeOutcome.fail("工程里找不到 pom.xml —— 编译级别升级只支持 Maven 工程，"
                    + "Gradle / 纯 javac 工程请先用 Gradle 的 java.version 或 --release 自行升级");
        }

        List<PomRewriteResult.FileChange> changes = new ArrayList<>();
        for (Path pom : poms) {
            String relative = SourceFiles.relativePath(workspace, pom);
            String original;
            try {
                original = Files.readString(pom, StandardCharsets.UTF_8);
            } catch (Exception e) {
                return NodeOutcome.fail("读取 " + relative + " 失败: " + e.getMessage());
            }

            PomJavaVersionRewriter.Result result;
            try {
                result = PomJavaVersionRewriter.rewrite(original, targetJdk);
            } catch (IllegalArgumentException | IllegalStateException e) {
                // 解析不了就不动它 —— 但也绝不放过：一份改不了的 pom 意味着这个工程升不上去，
                // 继续往下跑只会让每个文件的 VERIFY 都失败，且原因指向代码而不是这里
                return NodeOutcome.fail(relative + " 无法改写: " + e.getMessage());
            }
            if (!result.changed()) {
                log.info("pom 无需改动（编译级别已达标或已是指定值）: {}", relative);
                continue;
            }

            try {
                Files.writeString(pom, result.content(), StandardCharsets.UTF_8);
                String diff = UnifiedDiffGenerator.generate(original, result.content(), relative);
                taskStore.insertPatch(context.node().id(), relative, diff, ContentHash.sha256(original));
            } catch (Exception e) {
                return NodeOutcome.fail("写入 " + relative + " 失败: " + e.getMessage());
            }

            String summary = String.join("; ", result.changes());
            changes.add(new PomRewriteResult.FileChange(relative, result.content(), summary));
            log.info("编译级别已升级 [{}] targetJdk={} {}", relative, targetJdk, summary);
        }

        List<String> changedPaths = changes.stream().map(PomRewriteResult.FileChange::filePath).toList();
        log.info("POM_REWRITE 完成: 扫描 {} 份 pom，改写 {} 份 → JDK {}", poms.size(), changes.size(), targetJdk);
        return NodeOutcome.ok(new PomRewriteResult(targetJdk, poms.size(), changedPaths, List.copyOf(changes)));
    }
}
