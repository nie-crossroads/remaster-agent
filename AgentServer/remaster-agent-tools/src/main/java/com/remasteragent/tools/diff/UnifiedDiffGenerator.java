package com.remasteragent.tools.diff;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 生成 unified diff。
 *
 * <p>这是「模型返回整文件内容」这一决策的配套实现：模型只负责产出完整的文件内容，
 * diff 由我们本地算。原因是模型拼 unified diff 极易错行 —— 上下文行数算错、行号偏移、
 * hunk 头写错，而错一行的补丁是<b>没法应用</b>的。让模型做它做不好的事，
 * 然后花大量精力去校验它的产出，是不划算的。
 *
 * <p>本地算 diff 的额外好处：diff 一定是与原文严格一致的，
 * 人工审查看到的就是真实会发生的改动，不存在「模型描述的和实际改的不一样」。
 */
public final class UnifiedDiffGenerator {

    /** diff 上下文行数，取 git 默认值，前端 Monaco 显示效果最自然。 */
    private static final int CONTEXT_LINES = 3;

    private UnifiedDiffGenerator() {
    }

    /**
     * 生成 unified diff。
     *
     * @param originalText 改写前内容
     * @param revisedText  改写后内容
     * @param filePath     文件路径，会显示在 diff 的 --- / +++ 头里
     * @return unified diff 文本；内容完全相同时返回空字符串
     */
    public static String generate(String originalText, String revisedText, String filePath) {
        if (originalText == null) {
            originalText = "";
        }
        if (revisedText == null) {
            revisedText = "";
        }
        if (originalText.equals(revisedText)) {
            return "";
        }

        List<String> original = splitLines(originalText);
        List<String> revised = splitLines(revisedText);

        Patch<String> patch = DiffUtils.diff(original, revised);
        List<String> unified = UnifiedDiffUtils.generateUnifiedDiff(
                "a/" + filePath, "b/" + filePath, original, patch, CONTEXT_LINES);

        return String.join("\n", unified);
    }

    /**
     * 按行切分，<b>保留末尾空行</b>。
     *
     * <p>不能用 {@code String.lines()} 或 {@code split("\n")} —— 它们会丢掉结尾的空行，
     * 于是「文件末尾加了一个空行」这种改动会在 diff 里凭空消失。
     */
    private static List<String> splitLines(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        return new ArrayList<>(Arrays.asList(normalized.split("\n", -1)));
    }

    /** 统计新增与删除行数，用于指标展示。 */
    public static DiffStat stat(String diff) {
        if (diff == null || diff.isBlank()) {
            return new DiffStat(0, 0);
        }
        int added = 0;
        int removed = 0;
        for (String line : diff.split("\n", -1)) {
            // 跳过 --- / +++ 两个文件头，否则会把它们算成增删
            if (line.startsWith("+++") || line.startsWith("---")) {
                continue;
            }
            if (line.startsWith("+")) {
                added++;
            } else if (line.startsWith("-")) {
                removed++;
            }
        }
        return new DiffStat(added, removed);
    }

    /**
     * 增删行数统计。
     *
     * @param added   新增行数
     * @param removed 删除行数
     */
    public record DiffStat(int added, int removed) {
        public String describe() {
            return "+" + added + " / -" + removed;
        }
    }
}
