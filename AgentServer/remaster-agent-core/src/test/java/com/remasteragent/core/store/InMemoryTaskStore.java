package com.remasteragent.core.store;

import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.LlmCallRecord;
import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.NodeStatus;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.domain.PatchRecord;
import com.remasteragent.common.domain.TaskStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * {@link TaskStore} 的内存实现 —— 只服务于单测，不是生产代码。
 *
 * <p>它存在的意义就是让「回退重写」这条最关键的编排路径能被**确定性地**验证：
 * 不连数据库、不调模型、不跑 Maven，毫秒级得到稳定结论。
 * 生产实现是 {@link JdbcTaskStore}（PostgreSQL）。
 *
 * <p>语义上刻意对齐 JDBC 实现，尤其是两点：
 * <ul>
 *   <li>{@code findRunnable} 是「按需算就绪」，而不是缓存拓扑序 —— 这样运行期新增的
 *       attempt+1 节点才会被下一轮调度看见。</li>
 *   <li>节点是不可变记录，所有状态变更都是「换一行」，与原表 UPDATE 的语义一致。</li>
 * </ul>
 */
public final class InMemoryTaskStore implements TaskStore {

    private final Map<Long, MigrationTask> tasks = new LinkedHashMap<>();
    private final Map<Long, DagNode> nodes = new LinkedHashMap<>();
    private final Map<Long, String> metrics = new LinkedHashMap<>();
    private final List<PatchRecord> patches = new ArrayList<>();
    private final List<LlmCallRecord> llmCalls = new ArrayList<>();

    private long taskSeq = 0;
    private long nodeSeq = 0;
    private long patchSeq = 0;

    // ------------------------------------------------------------------
    // 任务
    // ------------------------------------------------------------------

    @Override
    public long createTask(String projectRoot, String entryFile, int targetJdk) {
        long id = ++taskSeq;
        Instant now = Instant.now();
        tasks.put(id, new MigrationTask(id, projectRoot, entryFile, targetJdk,
                TaskStatus.PENDING, null, null, now, now));
        return id;
    }

    @Override
    public Optional<MigrationTask> findTask(long taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public void updateTaskStatus(long taskId, TaskStatus status, String failReason) {
        tasks.computeIfPresent(taskId, (id, task) -> copy(task, status, task.metricsJson(), failReason));
    }

    @Override
    public void saveTaskMetrics(long taskId, String metricsJson) {
        metrics.put(taskId, metricsJson);
        tasks.computeIfPresent(taskId, (id, task) -> copy(task, task.status(), metricsJson, task.failReason()));
    }

    private static MigrationTask copy(MigrationTask task, TaskStatus status, String metricsJson, String failReason) {
        return new MigrationTask(task.id(), task.projectRoot(), task.entryFile(), task.targetJdk(),
                status, metricsJson, failReason, task.createdAt(), Instant.now());
    }

    @Override
    public List<Long> findPendingTaskIds(int limit) {
        return tasks.values().stream()
                .filter(task -> task.status() == TaskStatus.PENDING)
                .sorted(Comparator.comparing(MigrationTask::createdAt))
                .limit(limit)
                .map(MigrationTask::id)
                .toList();
    }

    @Override
    public List<MigrationTask> findRecentTasks(int limit) {
        return tasks.values().stream()
                .sorted(Comparator.comparing(MigrationTask::id).reversed())
                .limit(limit)
                .toList();
    }

    // ------------------------------------------------------------------
    // 节点
    // ------------------------------------------------------------------

    @Override
    public long insertNode(long taskId, String nodeKey, NodeType nodeType, List<Long> dependsOn, int attempt) {
        long id = ++nodeSeq;
        nodes.put(id, new DagNode(id, taskId, nodeKey, nodeType, List.copyOf(dependsOn),
                NodeStatus.PENDING, attempt, null, null, null, null));
        return id;
    }

    @Override
    public Optional<DagNode> findNode(long taskId, String nodeKey, int attempt) {
        return nodes.values().stream()
                .filter(node -> node.taskId() == taskId
                        && node.nodeKey().equals(nodeKey)
                        && node.attempt() == attempt)
                .findFirst();
    }

    @Override
    public List<DagNode> findNodes(long taskId) {
        return nodes.values().stream()
                .filter(node -> node.taskId() == taskId)
                .toList();
    }

    @Override
    public List<DagNode> findRunnable(long taskId) {
        List<DagNode> all = findNodes(taskId);
        Set<Long> succeeded = all.stream()
                .filter(node -> node.status() == NodeStatus.SUCCEEDED)
                .map(DagNode::id)
                .collect(Collectors.toSet());
        return all.stream()
                .filter(node -> node.status() == NodeStatus.PENDING)
                .filter(node -> succeeded.containsAll(node.dependsOn()))
                .toList();
    }

    @Override
    public void markNodeRunning(long nodeId) {
        replace(nodeId, node -> with(node, NodeStatus.RUNNING, node.resultJson(), node.error()));
    }

    @Override
    public void markNodeSucceeded(long nodeId, String resultJson) {
        replace(nodeId, node -> with(node, NodeStatus.SUCCEEDED, resultJson, null));
    }

    @Override
    public void markNodeFailed(long nodeId, String error, String resultJson) {
        replace(nodeId, node -> with(node, NodeStatus.FAILED, resultJson, error));
    }

    @Override
    public void markNodeSkipped(long nodeId, String error) {
        replace(nodeId, node -> with(node, NodeStatus.SKIPPED, node.resultJson(), error));
    }

    @Override
    public int countNodes(long taskId, NodeStatus status) {
        return (int) findNodes(taskId).stream().filter(node -> node.status() == status).count();
    }

    @Override
    public int countVerifyRounds(long taskId) {
        return (int) findNodes(taskId).stream()
                .filter(node -> node.nodeType() == NodeType.VERIFY)
                .count();
    }

    @Override
    public int resetStaleRunningNodes() {
        List<Long> stale = nodes.values().stream()
                .filter(node -> node.status() == NodeStatus.RUNNING)
                .map(DagNode::id)
                .toList();
        stale.forEach(nodeId -> replace(nodeId, node -> with(node, NodeStatus.PENDING,
                node.resultJson(), node.error())));
        return stale.size();
    }

    @Override
    public Optional<DagNode> findLatestSucceeded(long taskId, String nodeKey) {
        return nodes.values().stream()
                .filter(node -> node.taskId() == taskId
                        && node.nodeKey().equals(nodeKey)
                        && node.status() == NodeStatus.SUCCEEDED)
                .max(Comparator.comparing(DagNode::id));
    }

    private void replace(long nodeId, UnaryOperator<DagNode> mutation) {
        DagNode current = nodes.get(nodeId);
        if (current == null) {
            throw new IllegalArgumentException("节点不存在: " + nodeId);
        }
        nodes.put(nodeId, mutation.apply(current));
    }

    private static DagNode with(DagNode node, NodeStatus status, String resultJson, String error) {
        return new DagNode(node.id(), node.taskId(), node.nodeKey(), node.nodeType(), node.dependsOn(),
                status, node.attempt(), resultJson, error, node.startedAt(), node.finishedAt());
    }

    // ------------------------------------------------------------------
    // 补丁 / 成本
    // ------------------------------------------------------------------

    @Override
    public long insertPatch(long nodeId, String filePath, String diff, String originalHash) {
        long id = ++patchSeq;
        patches.add(new PatchRecord(id, nodeId, filePath, diff, originalHash, true, Instant.now()));
        return id;
    }

    @Override
    public List<PatchRecord> findPatches(long taskId) {
        Set<Long> nodeIds = findNodes(taskId).stream().map(DagNode::id).collect(Collectors.toSet());
        return patches.stream().filter(patch -> nodeIds.contains(patch.nodeId())).toList();
    }

    @Override
    public void recordLlmCall(LlmCallRecord record) {
        llmCalls.add(record);
    }

    @Override
    public List<LlmCallRecord> findLlmCalls(long taskId) {
        return llmCalls.stream()
                .filter(call -> call.taskId() != null && call.taskId() == taskId)
                .toList();
    }

    @Override
    public CostSummary summarizeCost(long taskId) {
        List<LlmCallRecord> calls = findLlmCalls(taskId);
        return new CostSummary(
                calls.size(),
                calls.stream().mapToLong(LlmCallRecord::promptTokens).sum(),
                calls.stream().mapToLong(LlmCallRecord::completionTokens).sum(),
                calls.stream().mapToDouble(LlmCallRecord::cost).sum());
    }

    // ------------------------------------------------------------------
    // 测试辅助（不属于 TaskStore 契约）
    // ------------------------------------------------------------------

    /** 供断言读取：任务收尾时写入的指标 JSON。 */
    public String metricsOf(long taskId) {
        return metrics.get(taskId);
    }

    /** 供断言读取：某个 (nodeKey, attempt) 节点的状态。 */
    public NodeStatus statusOf(long taskId, String nodeKey, int attempt) {
        return findNode(taskId, nodeKey, attempt)
                .map(DagNode::status)
                .orElseThrow(() -> new AssertionError(
                        "节点不存在: task=" + taskId + " key=" + nodeKey + " attempt=" + attempt));
    }

    /** 供断言读取：某个 (nodeKey, attempt) 节点是否存在。 */
    public boolean hasNode(long taskId, String nodeKey, int attempt) {
        return findNode(taskId, nodeKey, attempt).isPresent();
    }

    /** 供断言读取：某类型的全部节点尝试轮次，升序。 */
    public List<Integer> attemptsOf(long taskId, NodeType type) {
        return findNodes(taskId).stream()
                .filter(node -> node.nodeType() == type)
                .map(DagNode::attempt)
                .distinct()
                .sorted()
                .toList();
    }
}
