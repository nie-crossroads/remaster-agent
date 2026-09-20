package com.remasteragent.core.engine.node;

import com.remasteragent.common.agent.ParentUpgradeResult;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.core.engine.NodeContext;
import com.remasteragent.core.engine.NodeExecutor;
import com.remasteragent.core.engine.NodeOutcome;
import com.remasteragent.core.rag.SourceFiles;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.tools.diff.UnifiedDiffGenerator;
import com.remasteragent.tools.hash.ContentHash;
import com.remasteragent.tools.pom.SpringBootParentUpgrader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PARENT_UPGRADE 节点：把 {@code spring-boot-starter-parent} / BOM 升级到目标大版本 —— <b>不调模型</b>。
 *
 * <h2>为什么要这个节点</h2>
 * <p>Spring Boot 2.7 → 3.x 是「一处牵一片」的变更：换掉 parent 版本，整个 BOM 托管的
 * {@code spring-boot-starter-*} 依赖会从 <b>javax</b> 命名空间跳到 <b>jakarta</b> 命名空间，
 * 同时强制 Java 17+。它是第④层（坐标迁移：注入 jakarta 依赖）和第⑤层（用法迁移）能成立的前提——
 * 不先升 parent，注入的 {@code jakarta.*} 依赖只会跟旧 SB2 的 {@code javax} 栈冲突。</p>
 *
 * <h2>确定性，不猜</h2>
 * <p>目标版本是固化常量（{@link SpringBootParentUpgrader#TARGET_VERSION}），由
 * {@link SpringBootParentUpgrader} 的纯文本替换完成，不查 Maven Central、不联网。</p>
 *
 * <h2>与 POM_REWRITE / DEPENDENCY_UPGRADE 的关系</h2>
 * <p>三者都改 pom、互不重叠（一个动编译级别、一个动 parent/BOM、一个动 dependencies），顺序由 DAG 保证：
 * POM_REWRITE（编译级别）→ 本节点（parent/BOM）→ DEPENDENCY_UPGRADE（jakarta 依赖）→ REWRITE。
 * 重放（checkpoint 续跑）时三者的产物都会被重放。</p>
 *
 * <h2>多模块</h2>
 * <p>只有真正声明了 {@code spring-boot-starter-parent} 的 pom（通常是根 pom）会被改动；
 * 子模块继承 parent，不重复升级。构建插件 {@code spring-boot-maven-plugin} 若显式声明了版本，也一并升级。</p>
 */
@Component
public class ParentUpgradeNode implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(ParentUpgradeNode.class);

    /** 任务级单节点，键里不带文件 —— 一次任务只升级一次 parent（跨所有模块）。 */
    public static final String NODE_KEY = "parent_upgrade";

    private final TaskStore taskStore;

    @Autowired
    public ParentUpgradeNode(TaskStore taskStore) {
        this.taskStore = taskStore;
    }

    @Override
    public NodeType type() {
        return NodeType.PARENT_UPGRADE;
    }

    @Override
    public NodeOutcome execute(NodeContext context) {
        Path workspace = context.workspace().normalize();

        List<Path> poms;
        try {
            poms = SourceFiles.listPomFiles(workspace);
        } catch (Exception e) {
            return NodeOutcome.fail("枚举 pom.xml 失败: " + e.getMessage());
        }
        if (poms.isEmpty()) {
            return NodeOutcome.fail("工程里找不到 pom.xml —— 父工程升级只支持 Maven 工程");
        }

        List<ParentUpgradeResult.FileChange> changes = new ArrayList<>();
        for (Path pom : poms) {
            String relative = SourceFiles.relativePath(workspace, pom);

            String original;
            try {
                original = Files.readString(pom, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return NodeOutcome.fail("读取 " + relative + " 失败: " + e.getMessage());
            }

            // 只动「声明了 spring-boot-starter-parent 且大版本 < 3」的 pom；子模块继承 parent，不动
            if (!SpringBootParentUpgrader.needsUpgrade(original)) {
                continue;
            }

            SpringBootParentUpgrader.Result result;
            try {
                result = SpringBootParentUpgrader.upgrade(original);
            } catch (IllegalArgumentException | IllegalStateException e) {
                return NodeOutcome.fail(relative + " 无法升级 parent: " + e.getMessage());
            }
            if (!result.changed()) {
                continue;
            }

            try {
                Files.writeString(pom, result.content(), StandardCharsets.UTF_8);
                String diff = UnifiedDiffGenerator.generate(original, result.content(), relative);
                taskStore.insertPatch(context.node().id(), relative, diff, ContentHash.sha256(original));
            } catch (IOException e) {
                return NodeOutcome.fail("写入 " + relative + " 失败: " + e.getMessage());
            }

            changes.add(new ParentUpgradeResult.FileChange(relative, result.content(),
                    String.join("; ", result.changes())));
            log.info("已升级 Spring Boot parent [{}]: {}", relative, result.changes());
        }

        log.info("PARENT_UPGRADE 完成: 扫描 {} 份 pom，升级 {} 份", poms.size(), changes.size());
        return NodeOutcome.ok(new ParentUpgradeResult(poms.size(), List.copyOf(changes)));
    }
}
