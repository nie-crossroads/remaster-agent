package com.remasteragent.eval.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * API 响应的解析与形状守卫。
 *
 * <p>这组测试守的是一个具体的事故场景：<b>API 改了字段名，harness 一声不吭，
 * 报告里所有指标变成 0</b>。跑的人只会以为「Agent 这次表现真差」，
 * 而真正的问题在契约上。宽松反序列化是必要的（API 会合理地加字段），
 * 但它把「改名」和「没值」变得无法区分 —— 所以必须有这一层显式校验。
 *
 * <p>测试用例里那条 {@code metrics 字段被改名} 是本类存在的理由，其余是陪衬。
 */
class TaskResponseParserTest {

    /** 与 {@code TaskView} 同形状的响应（metrics 部分带 MetricsView 的全部字段）。 */
    private static String successResponse() {
        return """
                {
                  "id": 17,
                  "projectRoot": "E:/x/examples/eval/billing-legacy",
                  "entryFile": "src/main/java/com/example/billing/InvoiceCalculator.java",
                  "targetJdk": 21,
                  "status": "SUCCEEDED",
                  "failReason": null,
                  "cancelRequested": false,
                  "createdAt": "2026-09-16T08:00:00Z",
                  "updatedAt": "2026-09-16T08:01:30Z",
                  "metrics": {
                    "filesTotal": 4,
                    "compilePassed": 4,
                    "compilePassRate": 1.0,
                    "testsTotal": 51,
                    "testsPassed": 51,
                    "testPassRate": 1.0,
                    "coverage": 0.86,
                    "llmCalls": 6,
                    "promptTokens": 12000,
                    "completionTokens": 3000,
                    "totalCost": 0.0417,
                    "verifyAttempts": 1,
                    "retried": false,
                    "durationMs": 91234
                  },
                  "runDurationMs": 88000
                }
                """;
    }

    @Test
    @DisplayName("正常响应：取出 id / 状态 / 指标 / 运行时长")
    void parsesHappyPath() {
        TaskSummary summary = TaskResponseParser.parse(successResponse());

        assertEquals(17L, summary.id());
        assertEquals("SUCCEEDED", summary.status());
        assertTrue(summary.isTerminal());
        assertTrue(summary.succeeded());
        assertEquals(88_000L, summary.runDurationMs());
        assertEquals(4, summary.metrics().filesTotal());
        assertEquals(4, summary.metrics().compilePassed());
        assertEquals(51, summary.metrics().testsTotal());
        assertEquals(51, summary.metrics().testsPassed());
        assertEquals(1.0d, summary.metrics().compilePassRate(), 1e-9, "派生比率应当能算出来");
        assertEquals(1, summary.metrics().verifyAttempts());
        assertEquals(91_234L, summary.metrics().durationMs());
    }

    @Test
    @DisplayName("未结束的任务：status 非终态且 metrics 为 null，是合法状态")
    void runningTaskHasNoMetricsYet() {
        String json = """
                {"id": 18, "status": "RUNNING", "failReason": null, "cancelRequested": false,
                 "metrics": null, "runDurationMs": null}
                """;

        TaskSummary summary = TaskResponseParser.parse(json);

        assertEquals("RUNNING", summary.status());
        assertTrue(!summary.isTerminal());
        assertNull(summary.metrics());
        assertNull(summary.runDurationMs(), "没有 trace 数据时是 null，不能当 0");
    }

    @Test
    @DisplayName("缺 status 直接抛 —— 判定结局的依据不能靠猜")
    void missingStatusIsRejected() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> TaskResponseParser.parse("{\"id\": 19, \"metrics\": null}"));

        assertTrue(error.getMessage().contains("status"), error.getMessage());
    }

    @Test
    @DisplayName("metrics 字段被改名：点名报缺少哪个字段，而不是静默变成 0")
    void renamedMetricFieldIsCaught() {
        String json = successResponse().replace("\"testsTotal\": 51", "\"testCount\": 51");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> TaskResponseParser.parse(json));

        assertTrue(error.getMessage().contains("testsTotal"), "报错要点名缺的字段: " + error.getMessage());
        assertTrue(error.getMessage().contains("testCount"), "并给出实际存在的字段: " + error.getMessage());
    }

    @Test
    @DisplayName("非 JSON 响应（比如网关返回的 HTML 错误页）要抛，不能当成空对象")
    void nonJsonResponseIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> TaskResponseParser.parse("<html><body>502 Bad Gateway</body></html>"));
    }

    @Test
    @DisplayName("详情接口把 TaskView 嵌在 task 里 —— 同一个解析器要认得两种形状")
    void detailResponseIsUnwrapped() {
        // 这是 GET /api/tasks/{id} 的真实形状：{task, nodes, patches, cost, plan, gate}。
        // 扁平那份则在 GET /api/tasks 里。认形状而不是认端点，调用点就不用记「这次该用哪个」。
        String json = """
                {
                  "task": {
                    "id": 16,
                    "projectRoot": "E:/x/examples/eval/billing-legacy",
                    "entryFile": "src/main/java/com/example/billing/InvoiceCalculator.java",
                    "targetJdk": 21,
                    "status": "RUNNING",
                    "failReason": null,
                    "cancelRequested": false,
                    "metrics": null,
                    "runDurationMs": 23609
                  },
                  "nodes": [{"id": 69, "nodeKey": "analyze", "nodeType": "ANALYZE", "status": "SUCCEEDED"}],
                  "patches": [],
                  "cost": {"calls": 0, "promptTokens": 0, "completionTokens": 0, "totalCost": 0.0},
                  "plan": null,
                  "gate": null
                }
                """;

        TaskSummary summary = TaskResponseParser.parse(json);

        assertEquals(16L, summary.id(), "id 在 task 里，不是顶层");
        assertEquals("RUNNING", summary.status());
        assertEquals(23_609L, summary.runDurationMs());
        assertNull(summary.metrics());
    }

    @Test
    @DisplayName("详情接口里 metrics 字段被改名：照样点名报出来（不能因为多包一层就漏检）")
    void renamedMetricFieldIsCaughtInDetailShape() {
        String json = """
                {
                  "task": {
                    "id": 21, "status": "SUCCEEDED",
                    "metrics": {"filesTotal": 4, "compilePassed": 4, "testCount": 51, "testsPassed": 51,
                                "coverage": 0.8, "llmCalls": 3, "promptTokens": 1, "completionTokens": 1,
                                "totalCost": 0.1, "verifyAttempts": 1, "durationMs": 1000},
                    "runDurationMs": 1000
                  },
                  "nodes": []
                }
                """;

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> TaskResponseParser.parse(json));

        assertTrue(error.getMessage().contains("testsTotal"), error.getMessage());
    }

    @Test
    @DisplayName("CANCELLED 任务：metrics 为 null 是业务状态，不是契约破裂")
    void cancelledTaskIsNotAContractViolation() {
        String json = """
                {"id": 20, "status": "CANCELLED", "failReason": "用户取消：测试用",
                 "cancelRequested": true, "metrics": null, "runDurationMs": 12345}
                """;

        TaskSummary summary = TaskResponseParser.parse(json);

        assertEquals("CANCELLED", summary.status());
        assertTrue(summary.isTerminal());
        assertNull(summary.metrics(), "CANCELLED 有意不写 metrics，是设计如此");
        assertEquals(12_345L, summary.runDurationMs(),
                "但实际运行时长仍然拿得到 —— 这正是耗时口径拆成两个数的意义");
    }
}
