package com.remasteragent.web.api;

import com.remasteragent.common.agent.PlanResult;
import com.remasteragent.common.agent.VerifyResult;
import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.HumanGate;
import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.NodeStatus;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.domain.PatchRecord;
import com.remasteragent.common.domain.TaskMetrics;
import com.remasteragent.core.codec.JsonCodec;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.web.api.dto.TaskDetailView;
import com.remasteragent.web.api.dto.TaskView;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 领域模型 → 对外视图的转换。
 *
 * <p>单独成类而不是塞在 Controller 里，有两个实际好处：Controller 只表达「路由与 HTTP 语义」，
 * 一眼能看全 API 面；转换逻辑可以被单测直接调用，不需要起 MockMvc。
 *
 * <p>所有解析都<b>吞异常并降级为 null</b>，这是刻意的：
 * 指标 JSON 是历史数据，可能由旧版本代码写入、字段已经变了。
 * 因为「一条老任务的数据解析不出来」就让整个详情接口 500，
 * 会让历史任务永远打不开 —— 而历史数据恰恰是这类工具最常被翻看的东西。
 * 解析不出来时宁可让那一块显示「暂无」，也不要让接口整体失败。
 *
 * <p>用 core 的 {@link JsonCodec} 而不是注入 Spring Boot 的 ObjectMapper：
 * 这里读的就是 {@code dag_node.result} 里的 checkpoint，写它的是 Worker 进程。
 * 读写两端必须用同一套配置，否则「Worker 写得进、API 读不出」这类问题会以
 * 「指标偶尔为空」的形式出现，而且只在跨进程跑时才复现。
 */
@Component
public class TaskViewMapper {

    private final JsonCodec json;

    public TaskViewMapper(JsonCodec json) {
        this.json = json;
    }

    public TaskView toView(MigrationTask task) {
        return new TaskView(
                task.id(),
                task.projectRoot(),
                task.entryFile(),
                task.targetJdk(),
                task.status() == null ? null : task.status().name(),
                task.failReason(),
                task.createdAt(),
                task.updatedAt(),
                parseMetrics(task.metricsJson()));
    }

    public TaskDetailView toDetail(MigrationTask task, List<DagNode> nodes,
                                   List<PatchRecord> patches, TaskStore.CostSummary cost,
                                   boolean planApproved, HumanGate openGate) {
        // 补丁表里只有 node_id，轮次在节点上。回退重写会让同一个文件产生多份补丁，
        // 只靠文件名分不出先后 —— 在这里就把轮次并进补丁视图，前端不必再去 join 节点。
        Map<Long, Integer> attemptByNodeId = new HashMap<>();
        for (DagNode node : nodes) {
            attemptByNodeId.put(node.id(), node.attempt());
        }
        return new TaskDetailView(
                toView(task),
                nodes.stream().map(this::toNode).toList(),
                patches.stream()
                        .map(patch -> new TaskDetailView.PatchView(
                                patch.nodeId(), patch.filePath(), patch.diff(),
                                attemptByNodeId.getOrDefault(patch.nodeId(), 0)))
                        .toList(),
                new TaskDetailView.CostView(
                        cost.calls(), cost.promptTokens(), cost.completionTokens(), cost.totalCost()),
                toPlan(nodes, planApproved),
                toGate(openGate, nodes));
    }

    /**
     * 把「当前等待中的门禁」摊成评审卡片要的形状；没有门禁时返回 null。
     *
     * <p>节点键从节点列表里按 id 反查 —— human_gate 表只存 node_id，
     * 而前端要显示「哪道门、拦的是哪个文件」，这层 join 在服务端做掉，
     * 前端不必为了渲染一句话再去节点数组里找。
     */
    private TaskDetailView.GateView toGate(HumanGate gate, List<DagNode> nodes) {
        if (gate == null) {
            return null;
        }
        String nodeKey = nodes.stream()
                .filter(node -> node.id() != null && node.id() == gate.nodeId())
                .map(DagNode::nodeKey)
                .findFirst()
                .orElse(null);
        return new TaskDetailView.GateView(
                gate.id() == null ? 0L : gate.id(),
                gate.nodeId(),
                nodeKey,
                filePathOf(nodeKey),
                gate.status() == null ? null : gate.status().name(),
                gate.comment(),
                gate.createdAt(),
                gate.decidedAt());
    }

    /** 取节点键中 ':' 之后的部分（文件路径）；裸键返回 null。 */
    private static String filePathOf(String nodeKey) {
        if (nodeKey == null) {
            return null;
        }
        int colon = nodeKey.indexOf(':');
        return (colon < 0 || colon == nodeKey.length() - 1) ? null : nodeKey.substring(colon + 1);
    }

    /**
     * 从节点流水中捞出 PLAN 节点的产出，摊成评审界面要的形状。
     *
     * <p>取<b>最新一个成功</b>的 PLAN 节点，而不是「第一个」或「最后一个」：
     * 任务可能因为 Worker 重启而重跑 PLAN（成功后仍重入队），此时最新那份才是当前生效的计划。
     * 解析失败返回 null —— 指标 JSON 是历史数据，格式可能已随版本变化，
     * 为了「一条老任务的计划读不出来」而让整个详情接口 500，得不偿失（同本类顶部的约定）。
     */
    private TaskDetailView.PlanView toPlan(List<DagNode> nodes, boolean approved) {
        DagNode latest = null;
        for (DagNode node : nodes) {
            if (node.nodeType() != NodeType.PLAN || node.status() != NodeStatus.SUCCEEDED) {
                continue;
            }
            if (latest == null || node.id() > latest.id()) {
                latest = node;
            }
        }
        if (latest == null) {
            return null;
        }
        PlanResult plan = read(latest.resultJson(), PlanResult.class);
        if (plan == null) {
            return null;
        }
        List<TaskDetailView.PlanStepView> steps = plan.steps() == null ? List.of()
                : plan.steps().stream()
                .map(step -> new TaskDetailView.PlanStepView(step.filePath(), step.rationale()))
                .toList();
        return new TaskDetailView.PlanView(plan.summary(), steps, approved);
    }

    private TaskDetailView.NodeView toNode(DagNode node) {
        return new TaskDetailView.NodeView(
                node.id(),
                node.nodeKey(),
                node.nodeType() == null ? null : node.nodeType().name(),
                node.status() == null ? null : node.status().name(),
                node.attempt(),
                node.dependsOn(),
                node.error(),
                node.startedAt(),
                node.finishedAt(),
                node.nodeType() == NodeType.VERIFY ? parseVerify(node.resultJson()) : null);
    }

    private TaskView.MetricsView parseMetrics(String jsonText) {
        TaskMetrics metrics = read(jsonText, TaskMetrics.class);
        if (metrics == null) {
            return null;
        }
        return new TaskView.MetricsView(
                metrics.filesTotal(),
                metrics.compilePassed(),
                metrics.compilePassRate(),
                metrics.testsTotal(),
                metrics.testsPassed(),
                metrics.testPassRate(),
                metrics.coverage(),
                metrics.llmCalls(),
                metrics.promptTokens(),
                metrics.completionTokens(),
                metrics.totalCost(),
                metrics.verifyAttempts(),
                metrics.retried(),
                metrics.durationMs());
    }

    private TaskDetailView.VerifyView parseVerify(String jsonText) {
        VerifyResult result = read(jsonText, VerifyResult.class);
        if (result == null) {
            return null;
        }
        return new TaskDetailView.VerifyView(
                result.compiled(),
                result.testsTotal(),
                result.testsPassed(),
                result.testsFailed(),
                result.testsSkipped(),
                result.coverage(),
                result.timedOut(),
                result.failureExcerpt());
    }

    private <T> T read(String jsonText, Class<T> type) {
        return json.read(jsonText, type).orElse(null);
    }
}
