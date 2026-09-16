package com.remasteragent.eval.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.remasteragent.eval.util.EvalJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 用例清单 —— 读 {@code examples/eval/catalog.yaml}。
 *
 * <h2>「就绪」与「计划」必须分开记</h2>
 * <p>catalog 同时登记「已经能跑的样本」和「还没写的样本」，因为清单本身就是路线图。
 * 但<ul>
 *   <li>验收标准里「≥ 20 个可迁移目标」只算 {@code ready: true} 的条目 ——
 *       否则「条目数够了但一半跑不了」会变成一种很容易发生的自欺；</li>
 *   <li>基线预检只对就绪条目做，计划中的目录都还没建，去校验存在性只会刷屏报错。</li>
 * </ul>
 *
 * <p>文件不存在、字段非法、id 重复都直接抛 —— 评测集是「可复现」的载体，
 * 它本身含糊不清的话，报告再漂亮也没有意义。
 */
public final class CaseCatalog {

    private final Path source;
    private final List<EvalCase> cases;

    private CaseCatalog(Path source, List<EvalCase> cases) {
        this.source = source;
        this.cases = List.copyOf(cases);
    }

    /** catalog.yaml 的原始形状。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CatalogFile(int version, List<EvalCase> cases) {
    }

    /**
     * 从 YAML 载入。
     *
     * @throws IllegalArgumentException 文件缺失 / 格式非法 / id 重复
     */
    public static CaseCatalog load(Path yamlFile) {
        if (!Files.isRegularFile(yamlFile)) {
            throw new IllegalArgumentException("找不到用例清单: " + yamlFile.toAbsolutePath());
        }
        CatalogFile parsed;
        try {
            parsed = EvalJson.YAML.readValue(yamlFile.toFile(), CatalogFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException("解析用例清单失败: " + yamlFile, e);
        }
        if (parsed == null || parsed.cases() == null || parsed.cases().isEmpty()) {
            throw new IllegalArgumentException("用例清单里没有任何用例: " + yamlFile);
        }
        if (parsed.version() != 1) {
            throw new IllegalArgumentException(
                    "不认识的清单版本 " + parsed.version() + "（本 harness 只认 1）: " + yamlFile);
        }

        Map<String, EvalCase> byId = new LinkedHashMap<>();
        for (EvalCase evalCase : parsed.cases()) {
            EvalCase previous = byId.putIfAbsent(evalCase.id(), evalCase);
            if (previous != null) {
                throw new IllegalArgumentException("用例 id 重复: " + evalCase.id());
            }
        }
        return new CaseCatalog(yamlFile, new ArrayList<>(byId.values()));
    }

    public Path source() {
        return source;
    }

    /** 全部用例（含计划中）。 */
    public List<EvalCase> all() {
        return cases;
    }

    /** 已就绪、可以真的提交的用例。 */
    public List<EvalCase> runnable() {
        return cases.stream().filter(EvalCase::ready).toList();
    }

    /** 计划中、样本还没写的用例。 */
    public List<EvalCase> planned() {
        return cases.stream().filter(c -> !c.ready()).toList();
    }

    /** 全部负样本（就绪与否都算），用于在报告里说明「分母的意义」。 */
    public List<EvalCase> negatives() {
        return cases.stream().filter(EvalCase::isNegative).toList();
    }

    public Optional<EvalCase> find(String id) {
        return cases.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    /**
     * 按 id 取用例，取不到就抛 —— 命令行敲错 id 时应该立刻报错，
     * 而不是安静地跑 0 条用例、再产出一张空报告。
     */
    public EvalCase require(String id) {
        return find(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "用例清单里没有 id=" + id + "；可用 id: " + ids()));
    }

    public List<String> ids() {
        return cases.stream().map(EvalCase::id).toList();
    }

    /** 用例涉及的全部工程（去重，保持出现顺序）—— 基线预检按工程做，不是按用例做。 */
    public Set<String> projects() {
        return new LinkedHashSet<>(cases.stream().map(EvalCase::project).toList());
    }

    /**
     * 就绪用例的目录级自检：工程目录存在、含 {@code pom.xml}、目标文件存在。
     *
     * <p>刻意在「提交任务之前」做这一步。否则一个路径写错的用例会表现为
     * 「任务 FAILED」，与「Agent 改不动这个文件」混在一起，污染的是结论。
     *
     * @return 问题清单；空表示全部就绪
     */
    public List<String> validateReady(Path repoRoot) {
        List<String> problems = new ArrayList<>();
        for (EvalCase evalCase : runnable()) {
            Path projectPath = evalCase.projectPath(repoRoot);
            if (!Files.isDirectory(projectPath)) {
                problems.add(evalCase.id() + ": 工程目录不存在 " + projectPath);
                continue;
            }
            if (!Files.isRegularFile(projectPath.resolve("pom.xml"))) {
                problems.add(evalCase.id() + ": 工程目录下没有 pom.xml " + projectPath);
            }
            Path entryPath = evalCase.entryFilePath(repoRoot);
            if (!Files.isRegularFile(entryPath)) {
                problems.add(evalCase.id() + ": 目标文件不存在 " + entryPath);
            }
        }
        return problems;
    }
}
