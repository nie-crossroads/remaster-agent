package com.remasteragent.web.api;

import com.remasteragent.common.agent.VerifyResult;
import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.domain.PatchRecord;
import com.remasteragent.common.domain.TaskMetrics;
import com.remasteragent.core.codec.JsonCodec;
import com.remasteragent.core.store.TaskStore;
import com.remasteragent.web.api.dto.TaskDetailView;
import com.remasteragent.web.api.dto.TaskView;
import org.springframework.stereotype.Component;

import java.util.List;

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
                                   List<PatchRecord> patches, TaskStore.CostSummary cost) {
        return new TaskDetailView(
                toView(task),
                nodes.stream().map(this::toNode).toList(),
                patches.stream()
                        .map(patch -> new TaskDetailView.PatchView(
                                patch.nodeId(), patch.filePath(), patch.diff()))
                        .toList(),
                new TaskDetailView.CostView(
                        cost.calls(), cost.promptTokens(), cost.completionTokens(), cost.totalCost()));
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
