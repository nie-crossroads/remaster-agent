package com.remasteragent.llm.rewrite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型回复解析器的单测。
 *
 * <p>为什么值得单独测：这一段是 LLM 与系统之间唯一的入口，它误判的代价是双向的 ——
 * 判严了（把合法回复当垃圾）会造成大量无谓重试、白烧 token；判松了（把垃圾当合法）
 * 会往下游灌脏数据，最后在沙箱里以难以归因的方式失败。而它<strong>完全不依赖模型</strong>，
 * 所以可以用固定输入把每条边界钉死。
 *
 * <p>所有用例都取自「模型真实会返回的形状」，不是凭空构造的。
 */
class JsonExtractorTest {

    private static final String PAYLOAD =
            "{\"filePath\":\"A.java\",\"newContent\":\"class A {}\",\"rationale\":\"r\"}";

    @Test
    @DisplayName("回复就是纯 JSON：原样取出")
    void plainJson() {
        assertEquals(PAYLOAD, JsonExtractor.extractObject(PAYLOAD, "newContent"));
    }

    @Test
    @DisplayName("带 ```json 围栏：围栏被剥掉")
    void fencedJson() {
        String raw = "```json\n" + PAYLOAD + "\n```";
        assertEquals(PAYLOAD, JsonExtractor.extractObject(raw, "newContent"));
    }

    @Test
    @DisplayName("无语言标记的 ``` 围栏同样处理")
    void plainFence() {
        String raw = "```\n" + PAYLOAD + "\n```";
        assertEquals(PAYLOAD, JsonExtractor.extractObject(raw, "newContent"));
    }

    @Test
    @DisplayName("前后带中文解释：只取中间的 JSON")
    void proseAroundJson() {
        String raw = "好的，这是改写后的文件：\n" + PAYLOAD + "\n希望对你有帮助。";
        assertEquals(PAYLOAD, JsonExtractor.extractObject(raw, "newContent"));
    }

    @Test
    @DisplayName("字符串里的花括号不能把对象截断（最容易写错的一处）")
    void bracesInsideStringDoNotTruncate() {
        String raw = "{\"newContent\":\"class A { void m() { if (x) { y(); } } }\",\"rationale\":\"r\"}";

        assertEquals(raw, JsonExtractor.extractObject(raw, "newContent"));
    }

    @Test
    @DisplayName("转义引号不能把字符串状态判错")
    void escapedQuotesInsideString() {
        // 实际内容是：{"newContent":"String s = \"a{b}c\";","rationale":"r"}
        String raw = "{\"newContent\":\"String s = \\\"a{b}c\\\";\",\"rationale\":\"r\"}";

        assertEquals(raw, JsonExtractor.extractObject(raw, "newContent"));
    }

    @Test
    @DisplayName("嵌套对象要被完整取出，不能停在内层 }")
    void nestedObjects() {
        String raw = "{\"newContent\":\"x\",\"meta\":{\"inner\":{\"deep\":1}},\"rationale\":\"r\"}";

        assertEquals(raw, JsonExtractor.extractObject(raw, "newContent"));
    }

    @Test
    @DisplayName("被 max_tokens 截断（括号不配平）：返回 null 让上层判失败")
    void truncatedReturnsNull() {
        assertNull(JsonExtractor.extractObject("{\"newContent\":\"class A {\",\"rationale\":", "newContent"));
    }

    @Test
    @DisplayName("回复里根本没有 JSON：返回 null")
    void noJsonAtAll() {
        assertNull(JsonExtractor.extractObject("我无法完成这个任务。", "newContent"));
    }

    @ParameterizedTest
    @DisplayName("null / 空串 / 纯空白：返回 null")
    @ValueSource(strings = {"", "   ", "\n\t "})
    void blankInputs(String input) {
        assertNull(JsonExtractor.extractObject(input, "newContent"));
        assertNull(JsonExtractor.extractObject(null, "newContent"));
    }

    // ------------------------------------------------------------------
    // 正文干扰项：这几条是「只按配平+可解析判断」会踩的坑
    // ------------------------------------------------------------------

    @Test
    @DisplayName("正文先贴了一段旧代码示例（class A { }）—— 不能把 {} 当成产出")
    void oldCodeSnippetIsNotMistakenForPayload() {
        String raw = "旧代码是这样的：\n```java\nclass A { }\n```\n改写后：\n" + PAYLOAD;

        String extracted = JsonExtractor.extractObject(raw, "newContent");

        assertEquals(PAYLOAD, extracted, "{} 是合法 JSON，但缺 newContent，必须继续往后找");
    }

    @Test
    @DisplayName("正文里出现配平但不是 JSON 的花括号（如 {x}）—— 继续往后找")
    void balancedButNonJsonBracesAreSkipped() {
        String raw = "注意 {x} 这种写法不是 JSON。真正的产出：\n" + PAYLOAD;

        assertEquals(PAYLOAD, JsonExtractor.extractObject(raw, "newContent"));
    }

    @Test
    @DisplayName("解释文字里含字符串包裹的 {} —— 同样被跳过")
    void bracesInsideProseStringAreSkipped() {
        String raw = "{\"note\":\"{} 表示空对象\"} 然后是产出：" + PAYLOAD;

        String extracted = JsonExtractor.extractObject(raw, "newContent");

        assertTrue(extracted != null && extracted.contains("newContent"), "应取到真正的产出，实际: " + extracted);
    }

    @Test
    @DisplayName("不传 requiredKeys 时退化为「第一个可解析对象」")
    void withoutRequiredKeysFallsBackToFirstParseableObject() {
        assertEquals("{}", JsonExtractor.extractObject("这里有个 {} 空对象"));
    }

    @Test
    @DisplayName("候选过多时放弃，而不是把 CPU 打满")
    void candidateFloodIsBounded() {
        // 64 个干扰项已到上限，其后的真实产出不再被尝试 —— 这种回复本来也没救了，
        // 及时返回 null 让上层判失败，好过在解析上耗时
        String flood = "{}".repeat(80) + PAYLOAD;

        assertNull(JsonExtractor.extractObject(flood, "newContent"));
    }

    @Test
    @DisplayName("少一个必需字段就不算合格产出")
    void missingRequiredKeyIsRejected() {
        String raw = "{\"filePath\":\"A.java\",\"rationale\":\"忘了给 newContent\"}";

        assertNull(JsonExtractor.extractObject(raw, "newContent"));
    }
}
