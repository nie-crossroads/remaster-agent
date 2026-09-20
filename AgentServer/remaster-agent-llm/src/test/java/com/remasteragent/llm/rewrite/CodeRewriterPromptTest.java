package com.remasteragent.llm.rewrite;

import com.remasteragent.common.rag.CodeChunk;
import com.remasteragent.common.rag.RetrievedChunk;
import com.remasteragent.llm.context.ContextBudget;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CodeRewriter} 的 prompt 组装单测 —— 纯字符串断言，不连模型、不连数据库。
 *
 * <p>为什么专门测这个：<b>检索到的代码有没有真的进到发给模型的文本里，是整个 RAG 链路里
 * 最容易「看起来做了其实没做」的一环。</b>向量库写了、检索器返回了、节点也调了 ——
 * 只要最后这一步没把块拼进 prompt，前面所有工作都白费，而现象只是「改写质量没变好」，
 * 不会有任何报错。日志里也看不出来（我们只记 token 数）。
 *
 * <p>所以这里断言的是「拼出来的文本长什么样」：包含来源标注、包含代码内容、目标文件
 * 自己的块不该出现（那由节点负责剔除，这里只保证拼装本身不丢不重）。
 */
class CodeRewriterPromptTest {

    private static final String SOURCE = """
            package com.example;
            public class Demo {
                public String stamp() { return new Date().toString(); }
            }
            """;

    private static final String RELATED_SOURCE = "Demo d = new Demo();\nreturn d.stamp();";

    @Test
    void 检索到的代码块带来源标注进入用户消息() {
        RewriteCommand command = new RewriteCommand(
                "src/main/java/com/example/Demo.java", "com.example", "Demo",
                SOURCE, 21, 0, null,
                List.of(new RetrievedChunk(
                        new CodeChunk("src/main/java/com/example/App.java",
                                "com.example.App#main", CodeChunk.KIND_METHOD, 5, 6, RELATED_SOURCE),
                        1, List.of(RetrievedChunk.SOURCE_SYMBOL, RetrievedChunk.SOURCE_KEYWORD))));

        String prompt = CodeRewriter.buildUserPrompt(command);

        assertTrue(prompt.contains("Related code"), "应出现相关代码小节: " + prompt);
        assertTrue(prompt.contains("com.example.App#main"), "应带上符号名，模型才知道这是谁");
        assertTrue(prompt.contains("src/main/java/com/example/App.java"), "应带上文件路径");
        assertTrue(prompt.contains("hits: symbol+keyword"), "应标注命中来源，便于判断可信度");
        assertTrue(prompt.contains("Demo d = new Demo();"), "代码正文必须在 prompt 里");
        assertTrue(prompt.contains("DO NOT rewrite"), "必须明确告诉模型这些文件不要改");
        // 要改的源码仍要在场，且位于相关代码之后 —— 主体不能被上下文挤走
        assertTrue(prompt.contains("Source code to modernize"), prompt);
        assertTrue(prompt.indexOf("Related code") < prompt.indexOf("Source code to modernize"));
    }

    @Test
    void 框架破坏性变更提示进入用户消息() {
        // 首轮（attempt=0）也要带上：这类提示来自确定性扫描，不是上一次失败的反馈。
        // 少了这一节，模型看着一个 javax 已全改完的文件只会认为它无需改动。
        RewriteCommand command = new RewriteCommand(
                "src/main/java/com/example/Demo.java", "com.example", "Demo",
                SOURCE, 21, 0, null, List.of(), null,
                List.of("Spring 6 的 HttpComponentsClientHttpRequestFactory 只接受 HttpClient 5"));

        String prompt = CodeRewriter.buildUserPrompt(command);

        assertTrue(prompt.contains("破坏性变更"), "应出现破坏性变更小节: " + prompt);
        assertTrue(prompt.contains("HttpClient 5"), "提示正文必须在 prompt 里: " + prompt);
        assertTrue(prompt.indexOf("破坏性变更") < prompt.indexOf("Source code to modernize"),
                "提示应排在源码之前，否则容易被长源码挤到模型注意力之外");
    }

    @Test
    void 没有破坏性变更提示时不出现该小节() {
        RewriteCommand command = new RewriteCommand(
                "src/main/java/com/example/Demo.java", "com.example", "Demo",
                SOURCE, 21, 0, null, List.of(), null, List.of());

        String prompt = CodeRewriter.buildUserPrompt(command);

        assertFalse(prompt.contains("破坏性变更"), "没命中就不该凭空加一节: " + prompt);
    }

    @Test
    void 没有检索结果时不出现相关代码小节() {
        RewriteCommand command = new RewriteCommand(
                "src/main/java/com/example/Demo.java", "com.example", "Demo",
                SOURCE, 21, 0, null, List.of());

        String prompt = CodeRewriter.buildUserPrompt(command);

        assertFalse(prompt.contains("Related code"),
                "无上下文时不该留一个空小节 —— 空标题只会让模型以为内容丢了");
        assertTrue(prompt.contains(SOURCE));
    }

    @Test
    void 重试时失败反馈与相关代码可以同时存在() {
        RewriteCommand command = new RewriteCommand(
                "src/main/java/com/example/Demo.java", "com.example", "Demo",
                SOURCE, 21, 1, "编译失败: 找不到符号 Date",
                List.of(new RetrievedChunk(
                        new CodeChunk("src/main/java/com/example/Clock.java",
                                "com.example.Clock#now", CodeChunk.KIND_METHOD, 1, 2, "return Instant.now();"),
                        1, List.of(RetrievedChunk.SOURCE_NEIGHBOR))));

        String prompt = CodeRewriter.buildUserPrompt(command);

        assertTrue(prompt.contains("这是第 1 次重试"));
        assertTrue(prompt.contains("编译失败: 找不到符号 Date"));
        assertTrue(prompt.contains("return Instant.now();"));
        assertTrue(prompt.contains("hits: neighbor"), "邻居扩展进来的块也要如实标注来源");
    }

    @Test
    void 预算不足时低优先级块被丢弃并标注省略() {
        // 高优先级（rank=1）块保留，低优先级（rank=2）块被上下文预算裁掉
        RewriteCommand command = new RewriteCommand(
                "src/main/java/com/example/Demo.java", "com.example", "Demo",
                SOURCE, 21, 0, null,
                List.of(
                        new RetrievedChunk(new CodeChunk("A.java", "A#m", CodeChunk.KIND_METHOD, 1, 2, "return a();"),
                                1, List.of(RetrievedChunk.SOURCE_SYMBOL)),
                        new RetrievedChunk(new CodeChunk("B.java", "B#m", CodeChunk.KIND_METHOD, 1, 2, "return b();"),
                                2, List.of(RetrievedChunk.SOURCE_KEYWORD))));

        // 极小预算：首项保底入选，次项丢弃
        String prompt = CodeRewriter.buildUserPrompt(command, ContextBudget.of(2, true));

        assertTrue(prompt.contains("A.java"), "高优先级块应保留: " + prompt);
        assertTrue(prompt.contains("omitted"), "应出现省略标注: " + prompt);
        assertFalse(prompt.contains("B.java"), "低优先级块应被丢弃: " + prompt);
    }

    @Test
    void 关闭治理时相关代码全量透传() {
        RewriteCommand command = new RewriteCommand(
                "src/main/java/com/example/Demo.java", "com.example", "Demo",
                SOURCE, 21, 0, null,
                List.of(
                        new RetrievedChunk(new CodeChunk("A.java", "A#m", CodeChunk.KIND_METHOD, 1, 2, "return a();"),
                                1, List.of(RetrievedChunk.SOURCE_SYMBOL)),
                        new RetrievedChunk(new CodeChunk("B.java", "B#m", CodeChunk.KIND_METHOD, 1, 2, "return b();"),
                                2, List.of(RetrievedChunk.SOURCE_KEYWORD))));

        String prompt = CodeRewriter.buildUserPrompt(command, ContextBudget.disabled());

        assertTrue(prompt.contains("A.java"));
        assertTrue(prompt.contains("B.java"));
        assertFalse(prompt.contains("omitted"), "关闭治理不应有省略标注");
    }
}
