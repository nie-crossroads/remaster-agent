package com.remasteragent.llm.rewrite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 从模型回复里把 JSON 对象抠出来。
 *
 * <p>为什么需要这个：即使 prompt 里明确要求「只返回 JSON」，实际模型仍然经常加上
 * ```json 代码块围栏、前置一句「好的，这是改写后的代码」、或者在末尾补一段解释。
 * 这些都属于正常行为而非故障，如果因此判定产出非法，会造成大量无谓的重试与成本浪费。
 *
 * <p>策略是：<b>在正文里找到第一个真正能解析成 JSON 对象的片段</b>，而不是要求整段回复恰好是 JSON。
 *
 * <h2>两个容易写错的地方</h2>
 * <ol>
 *   <li><b>括号配平必须感知字符串。</b> 代码内容里出现 {@code "}、{@code \\}、{@code {}} 是必然的。
 *       用「找最后一个大括号」或不做字符串状态跟踪的计数，会在任何一句含花括号的字符串上崩掉
 *       —— 比如 {@code {"newContent":"if (x) { return; }"}} 会被截成
 *       {@code {"newContent":"if (x) {} }。</li>
 *   <li><b>正文里的花括号不是候选起点。</b> 模型很爱写「注意 { 和 } 要成对」这类解释，
 *       甚至贴一段 {@code class A { }} 的旧代码示例 —— 而 {@code {}} 恰好是<b>合法的空 JSON 对象</b>。
 *       所以判据不能只是「配平且可解析」：还要确认它包含调用方期待的业务字段
 *       （见 {@link #extractObject(String, String...)} 的 {@code requiredKeys}）。
 *       少了这一条，模型在正文里贴一段带大括号的代码就会让抽取器返回 {@code {}}，
 *       随后被当成「解析成功但字段缺失」而白白重试一轮。</li>
 * </ol>
 */
public final class JsonExtractor {

    /**
     * 候选起点的尝试上限。
     *
     * <p>理论上「逐个候选试」最坏是 O(n²)。真实回复只有几十 KB，且花括号通常屈指可数，
     * 但一个失控的回复（比如模型抽风输出几万个 {@code \{}）不该把 CPU 打满。
     * 给个上限：超了就放弃，让调用方按「找不到 JSON」处理 —— 那种回复本来也没救了。
     */
    private static final int MAX_CANDIDATES = 64;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonExtractor() {
    }

    /**
     * 抽取正文里第一个「配平、可解析成 JSON 对象、且包含全部 {@code requiredKeys}」的片段。
     *
     * @param text         模型回复原文
     * @param requiredKeys 必须存在的顶层字段名。传空数组表示只要是个 JSON 对象就行 ——
     *                     但调用方通常应该传，否则正文里的旧代码示例（如 {@code {}}）会被误取
     * @return JSON 文本；找不到合格对象时返回 null
     */
    public static String extractObject(String text, String... requiredKeys) {
        if (text == null || text.isBlank()) {
            return null;
        }

        int attempts = 0;
        for (int start = text.indexOf('{'); start >= 0; start = text.indexOf('{', start + 1)) {
            if (++attempts > MAX_CANDIDATES) {
                return null;
            }
            String candidate = balancedObjectAt(text, start);
            if (candidate != null && isAcceptable(candidate, requiredKeys)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 从 {@code start}（必须指向 {@code \{}）开始找配平的对象。
     *
     * @return 完整的 JSON 片段；括号没配平（通常是 max_tokens 截断）时返回 null
     */
    private static String balancedObjectAt(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                // 只有字符串里的反斜杠才是转义符；字符串外的反斜杠不改变任何状态
                if (inString) {
                    escaped = true;
                }
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }

            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /** 配平的片段未必是我们要的 —— 必须能解析成对象，且带上调用方期待的字段。 */
    private static boolean isAcceptable(String candidate, String[] requiredKeys) {
        JsonNode node;
        try {
            node = MAPPER.readTree(candidate);
        } catch (Exception e) {
            return false;
        }
        if (node == null || !node.isObject()) {
            return false;
        }
        for (String key : requiredKeys) {
            if (!node.has(key)) {
                return false;
            }
        }
        return true;
    }
}
