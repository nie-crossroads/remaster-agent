package com.remasteragent.eval.catalog;

import com.remasteragent.eval.support.RepoRoot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用例清单的契约测试 —— 直接校验仓库里那份真实的 {@code catalog.yaml}。
 *
 * <p>刻意不造假数据：「清单能不能解析」这件事的价值全在真实文件上。
 * 这份测试是评测集的第一道防线，它要拦住的是这几类会让整批结果失真的错误：
 * <ul>
 *   <li>id 重复 —— 报告里两行同名，聚合时分不清是哪条；</li>
 *   <li>{@code entryFile} 不以 {@code .java} 结尾 —— API 会 400，表现为「提交失败」，
 *       而真正的锅在 catalog；</li>
 *   <li>就绪用例指向不存在的文件 —— 同上，会让「任务失败」和「样本写错」混在一起；</li>
 *   <li>就绪用例里一条负样本都没有 —— 那样成功率没有分母意义。</li>
 * </ul>
 */
class CaseCatalogTest {

    private static CaseCatalog catalog;
    private static Path repoRoot;

    @BeforeAll
    static void loadCatalog() {
        repoRoot = RepoRoot.locateFromWorkingDirectory();
        catalog = CaseCatalog.load(RepoRoot.catalogFile(repoRoot));
    }

    @Test
    @DisplayName("清单能解析，且每条用例的 id 唯一")
    void catalogParsesWithUniqueIds() {
        List<String> ids = catalog.ids();

        assertFalse(ids.isEmpty(), "清单不该是空的");
        assertEquals(ids.size(), ids.stream().distinct().count(), "用例 id 必须唯一");
    }

    @Test
    @DisplayName("每条用例的 entryFile 都以 .java 结尾（否则 API 直接 400）")
    void everyEntryFileEndsWithJava() {
        for (EvalCase evalCase : catalog.all()) {
            assertTrue(evalCase.entryFile().endsWith(".java"),
                    evalCase.id() + " 的 entryFile 不是 .java: " + evalCase.entryFile());
            assertTrue(evalCase.targetJdk() >= 17, evalCase.id() + " 的 targetJdk 低于 17");
        }
    }

    @Test
    @DisplayName("就绪用例指向的工程目录、pom.xml、目标文件都真实存在")
    void readyCasesPointAtRealFiles() {
        assertTrue(catalog.runnable().size() > 0, "至少要有一条就绪用例");
        assertEquals(List.of(), catalog.validateReady(repoRoot),
                "就绪用例的目录自检不该有问题");
    }

    @Test
    @DisplayName("每条就绪用例的工程都真的含 pom.xml")
    void readyProjectsHavePoms() {
        for (EvalCase evalCase : catalog.runnable()) {
            Path pom = evalCase.projectPath(repoRoot).resolve("pom.xml");
            assertTrue(Files.isRegularFile(pom), evalCase.id() + " 的工程缺 pom.xml: " + pom);
        }
    }

    @Test
    @DisplayName("就绪用例里必须有负样本 —— 否则成功率没有分母意义")
    void readySetContainsNegatives() {
        List<EvalCase> readyNegatives = catalog.negatives().stream().filter(EvalCase::ready).toList();

        assertFalse(readyNegatives.isEmpty(),
                "至少要有一条就绪的负样本：只跑正样本的评测集无法区分"
                        + "「Agent 真的会改」与「Agent 永远报成功」");
    }

    @Test
    @DisplayName("计划中的用例不计入「就绪」，也不会被 runner 选中")
    void plannedCasesAreExcludedFromRunnable() {
        assertFalse(catalog.planned().isEmpty(), "清单里应当同时登记计划中的用例（作为路线图）");

        List<String> runnableIds = catalog.runnable().stream().map(EvalCase::id).toList();
        for (EvalCase planned : catalog.planned()) {
            assertFalse(runnableIds.contains(planned.id()),
                    planned.id() + " 标了 ready=false，却出现在就绪集合里");
        }
        assertEquals(catalog.all().size(), catalog.runnable().size() + catalog.planned().size());
    }

    @Test
    @DisplayName("按 id 取用例：取得到用 require，取不到要抛而不是静默返回空")
    void requireFailsLoudlyOnUnknownId() {
        EvalCase first = catalog.runnable().get(0);
        assertEquals(first.id(), catalog.require(first.id()).id());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> catalog.require("this-id-does-not-exist"));
        assertTrue(error.getMessage().contains("this-id-does-not-exist"));
    }

    @Test
    @DisplayName("每个工程都被至少一条用例引用，不存在「登记了工程却没有用例」")
    void everyProjectIsReferenced() {
        for (String project : catalog.projects()) {
            assertFalse(project.isBlank(), "工程名不能为空");
            assertFalse(project.startsWith("/") || project.contains(":"),
                    "工程路径必须是相对仓库根的形式: " + project);
        }
    }
}
