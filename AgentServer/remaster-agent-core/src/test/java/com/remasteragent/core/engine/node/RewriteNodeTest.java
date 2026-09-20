package com.remasteragent.core.engine.node;

import com.remasteragent.core.codec.JsonCodec;
import com.remasteragent.common.agent.AnalyzeResult;
import com.remasteragent.common.agent.RewriteProposal;
import com.remasteragent.common.agent.RewriteResult;
import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.LlmCallRecord;
import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.rag.CodeChunk;
import com.remasteragent.common.rag.RetrievedChunk;
import com.remasteragent.core.engine.NodeContext;
import com.remasteragent.core.engine.NodeOutcome;
import com.remasteragent.core.rag.ContextRetriever;
import com.remasteragent.core.store.InMemoryTaskStore;
import com.remasteragent.llm.config.LlmProperties;
import com.remasteragent.llm.rewrite.CodeRewriter;
import com.remasteragent.llm.rewrite.RewriteCommand;
import com.remasteragent.llm.rewrite.RewriteOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REWRITE 节点的单测 —— 用桩件替掉大模型，把「写盘 + 记账」这段钉死。
 *
 * <p>这一层最值得测的原因是它同时干了三件后果很重的事：把模型产出落到沙箱、
 * 落 {@code patch} 审计、落 {@code llm_call} 成本。这三件事都不依赖模型本身的智能，
 * 因此完全可以用固定输入验证；而一旦写错（比如把路径判断交给模型、或成本算错），
 * 造成的损失是数据被改到不该改的地方、或成本报表失真。
 *
 * <p>特别验证一条安全边界：<b>模型声称的文件路径一律不采信</b>。
 * 写入位置始终来自 ANALYZE 阶段服务端自己算出来的路径，
 * 所以模型即使返回 {@code ../../evil.java} 也落不到沙箱外。
 */
class RewriteNodeTest {

    private static final String ENTRY = "src/main/java/com/example/Demo.java";

    private static final String LEGACY_SOURCE = """
            package com.example;

            import java.util.Date;

            public class Demo {
                public String stamp() {
                    return new Date().toString();
                }
            }
            """;

    /**
     * 「没有 javax、但用了 Spring 6 已换底层实现的 API」的源码 —— 博客工程
     * {@code RestTemplateConfig.java} 的同类：一个 javax 都没有，因此命名空间那把尺子完全看不见它。
     */
    private static final String USES_SPRING5_API_SOURCE = """
            package com.example;

            import org.apache.http.impl.client.CloseableHttpClient;
            import org.apache.http.impl.client.HttpClients;
            import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
            import org.springframework.web.client.RestTemplate;

            public class Demo {
                public String stamp() {
                    return new java.util.Date().toString();
                }

                public RestTemplate restTemplate() {
                    CloseableHttpClient client = HttpClients.createDefault();
                    return new RestTemplate(new HttpComponentsClientHttpRequestFactory(client));
                }
            }
            """;

    private static final String MODERN_SOURCE = """
            package com.example;

            import java.time.Instant;

            public class Demo {
                public String stamp() {
                    return Instant.now().toString();
                }
            }
            """;

    /** 模型把业务逻辑改了，却漏了 import：仍残留 javax.annotation.Resource（未迁到 jakarta）。 */
    private static final String LEAVES_JAVAX_SOURCE = """
            package com.example;

            import javax.annotation.Resource;
            import java.time.Instant;

            public class Demo {
                @Resource
                private Object dependency;

                public String stamp() {
                    return Instant.now().toString();
                }
            }
            """;

    @TempDir
    Path tmp;

    private InMemoryTaskStore store;
    private Path workspace;
    private long taskId;

    @BeforeEach
    void setUp() throws IOException {
        store = new InMemoryTaskStore();
        workspace = tmp.resolve("ws").resolve("task-1");

        // 「沙箱工作目录」：文件已由 WorkspacePreparer 复制进来，且必须是原始版本
        Path entry = workspace.resolve(ENTRY);
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, LEGACY_SOURCE, StandardCharsets.UTF_8);

        Path projectRoot = tmp.resolve("legacy-demo");
        Files.createDirectories(projectRoot);
        taskId = store.createTask(projectRoot.toString(), ENTRY, 21, null);

        // 预置 ANALYZE 的 checkpoint —— REWRITE 从它读上下文
        JsonCodec mapper = new JsonCodec();
        long analyzeId = store.insertNode(taskId, AnalyzeNode.NODE_KEY, NodeType.ANALYZE, List.of(), 0);
        store.markNodeSucceeded(analyzeId, mapper.write(new AnalyzeResult(
                ENTRY, "com.example", "Demo", List.of("com.example.Demo", "com.example.Demo#stamp()"),
                LEGACY_SOURCE, "单类单方法")));
    }

    @Test
    @DisplayName("正常改写：产出落到沙箱、patch 与 llm_call 各落一笔、成本算对")
    void happyPathWritesFileAndRecordsAccounting() {
        CapturingRewriter rewriter = new CapturingRewriter(proposal(ENTRY, MODERN_SOURCE), null);

        NodeOutcome outcome = execute(rewriter, 0, null);

        assertTrue(outcome.success(), "合规产出应成功，实际: " + outcome.error());

        // 1) 文件真的被写进沙箱
        assertEquals(MODERN_SOURCE, read(workspace.resolve(ENTRY)));

        // 2) patch 审计落库
        assertEquals(1, store.findPatches(taskId).size());
        assertEquals(ENTRY, store.findPatches(taskId).get(0).filePath());

        // 3) 成本埋点：1000 prompt + 2000 completion，单价 1.0 / 2.0 每百万
        List<LlmCallRecord> calls = store.findLlmCalls(taskId);
        assertEquals(1, calls.size());
        LlmCallRecord call = calls.get(0);
        assertEquals("stub-model", call.model());
        assertEquals(NodeType.REWRITE.name(), call.purpose());
        assertEquals(1000, call.promptTokens());
        assertEquals(2000, call.completionTokens());
        assertEquals(0.005d, call.cost(), 1e-9);

        // 4) 节点产出里带着重放所需的改写内容（落库成 checkpoint 由调度器负责，这里只验证产出本身）
        assertInstanceOf(RewriteResult.class, outcome.result());
        RewriteResult result = (RewriteResult) outcome.result();
        assertEquals(ENTRY, result.filePath(), "产出路径必须是服务端认定的目标文件");
        assertTrue(result.newContent().contains("Instant.now()"));
        assertEquals(0, result.attempt());
    }

    @Test
    @DisplayName("模型声称的路径不被采信：写入位置始终由服务端决定（路径穿越被堵死）")
    void modelSuppliedPathIsIgnored() {
        // 模型返回了一个逃出沙箱的路径
        CapturingRewriter rewriter = new CapturingRewriter(
                proposal("../../evil.java", MODERN_SOURCE), null);

        NodeOutcome outcome = execute(rewriter, 0, null);

        assertTrue(outcome.success());
        // 落盘位置仍是服务端从 ANALYZE 拿到的路径
        assertEquals(MODERN_SOURCE, read(workspace.resolve(ENTRY)));
        assertFalse(Files.exists(tmp.resolve("evil.java")), "模型无权把文件写到沙箱之外");
        assertFalse(Files.exists(workspace.resolve("evil.java")));
    }

    @Test
    @DisplayName("护栏拦下（包名被改）：失败，但仍记一次成本 —— 钱确实花了")
    void guardrailViolationFailsButStillRecordsCost() {
        String wrongPackage = """
                package com.wrong;

                public class Demo {
                }
                """;
        CapturingRewriter rewriter = new CapturingRewriter(proposal(ENTRY, wrongPackage), null);

        NodeOutcome outcome = execute(rewriter, 0, null);

        assertFalse(outcome.success());
        assertTrue(outcome.error().contains("包名"), "失败原因应指明是包名被改: " + outcome.error());

        // 沙箱文件保持原样，没有被写脏
        assertEquals(LEGACY_SOURCE, read(workspace.resolve(ENTRY)));
        assertEquals(0, store.findPatches(taskId).size());

        // 关键：调用已经发生、token 已经消耗，成本必须如实入账，否则报表会漏账
        assertEquals(1, store.findLlmCalls(taskId).size(),
                "被护栏拦下的产出同样是花了钱的，成本不能漏记");
    }

    @Test
    @DisplayName("模型原样返回源文件（+0/-0）：判失败，而不是假装成功 —— 否则白烧一轮且失败原因指向不了真因")
    void identicalContentFailsInsteadOfPretendingSuccess() throws IOException {
        // 源文件命中了 Spring Boot 2→3 的破坏性 API（HttpComponentsClientHttpRequestFactory），
        // 属于「确实有活要干」的文件 —— 此时原样返回就是没交作业，必须判失败。
        Files.writeString(workspace.resolve(ENTRY), USES_SPRING5_API_SOURCE, StandardCharsets.UTF_8);
        CapturingRewriter rewriter = new CapturingRewriter(proposal(ENTRY, USES_SPRING5_API_SOURCE), null);

        NodeOutcome outcome = execute(rewriter, 0, null);

        assertFalse(outcome.success(),
                "内容与原文件完全相同时必须判失败：交给 VERIFY 兜底只会拿到「编译失败」这种不指向真因的原因");
        assertTrue(outcome.error().contains("+0/-0"),
                "失败原因要说清是「没有产生差异」: " + outcome.error());

        // 不写盘、不留空补丁 —— 否则审计里会多出一条没有内容的记录，前端还会给它一个「第 N 轮」标签
        assertEquals(USES_SPRING5_API_SOURCE, read(workspace.resolve(ENTRY)));
        assertEquals(0, store.findPatches(taskId).size(), "空补丁不该入库");

        // 与护栏同理：调用确实发生了，成本不能因为「没改出东西」就抹掉
        assertEquals(1, store.findLlmCalls(taskId).size(),
                "钱花在了一次没有产出的调用上，报表必须如实反映");
    }

    @Test
    @DisplayName("源文件已无待迁移项、模型原样返回：判「无需改动」成功 —— 而不是把已干净的文件再拉回来空跑一轮")
    void identicalContentOnAlreadyCleanFileIsNoop() {
        // LEGACY_SOURCE 既无残留旧 import、也没命中任何框架破坏性 API，
        // 模型判定「无需改动」就是正确结论 —— #57 里就因为一律判失败而空跑了 20 次。
        CapturingRewriter rewriter = new CapturingRewriter(proposal(ENTRY, LEGACY_SOURCE), null);

        NodeOutcome outcome = execute(rewriter, 0, null);

        assertTrue(outcome.success(),
                "源文件已无待迁移项时，原样返回应判无需改动成功: " + outcome.error());
        assertEquals(LEGACY_SOURCE, read(workspace.resolve(ENTRY)), "没有改动就不该写盘");
        assertEquals(0, store.findPatches(taskId).size(), "没有改动就不该落 patch");
        assertEquals(1, store.findLlmCalls(taskId).size(),
                "这次调用确实发生了，成本不能因为「判定无需改动」就抹掉");
    }

    @Test
    @DisplayName("命中框架破坏性 API 的文件：把「该怎么改」的确定性提示递给模型，首轮就带上")
    void spring3BreakingApiHintsArePassedToModel() throws IOException {
        Files.writeString(workspace.resolve(ENTRY), USES_SPRING5_API_SOURCE, StandardCharsets.UTF_8);
        CapturingRewriter rewriter = new CapturingRewriter(proposal(ENTRY, MODERN_SOURCE), null);

        execute(rewriter, 0, null);

        assertNotNull(rewriter.lastCommand, "改写请求应被桩件捕获");
        assertTrue(rewriter.lastCommand.migrationHints().stream()
                        .anyMatch(hint -> hint.contains("HttpClient 5")),
                "命中 HttpComponentsClientHttpRequestFactory 就必须告诉模型要换 HttpClient 5，"
                        + "否则模型看着一个 javax 已全改完的文件，只会认为它无需改动: "
                        + rewriter.lastCommand.migrationHints());
    }

    @Test
    @DisplayName("改写后仍残留旧 import（漏改 javax → jakarta）：判失败并带精确反馈，而非假装成功")
    void leftoverLegacyImportFailsWithFeedback() {
        // 模型改了业务逻辑，却漏了 javax.annotation.Resource —— 复现博客工程曾被漏改的真实失误
        CapturingRewriter rewriter = new CapturingRewriter(proposal(ENTRY, LEAVES_JAVAX_SOURCE), null);

        NodeOutcome outcome = execute(rewriter, 0, null);

        assertFalse(outcome.success(),
                "漏改的 import 必须判失败：交给整仓 VERIFY 兜底只会拿到不指向真因的编译错误");
        assertTrue(outcome.error().contains("javax.annotation.Resource"),
                "失败原因应精确指出漏改了哪些 import: " + outcome.error());
        assertTrue(outcome.error().contains("jakarta.annotation.Resource"),
                "应给出 jakarta 目标命名空间提示: " + outcome.error());

        // 不写盘、不留空补丁 —— 否则审计里会多出一条没有内容的记录，前端还会给它一个「第 N 轮」标签
        assertEquals(LEGACY_SOURCE, read(workspace.resolve(ENTRY)));
        assertEquals(0, store.findPatches(taskId).size(), "残留旧 import 的改写不该入库");

        // 关键：调用确实发生了、token 已经消耗，成本必须如实入账，否则报表会漏账
        assertEquals(1, store.findLlmCalls(taskId).size(),
                "被判定漏改的产出同样是花了钱的，成本不能漏记");
    }

    @Test
    @DisplayName("模型回复无法解析：判为可重试失败，不记成本（拿不到用量）")
    void unparseableResponseIsRetryableFailure() {
        CapturingRewriter rewriter = new CapturingRewriter(null,
                new CodeRewriter.RewriteFailedException("模型回复里找不到完整的 JSON 对象（可能被截断）"));

        NodeOutcome outcome = execute(rewriter, 0, null);

        assertFalse(outcome.success());
        assertTrue(outcome.error().contains("模型产出不可用"), "应被归类为可重试失败: " + outcome.error());
        assertTrue(store.findLlmCalls(taskId).isEmpty(), "解析阶段就失败，没有用量可记");
        assertEquals(0, store.findPatches(taskId).size());
    }

    @Test
    @DisplayName("找不到 ANALYZE 产出：直接失败，不调用模型")
    void missingAnalysisFailsFast() {
        InMemoryTaskStore emptyStore = new InMemoryTaskStore();
        long lonelyTask = emptyStore.createTask(tmp.toString(), ENTRY, 21, null);
        emptyStore.insertNode(lonelyTask, RewriteNode.NODE_KEY, NodeType.REWRITE, List.of(), 0);
        CapturingRewriter rewriter = new CapturingRewriter(proposal(ENTRY, MODERN_SOURCE), null);

        MigrationTask task = emptyStore.findTask(lonelyTask).orElseThrow();
        DagNode node = emptyStore.findNode(lonelyTask, RewriteNode.NODE_KEY, 0).orElseThrow();
        RewriteNode executor = new RewriteNode(emptyStore, rewriter, llmProperties(), new JsonCodec(),
                ContextRetriever.NONE);
        NodeOutcome outcome = executor.execute(new NodeContext(task, node, workspace, null));

        assertFalse(outcome.success());
        assertTrue(outcome.error().contains("ANALYZE"), outcome.error());
        assertNull(rewriter.lastCommand, "上游缺失时不该浪费一次模型调用");
    }

    @Test
    @DisplayName("重试时把失败反馈与 attempt 一起传给模型 —— 回退能起作用的关键")
    void retryFeedbackIsForwarded() {
        CapturingRewriter rewriter = new CapturingRewriter(proposal(ENTRY, MODERN_SOURCE), null);

        execute(rewriter, 1, "编译失败: Demo.java:[7,17] 找不到符号 方法 stamp()");

        RewriteCommand command = rewriter.lastCommand;
        assertNotNull(command);
        assertEquals(1, command.attempt());
        assertTrue(command.isRetry());
        assertEquals("编译失败: Demo.java:[7,17] 找不到符号 方法 stamp()", command.failureFeedback());
        assertEquals(21, command.targetJdk());
        assertEquals("com.example", command.packageName());
        assertEquals("Demo", command.className());
    }

    @Test
    @DisplayName("检索上下文随命令下发；目标文件自己的块被剔除（否则同一段代码进 prompt 两遍）")
    void retrievalContextIsForwardedAndSelfIsExcluded() {
        CodeChunk self = new CodeChunk(ENTRY, "com.example.Demo", CodeChunk.KIND_CLASS,
                1, 8, MODERN_SOURCE);
        CodeChunk caller = new CodeChunk("src/main/java/com/example/App.java",
                "com.example.App#main", CodeChunk.KIND_METHOD, 5, 9, "Demo d = new Demo();");
        CodeChunk clock = new CodeChunk("src/main/java/com/example/Clock.java",
                "com.example.Clock#now", CodeChunk.KIND_METHOD, 3, 5, "return Instant.now();");

        List<String> queries = new ArrayList<>();
        ContextRetriever stub = (root, query) -> {
            queries.add(query);
            return List.of(
                    new RetrievedChunk(self, 1, List.of(RetrievedChunk.SOURCE_SYMBOL)),
                    new RetrievedChunk(caller, 2, List.of(RetrievedChunk.SOURCE_SYMBOL)),
                    new RetrievedChunk(clock, 3, List.of(RetrievedChunk.SOURCE_KEYWORD)));
        };

        CapturingRewriter rewriter = new CapturingRewriter(proposal(ENTRY, MODERN_SOURCE), null);
        NodeOutcome outcome = execute(rewriter, 0, null, stub);

        assertTrue(outcome.success(), outcome.error());

        RewriteCommand command = rewriter.lastCommand;
        assertNotNull(command);
        assertEquals(2, command.contextChunks().size(), "目标文件自身的块必须被剔除");
        assertTrue(command.contextChunks().stream()
                        .noneMatch(hit -> ENTRY.equals(hit.chunk().filePath())),
                "自己不该作为「相关代码」再出现一次");
        assertEquals("src/main/java/com/example/App.java",
                command.contextChunks().get(0).chunk().filePath(), "融合排名前的块应先保留");

        // 查询串用全限定名而不是文件路径：路径片段（src/main/java）只会带来无差别命中
        assertEquals(List.of("com.example.Demo"), queries);
    }

    @Test
    @DisplayName("检索抛异常时降级为空上下文且改写照常成功 —— 检索是增强，不是单点")
    void retrievalFailureDegradesGracefully() {
        ContextRetriever broken = (root, query) -> {
            throw new IllegalStateException("数据库连不上");
        };

        CapturingRewriter rewriter = new CapturingRewriter(proposal(ENTRY, MODERN_SOURCE), null);
        NodeOutcome outcome = execute(rewriter, 0, null, broken);

        assertTrue(outcome.success(), "检索挂掉不该让改写失败: " + outcome.error());
        assertTrue(rewriter.lastCommand.contextChunks().isEmpty());
        assertEquals(MODERN_SOURCE, read(workspace.resolve(ENTRY)));
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    private NodeOutcome execute(CapturingRewriter rewriter, int attempt, String retryFeedback) {
        return execute(rewriter, attempt, retryFeedback, ContextRetriever.NONE);
    }

    private NodeOutcome execute(CapturingRewriter rewriter, int attempt, String retryFeedback,
                                ContextRetriever retriever) {
        store.insertNode(taskId, RewriteNode.NODE_KEY, NodeType.REWRITE, List.of(), attempt);
        RewriteNode node = new RewriteNode(store, rewriter, llmProperties(), new JsonCodec(), retriever);
        MigrationTask task = store.findTask(taskId).orElseThrow();
        DagNode dagNode = store.findNode(taskId, RewriteNode.NODE_KEY, attempt).orElseThrow();
        return node.execute(new NodeContext(task, dagNode, workspace, retryFeedback));
    }

    private static LlmProperties llmProperties() {
        // 参数顺序：baseUrl, apiKey, model, rewriteModel, embeddingModel, embeddingDimensions,
        //          embeddingBaseUrl, embeddingApiKey, timeoutSeconds, maxRetries,
        //          retryBackoffMillis, callBudgetSeconds, inPrice, outPrice, logRequests
        // 重试相关两项填 0：单测用桩件替掉了模型，重试语义由 LlmRetryExecutorTest 单独钉。
        return new LlmProperties("http://stub/v1", "stub-key", "stub-model", null,
                null, null, null, null, 60, 0, 0, 0, 1.0d, 2.0d, false);
    }

    private static RewriteProposal proposal(String filePath, String newContent) {
        return new RewriteProposal(filePath, newContent, "改用 java.time，行为不变");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("读取失败: " + path, e);
        }
    }

    /** 替掉真实模型调用的桩件：记录收到的命令，按预设返回或抛错。 */
    private static final class CapturingRewriter extends CodeRewriter {

        private final RewriteProposal proposal;
        private final RuntimeException failure;
        private RewriteCommand lastCommand;

        private CapturingRewriter(RewriteProposal proposal, RuntimeException failure) {
            super(null);
            this.proposal = proposal;
            this.failure = failure;
        }

        @Override
        public RewriteOutcome rewrite(RewriteCommand command) {
            this.lastCommand = command;
            if (failure != null) {
                throw failure;
            }
            return new RewriteOutcome(proposal, "stub-model", 1000, 2000, 120L, "<raw response>");
        }
    }
}
