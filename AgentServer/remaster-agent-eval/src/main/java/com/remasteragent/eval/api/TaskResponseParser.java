package com.remasteragent.eval.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.remasteragent.eval.util.EvalJson;
import com.remasteragent.eval.util.JsonShape;

/**
 * 把 {@code GET /api/tasks/{id}} 的响应文本解析成 {@link TaskSummary}。
 *
 * <p>单独抽出来是为了<b>可测</b>：解析这一段不需要起 HTTP 服务、不需要数据库，
 * 给它一段 JSON 就能断言。而它恰恰是整条链路里最该被测的地方 ——
 * 「指标全 0」这种最误导人的失败就发生在这里（字段改名 + 宽松反序列化 = 静默的 0）。
 *
 * <h2>三道闸</h2>
 * <ol>
 *   <li>根节点必须有 {@code id} 与 {@code status}；</li>
 *   <li>{@code metrics} 若存在，必须含报告要用的全部字段 —— 缺哪个就点名报哪个；</li>
 *   <li>{@code metrics} 为 {@code null} 是<b>合法</b>的（任务未结束，或 CANCELLED 有意不写指标），
 *       但它与「字段缺失」必须区分开：前者是业务状态，后者是契约破裂。</li>
 * </ol>
 */
public final class TaskResponseParser {

    /** metrics 里报告必须用到的字段。少了任何一个，报告里的数就是假的。 */
    static final String[] REQUIRED_METRIC_KEYS = {
            "filesTotal", "compilePassed",
            "testsTotal", "testsPassed",
            "coverage", "llmCalls",
            "promptTokens", "completionTokens",
            "totalCost", "verifyAttempts", "durationMs"
    };

    private TaskResponseParser() {
    }

    public static TaskSummary parse(String json) {
        JsonNode root;
        try {
            root = EvalJson.JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("任务响应不是合法 JSON: " + abbreviate(json), e);
        }
        root = unwrap(root);
        JsonShape.requireKeys(root, "", "id", "status");

        JsonNode metricsNode = root.path("metrics");
        if (!metricsNode.isMissingNode() && !metricsNode.isNull()) {
            JsonShape.requireKeys(root, "metrics", REQUIRED_METRIC_KEYS);
        }

        try {
            return EvalJson.JSON.treeToValue(root, TaskSummary.class);
        } catch (Exception e) {
            throw new IllegalStateException("任务响应解析失败: " + abbreviate(json), e);
        }
    }

    /**
     * 详情接口 {@code GET /api/tasks/{id}} 返回的是 {@code {task, nodes, patches, cost, ...}}，
     * 真正的任务字段在 {@code task} 里 —— 与列表接口 {@code GET /api/tasks} 的扁平形状不同。
     *
     * <p>同一个语义两个形状，正是这个项目栽过的地方（SSE 事件与 REST 快照不同形导致
     * 指标面板归零）。这里的处理方式是<b>认形状而不是认端点</b>：根节点没有 {@code id}
     * 但有 {@code task} 就往下走一层。这样列表接口、详情接口、以及将来任何把
     * {@code TaskView} 嵌一层的端点都能用同一个解析器，不必在调用点记着「这次该用哪个」。
     */
    private static JsonNode unwrap(JsonNode root) {
        if (!root.has("id") && root.path("task").isObject()) {
            return root.get("task");
        }
        return root;
    }

    private static String abbreviate(String text) {
        return text == null ? "null" : (text.length() <= 400 ? text : text.substring(0, 400) + "…");
    }
}
