package com.remasteragent.core.store;

import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.GateStatus;
import com.remasteragent.common.domain.HumanGate;
import com.remasteragent.common.domain.LlmCallRecord;
import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.NodeStatus;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.domain.PatchRecord;
import com.remasteragent.common.domain.SourceWriteBack;
import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.common.domain.TraceSpan;
import com.remasteragent.core.codec.JsonCodec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private final Set<Long> approvedPlans = new java.util.LinkedHashSet<>();
    private final Map<Long, HumanGate> gates = new LinkedHashMap<>();
    private final List<TraceSpan> spans = new ArrayList<>();
    private final List<SourceWriteBack> writeBacks = new ArrayList<>();

    private long taskSeq = 0;
    private long nodeSeq = 0;
    private long patchSeq = 0;
    private long gateSeq = 0;
    private long writeBackSeq = 0;

    // ------------------------------------------------------------------
    // 任务
    // ------------------------------------------------------------------

    @Override
    public long createTask(String projectRoot, String entryFile, int targetJdk, String name, boolean demo) {
        long id = ++taskSeq;
        Instant now = Instant.now();
        tasks.put(id, new MigrationTask(id, projectRoot, entryFile, targetJdk,
                TaskStatus.PENDING, null, null, false, now, now, name, demo));
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
                status, metricsJson, failReason, task.cancelRequested(), task.createdAt(), Instant.now(),
                task.name(), task.demo());
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
    // 规划评审（阶段 2）
    // ------------------------------------------------------------------

    @Override
    public void approvePlan(long taskId) {
        approvedPlans.add(taskId);
    }

    @Override
    public boolean isPlanApproved(long taskId) {
        return approvedPlans.contains(taskId);
    }

    // ------------------------------------------------------------------
    // 人工门禁（阶段 3）
    // ------------------------------------------------------------------

    @Override
    public long insertGate(long nodeId, String comment) {
        long id = ++gateSeq;
        gates.put(id, new HumanGate(id, nodeId, GateStatus.PENDING, null, comment, Instant.now(), null));
        return id;
    }

    @Override
    public Optional<HumanGate> findOpenGate(long taskId) {
        Set<Long> nodeIds = findNodes(taskId).stream().map(DagNode::id).collect(Collectors.toSet());
        return gates.values().stream()
                .filter(gate -> gate.status() == GateStatus.PENDING && nodeIds.contains(gate.nodeId()))
                .max(Comparator.comparing(HumanGate::id));
    }

    @Override
    public Optional<HumanGate> findGate(long gateId) {
        return Optional.ofNullable(gates.get(gateId));
    }

    @Override
    public List<HumanGate> findGates(long taskId) {
        Set<Long> nodeIds = findNodes(taskId).stream().map(DagNode::id).collect(Collectors.toSet());
        return gates.values().stream()
                .filter(gate -> nodeIds.contains(gate.nodeId()))
                .sorted(Comparator.comparing(HumanGate::id))
                .toList();
    }

    @Override
    public int decideGate(long gateId, GateStatus status, String reviewer, String comment) {
        HumanGate current = gates.get(gateId);
        if (current == null || current.status() != GateStatus.PENDING) {
            return 0;
        }
        gates.put(gateId, new HumanGate(current.id(), current.nodeId(), status, reviewer,
                comment, current.createdAt(), Instant.now()));
        return 1;
    }

    @Override
    public List<OpenGateRef> findOverdueGates(Instant threshold) {
        return gates.values().stream()
                .filter(gate -> gate.status() == GateStatus.PENDING)
                .filter(gate -> gate.createdAt() != null && gate.createdAt().isBefore(threshold))
                .map(gate -> {
                    DagNode node = nodes.get(gate.nodeId());
                    return new OpenGateRef(gate.id(), gate.nodeId(),
                            node == null ? 0L : node.taskId(),
                            node == null ? null : node.nodeKey(),
                            gate.createdAt());
                })
                .sorted(Comparator.comparing(OpenGateRef::createdAt))
                .toList();
    }

    // ------------------------------------------------------------------
    // 任务控制（取消 / 重跑）
    // ------------------------------------------------------------------

    /**
     * 取消标志直接写在任务记录上，而不是另开一个 {@code Set<Long>} 存着。
     *
     * <p>后者会让同一个语义有两个来源：{@code findTask().cancelRequested()} 读的是记录里的字段，
     * 而 {@code isCancelRequested()} 读的是集合 —— 一旦只更新了其中一个，就会出现
     * 「接口说取消了、详情页却显示没取消」这类只在特定用例里复现的假象。
     */
    @Override
    public void requestCancel(long taskId) {
        tasks.computeIfPresent(taskId, (id, task) -> withCancel(task, true));
    }

    @Override
    public boolean isCancelRequested(long taskId) {
        MigrationTask task = tasks.get(taskId);
        return task != null && task.cancelRequested();
    }

    @Override
    public void clearCancelRequest(long taskId) {
        tasks.computeIfPresent(taskId, (id, task) -> withCancel(task, false));
    }

    private static MigrationTask withCancel(MigrationTask task, boolean cancelRequested) {
        return new MigrationTask(task.id(), task.projectRoot(), task.entryFile(), task.targetJdk(),
                task.status(), task.metricsJson(), task.failReason(), cancelRequested,
                task.createdAt(), Instant.now(), task.name(), task.demo());
    }

    @Override
    public int countActiveDemoTasks() {
        return (int) tasks.values().stream()
                .filter(task -> task.demo()
                        && task.status() != TaskStatus.SUCCEEDED
                        && task.status() != TaskStatus.FAILED
                        && task.status() != TaskStatus.CANCELLED)
                .count();
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
    public void markNodePending(long nodeId) {
        replace(nodeId, node -> with(node, NodeStatus.PENDING, node.resultJson(), node.error()));
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
        // 与 JdbcTaskStore 同一口径：轮次 = 真正执行过的 VERIFY 轮数（去重后的 distinct attempt），
        // 不是节点总数，也不是「最大 attempt + 1」。被回退重铺、最终 SKIPPED 的 VERIFY 节点不计入，
        // 否则 reissueBatchVerify 因 REWRITE 失败而自增的节点 attempt 会把轮次胀穿。
        return (int) findNodes(taskId).stream()
                .filter(node -> node.nodeType() == NodeType.VERIFY
                        && node.status() != NodeStatus.SKIPPED)
                .map(DagNode::attempt)
                .distinct()
                .count();
    }

    @Override
    public int resetStaleRunningNodes(long taskId) {
        List<Long> stale = findNodes(taskId).stream()
                .filter(node -> node.status() == NodeStatus.RUNNING)
                .map(DagNode::id)
                .toList();
        stale.forEach(nodeId -> replace(nodeId, node -> with(node, NodeStatus.PENDING,
                node.resultJson(), node.error())));
        return stale.size();
    }

    @Override
    public int resetFailedNodes(long taskId) {
        List<Long> failed = findNodes(taskId).stream()
                .filter(node -> node.status() == NodeStatus.FAILED || node.status() == NodeStatus.SKIPPED)
                .map(DagNode::id)
                .toList();
        // 与 JDBC 实现一致：error 与 result 一并清空，避免上一轮的失败原因留在新一轮的现场里
        failed.forEach(nodeId -> replace(nodeId, node -> new DagNode(
                node.id(), node.taskId(), node.nodeKey(), node.nodeType(), node.dependsOn(),
                NodeStatus.PENDING, node.attempt(), null, null, null, null)));
        return failed.size();
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
    // 全链路 Trace
    // ------------------------------------------------------------------

    @Override
    public List<TraceSpan> findTraceSpans(long taskId) {
        return spans.stream()
                .filter(span -> span.taskId() != null && span.taskId() == taskId)
                .sorted(Comparator.comparing(TraceSpan::startAt))
                .toList();
    }

    @Override
    public Map<Long, List<TraceSpan>> findTraceSpansByTasks(Collection<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return Map.of();
        }
        // LinkedHashSet 去重并保住「首次出现」的顺序 —— 与 JDBC 实现一样，
        // 返回的 Map 里不含没有 span 的任务。
        Map<Long, List<TraceSpan>> byTask = new LinkedHashMap<>();
        for (Long id : new LinkedHashSet<>(taskIds)) {
            if (id == null) {
                continue;
            }
            List<TraceSpan> rows = findTraceSpans(id);
            if (!rows.isEmpty()) {
                byTask.put(id, rows);
            }
        }
        return byTask;
    }

    @Override
    public Optional<String> findTraceId(long taskId) {
        return findTraceSpans(taskId).stream().map(TraceSpan::traceId).findFirst();
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

    // ------------------------------------------------------------------
    // 变更回写审计
    // ------------------------------------------------------------------

    @Override
    public long insertWriteBack(long taskId, String projectRoot, String backupDir,
                                String filesJson, int fileCount) {
        long id = ++writeBackSeq;
        // 与生产实现同一语义：files 在库里是 JSONB，这里存成解析后的对象列表 ——
        // 单测关心的是「清单能被读回来」，不是 JSON 文本长什么样
        List<SourceWriteBack.AppliedFile> files = new JsonCodec()
                .readList(filesJson, SourceWriteBack.AppliedFile.class)
                .orElse(List.of());
        writeBacks.add(new SourceWriteBack(id, taskId, projectRoot, backupDir,
                files, fileCount, Instant.now()));
        return id;
    }

    @Override
    public List<SourceWriteBack> findWriteBacks(long taskId) {
        return writeBacks.stream().filter(w -> w.taskId() == taskId).toList();
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

    /** 直接塞一个 span —— 用于验证「读取侧」的形状，不经过 OTel SDK。 */
    public void addSpan(TraceSpan span) {
        spans.add(span);
    }
}
