package com.remasteragent.core.engine;

import com.remasteragent.common.agent.RewriteProposal;
import com.remasteragent.tools.ast.JavaSourceAnalyzer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 产出校验 —— 决定「模型交回来的东西算不算数」。
 *
 * <p>这是整个闭环里最容易被忽略、却最决定成败的一环。没有它，链路会退化成一个
 * 「让模型改代码」的 demo：模型可以改包名、换类名、返回半截文件、甚至回一段解释文字，
 * 而下游会照单全收，然后在沙箱里以各种诡异的方式失败，你会误以为是沙箱或模型的问题。
 *
 * <p>四条判据，从便宜到昂贵排列，任意一条不满足就直接判失败并触发 attempt+1：
 * <ol>
 *   <li>内容非空</li>
 *   <li><b>能通过 JavaParser 解析</b> —— 语法都不对的代码没必要进沙箱浪费一次执行</li>
 *   <li><b>包名不变</b> —— 模型偶尔会「顺手」整理包名，这在迁移场景里是灾难</li>
 *   <li><b>主类型名不变</b> —— 换类名会让所有引用它的文件一起编译失败</li>
 * </ol>
 *
 * <p>注意第 2 条同时起到了省钱的作用：解析在本地是毫秒级的，而一次沙箱执行要几十秒，
 * 用本地毫秒级检查拦掉明显非法的产出，是最划算的一笔优化。
 *
 * <h2>为什么这里<b>不</b>校验模型声称的文件路径</h2>
 * <p>模型返回的 {@code filePath} 根本不被信任 —— 写入位置由 {@code RewriteNode} 用
 * 服务端从 ANALYZE 拿到的路径决定，模型说什么都不影响落盘位置（也就天然堵死了
 * {@code ../../} 这类路径穿越）。既然它不影响任何行为，再对它做校验只会带来一个坏结果：
 * 模型写错路径时白白触发一次重试、多烧一轮 token，而那次重试对结果毫无改善。
 * 这是刻意留白，不是漏掉。
 */
public final class ProposalGuardrail {

    private ProposalGuardrail() {
    }

    /**
     * 校验结果。
     *
     * @param passed 是否通过
     * @param reason 未通过的原因，会作为失败反馈喂回下一次改写
     */
    public record GuardrailResult(boolean passed, String reason) {

        public static GuardrailResult ok() {
            return new GuardrailResult(true, "");
        }

        public static GuardrailResult violation(String reason) {
            return new GuardrailResult(false, reason);
        }
    }

    /**
     * 执行校验。
     *
     * @param expectedPackage 改写前的包名；为空表示默认包，此时不校验包名
     * @param expectedType    改写前的主类型名；为空表示源文件里没有顶层类型，此时不校验类型名
     * @param proposal        模型产出
     */
    /**
     * 校验（不含公开成员约束的兼容入口）。
     *
     * @see #check(String, String, List, RewriteProposal)
     */
    public static GuardrailResult check(String expectedPackage,
                                        String expectedType,
                                        RewriteProposal proposal) {
        return check(expectedPackage, expectedType, List.of(), proposal);
    }

    /**
     * 校验 —— 在原有「非空 / 可解析 / 主类型名不变 / 包名不变」四判据之外，
     * 增加「公开 API 不可被删」的判据。
     *
     * @param expectedPublicMembers 改写前目标文件的 public 成员签名快照
     *                              （由 {@link JavaSourceAnalyzer#publicMembers} 抽取）；
     *                              为空表示不校验公开 API
     */
    public static GuardrailResult check(String expectedPackage,
                                        String expectedType,
                                        List<String> expectedPublicMembers,
                                        RewriteProposal proposal) {
        if (proposal == null) {
            return GuardrailResult.violation("产出为空");
        }
        if (proposal.newContent() == null || proposal.newContent().isBlank()) {
            return GuardrailResult.violation("产出内容为空");
        }

        JavaSourceAnalyzer.ParsedHeader header;
        try {
            header = JavaSourceAnalyzer.parseHeader(proposal.newContent());
        } catch (Exception e) {
            return GuardrailResult.violation(
                    "产出无法被解析为合法 Java 源码：" + e.getMessage()
                            + "。请确保返回的是完整、语法正确的文件内容。");
        }

        if (expectedType != null && !expectedType.isBlank()
                && !expectedType.equals(header.primaryType())) {
            return GuardrailResult.violation(
                    "主类型名被改动了：期望 %s，实际 %s。类名不允许修改。"
                            .formatted(expectedType, header.primaryType().isEmpty() ? "(空)" : header.primaryType()));
        }

        if (expectedPackage != null && !expectedPackage.isBlank()
                && !expectedPackage.equals(header.packageName())) {
            return GuardrailResult.violation(
                    "包名被改动了：期望 %s，实际 %s。包名不允许修改。"
                            .formatted(expectedPackage, header.packageName().isEmpty() ? "(空)" : header.packageName()));
        }

        GuardrailResult publicApi = checkPublicMembers(expectedPublicMembers, proposal);
        if (!publicApi.passed()) {
            return publicApi;
        }

        return GuardrailResult.ok();
    }

    /**
     * 公开 API 契约判据：改写后必须仍保留改写前的每一个 public 成员签名。
     *
     * <p>模型偶尔会「顺手」删掉它以为没用的 public 方法/字段，但迁移场景下这些公开成员
     * 很可能是调用方或测试的依赖 —— 删掉会让下游编译失败，且这种失败要到沙箱里才暴露，
     * 反馈不指向真因。在本地毫秒级预筛拦掉，比白跑一次沙箱便宜得多。</p>
     *
     * <p>只比对「签名是否存在」，不比对实现：模型合法地改方法体是允许的，只要公开契约不变。</p>
     */
    private static GuardrailResult checkPublicMembers(List<String> expected, RewriteProposal proposal) {
        if (expected == null || expected.isEmpty()) {
            return GuardrailResult.ok();
        }
        List<String> removed;
        try {
            List<String> after = JavaSourceAnalyzer.publicMembers(proposal.newContent());
            Set<String> afterSet = new LinkedHashSet<>(after);
            removed = expected.stream()
                    .filter(member -> !afterSet.contains(member))
                    .toList();
        } catch (Exception e) {
            // 解析失败已由前面的判据拦截，这里理论上不会触发；兜底放过，不重复报解析错误
            return GuardrailResult.ok();
        }
        if (!removed.isEmpty()) {
            return GuardrailResult.violation(
                    "改写删除了公开成员（公开 API 是被调用方/测试依赖的契约，不允许删除或改签名）："
                            + String.join("、", removed)
                            + "。请保留这些公开成员的签名、仅调整其实现；若确需移除，请同时更新其调用方。");
        }
        return GuardrailResult.ok();
    }
}
