package com.remasteragent.core.engine.node;

import com.remasteragent.core.codec.JsonCodec;
import com.remasteragent.common.agent.AnalyzeResult;
import com.remasteragent.common.agent.RewriteProposal;
import com.remasteragent.common.agent.RewriteResult;
import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.LlmCallRecord;
import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.core.engine.NodeContext;
import com.remasteragent.core.engine.NodeOutcome;
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

    private static final String MODERN_SOURCE = """
            package com.example;

            import java.time.Instant;

            public class Demo {
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
        taskId = store.createTask(projectRoot.toString(), ENTRY, 21);

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
        long lonelyTask = emptyStore.createTask(tmp.toString(), ENTRY, 21);
        emptyStore.insertNode(lonelyTask, RewriteNode.NODE_KEY, NodeType.REWRITE, List.of(), 0);
        CapturingRewriter rewriter = new CapturingRewriter(proposal(ENTRY, MODERN_SOURCE), null);

        MigrationTask task = emptyStore.findTask(lonelyTask).orElseThrow();
        DagNode node = emptyStore.findNode(lonelyTask, RewriteNode.NODE_KEY, 0).orElseThrow();
        RewriteNode executor = new RewriteNode(emptyStore, rewriter, llmProperties(), new JsonCodec());
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

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------

    private NodeOutcome execute(CapturingRewriter rewriter, int attempt, String retryFeedback) {
        store.insertNode(taskId, RewriteNode.NODE_KEY, NodeType.REWRITE, List.of(), attempt);
        RewriteNode node = new RewriteNode(store, rewriter, llmProperties(), new JsonCodec());
        MigrationTask task = store.findTask(taskId).orElseThrow();
        DagNode dagNode = store.findNode(taskId, RewriteNode.NODE_KEY, attempt).orElseThrow();
        return node.execute(new NodeContext(task, dagNode, workspace, retryFeedback));
    }

    private static LlmProperties llmProperties() {
        return new LlmProperties("http://stub/v1", "stub-key", "stub-model", null,
                60, 0, 1.0d, 2.0d, false);
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
