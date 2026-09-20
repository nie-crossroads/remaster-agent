package com.remasteragent.core.engine.node;

import com.remasteragent.common.agent.DependencyUpgradeResult;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.core.engine.NodeContext;
import com.remasteragent.core.engine.NodeExecutor;
import com.remasteragent.core.engine.NodeOutcome;
import com.remasteragent.core.rag.SourceFiles;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.tools.ast.JdkRemovalScanner;
import com.remasteragent.tools.ast.Spring3BreakingApiScanner;
import com.remasteragent.tools.diff.UnifiedDiffGenerator;
import com.remasteragent.tools.hash.ContentHash;
import com.remasteragent.tools.pom.JakartaArtifactCatalog;
import com.remasteragent.tools.pom.PomDependencyInjector;
import com.remasteragent.tools.pom.PomDependencyUpgrader;
import com.remasteragent.tools.pom.SpringBoot3DependencyCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DEPENDENCY_UPGRADE 节点：把工程迁到 SB3 所需的<b>依赖侧</b>改动一次性补齐 —— <b>不调模型</b>。
 * 它做两件事（阶段 5 第③/④层里确定性、可单测的那部分）：
 * <ol>
 *   <li>第④层：把 {@code javax.*→jakarta.*} 坐标迁移所需的 Jakarta 依赖注入 pom（由源码 import 扫描驱动）；</li>
 *   <li>第③层版本提升 + 第④层坐标改名：{@code mysql:mysql-connector-java → com.mysql:mysql-connector-j}、
 *       {@code mybatis-spring-boot-starter} 2.3.x → 3.x 等，按固化白名单改写。</li>
 * </ol>
 *
 * <h2>为什么要这个节点</h2>
 * <p>源码改写（REWRITE）把 {@code javax.annotation.Resource} 换成 {@code jakarta.annotation.Resource} 之后，
 * pom 里若没有对应的 {@code jakarta.annotation-api}，VERIFY 的 {@code mvn test} 会因「找不到符号」而失败。
 * 这个失败与「模型改写得好不好」无关，纯粹是依赖缺了 —— 把它交给确定性代码补上，才对得起
 * 「Java 工程壁垒兜住 LLM 不确定性」的定位。</p>
 *
 * <h2>怎么决定注入哪些依赖（确定性，不猜）</h2>
 * <p>直接复用 {@link JdkRemovalScanner} 的全工程扫描结果：凡是 import 了「从 JDK 移除」包的文件，
 * 其 import 经过 {@link JakartaArtifactCatalog} 白名单映射，得到要注入的 Jakarta 构件集合。
 * 坐标是固化的配置（见 {@link JakartaArtifactCatalog} 类注释），不查最新版、不联网。</p>
 *
 * <h2>多模块：每个 pom 只注入自己模块用到的依赖</h2>
 * <p>对每份 pom，只扫描它所在模块的子树（排除其下的嵌套模块），避免把 A 模块的依赖误注入 B 模块的 pom。
 * 单模块工程退化为「根 pom 注入全部所需依赖」。</p>
 *
 * <h2>与 POM_REWRITE 的关系</h2>
 * <p>两者都改 pom、互不相干（一个动编译级别、一个动 dependencies），顺序由 DAG 保证：
 * POM_REWRITE 先跑，本节点接着跑，最后才是 REWRITE。重放（checkpoint 续跑）时两者的产物都会被重放。</p>
 */
@Component
public class DependencyUpgradeNode implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(DependencyUpgradeNode.class);

    /** 任务级单节点，键里不带文件 —— 一次任务只注入一次依赖（跨所有模块）。 */
    public static final String NODE_KEY = "dependency_upgrade";

    private final TaskStore taskStore;
    private final JdkRemovalScanner removalScanner;

    @Autowired
    public DependencyUpgradeNode(TaskStore taskStore) {
        this(taskStore, JdkRemovalScanner.create());
    }

    public DependencyUpgradeNode(TaskStore taskStore, JdkRemovalScanner removalScanner) {
        this.taskStore = taskStore;
        this.removalScanner = removalScanner == null ? JdkRemovalScanner.create() : removalScanner;
    }

    @Override
    public NodeType type() {
        return NodeType.DEPENDENCY_UPGRADE;
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
            return NodeOutcome.fail("工程里找不到 pom.xml —— 依赖注入只支持 Maven 工程");
        }

        // 全工程扫一次移除风险，按模块子树分发到各自的 pom
        JdkRemovalScanner.ProjectRemovalRisks removalRisks = removalScanner.scan(workspace);
        // 再扫一次「Spring Boot 2→3 破坏性 API」：它们与命名空间无关，但换掉了底层库
        // （如 Spring 6 的 HttpComponentsClientHttpRequestFactory 只在 classpath 有 httpclient5 时才编得过）。
        // 这类缺口改源码没用，只能补依赖 —— 所以在本节点而不是 REWRITE 里处理。
        Map<String, List<Spring3BreakingApiScanner.Finding>> breakingHits =
                Spring3BreakingApiScanner.scanProject(workspace);

        List<DependencyUpgradeResult.FileChange> changes = new ArrayList<>();
        for (Path pom : poms) {
            String relative = SourceFiles.relativePath(workspace, pom);
            Path moduleRoot = pom.getParent();

            // 本模块下的嵌套模块目录（它们的 pom 各自处理，不要在本 pom 注入其依赖）
            Set<Path> nestedModuleDirs = poms.stream()
                    .map(Path::getParent)
                    .filter(dir -> !dir.equals(moduleRoot) && dir.startsWith(moduleRoot))
                    .collect(Collectors.toSet());

            // 收集本模块子树（排除嵌套模块）内、被移除 import 用到的包 → 映射成 Jakarta 构件
            Set<String> moduleImports = new LinkedHashSet<>();
            for (var entry : removalRisks.byFile().entrySet()) {
                Path file = workspace.resolve(entry.getKey());
                if (!file.startsWith(moduleRoot) || nestedModuleDirs.stream().anyMatch(file::startsWith)) {
                    continue;
                }
                for (JdkRemovalScanner.RemovalRisk risk : entry.getValue()) {
                    moduleImports.add(risk.importFqn());
                }
            }
            Set<JakartaArtifactCatalog.Artifact> needed = JakartaArtifactCatalog.artifactsForImports(moduleImports);

            // 本模块子树命中的框架破坏性 API → 需要补的底层库（如 httpclient5）
            Set<Spring3BreakingApiScanner.Finding> moduleFindings = new LinkedHashSet<>();
            for (var entry : breakingHits.entrySet()) {
                Path file = workspace.resolve(entry.getKey());
                if (!file.startsWith(moduleRoot) || nestedModuleDirs.stream().anyMatch(file::startsWith)) {
                    continue;
                }
                moduleFindings.addAll(entry.getValue());
            }
            needed.addAll(SpringBoot3DependencyCatalog.forFindings(moduleFindings));

            String original;
            try {
                original = Files.readString(pom, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return NodeOutcome.fail("读取 " + relative + " 失败: " + e.getMessage());
            }

            // 1) 坐标 / 版本规范化（阶段 5 第③层版本提升 + 第④层坐标改名）：mysql 坐标改名、mybatis 升 3.x 等。
            //    与 jakarta 注入互不依赖，先跑它，结果作为下一步的输入。
            PomDependencyUpgrader.Result coord;
            try {
                coord = PomDependencyUpgrader.upgrade(original);
            } catch (IllegalArgumentException | IllegalStateException e) {
                return NodeOutcome.fail(relative + " 依赖坐标/版本升级失败: " + e.getMessage());
            }
            String content = coord.content();

            // 2) jakarta 依赖注入（第④层）：仅在工程确实用到需 jakarta 依赖的移除 API 时才改
            PomDependencyInjector.Result inject;
            try {
                inject = PomDependencyInjector.inject(content, needed);
            } catch (IllegalArgumentException | IllegalStateException e) {
                return NodeOutcome.fail(relative + " 无法注入依赖: " + e.getMessage());
            }
            content = inject.content();

            if (!coord.changed() && !inject.changed()) {
                log.info("pom 无需改动（无坐标/版本升级、且已含所需 jakarta 依赖）: {}", relative);
                continue;
            }

            try {
                Files.writeString(pom, content, StandardCharsets.UTF_8);
                String diff = UnifiedDiffGenerator.generate(original, content, relative);
                taskStore.insertPatch(context.node().id(), relative, diff, ContentHash.sha256(original));
            } catch (IOException e) {
                return NodeOutcome.fail("写入 " + relative + " 失败: " + e.getMessage());
            }

            List<String> notes = new ArrayList<>(coord.notes());
            notes.addAll(inject.added());
            changes.add(new DependencyUpgradeResult.FileChange(relative, content, String.join("; ", notes)));
            log.info("已升级依赖 [{}]: {}", relative, notes);
        }

        log.info("DEPENDENCY_UPGRADE 完成: 扫描 {} 份 pom，注入 {} 份", poms.size(), changes.size());
        return NodeOutcome.ok(new DependencyUpgradeResult(poms.size(), List.copyOf(changes)));
    }
}
