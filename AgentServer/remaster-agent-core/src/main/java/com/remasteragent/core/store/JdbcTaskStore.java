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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@link TaskStore} 的 PostgreSQL 实现。
 *
 * <p>几个实现细节值得单独说明，它们都是踩过才会知道的：
 *
 * <ul>
 *   <li><b>JSONB 写入用 {@code CAST(? AS jsonb)}</b> 而不是 {@code ?::jsonb}。
 *       后者在 JDBC 预处理语句里容易与驱动自身的参数解析打架，前者是最稳的写法。</li>
 *   <li><b>{@code depends_on} 是 bigint[]</b>，用 {@code CAST(? AS bigint[])} 传
 *       {@code "{1,2}"} 形式，避免为一个数组列引入自定义 TypeHandler。
 *       这不优雅，但它换来的是零配置 —— 在这个项目里，可读性比省几个字符重要。</li>
 *   <li><b>就绪判定写成一个 SQL</b>，用 {@code unnest} 展开依赖数组做 NOT EXISTS。
 *       如果改成「查出来在 Java 里判断」，就会在节点数增长后变成 N+1 查询。</li>
 *   <li><b>回读自增主键必须显式指定列名</b>（见 {@link #ID_COLUMN}），
 *       不能用 {@code Statement.RETURN_GENERATED_KEYS}。这是 PostgreSQL 上的经典坑，
 *       理由写在 {@link #requireKey(KeyHolder)} 上。</li>
 * </ul>
 */
@Repository
public class JdbcTaskStore implements TaskStore {

    /**
     * 回读自增主键时显式声明的列名。
     *
     * <p>不要退回 {@code Statement.RETURN_GENERATED_KEYS}：那在 PostgreSQL 上等价于
     * 「把整行都返回给我」，而不是只返回生成的键。
     */
    private static final String[] ID_COLUMN = {"id"};

    private final JdbcTemplate jdbc;

    /**
     * 读 JSONB 列（回写审计里的文件清单）要用编排层自己的编解码器 ——
     * 不能用 Spring 的 {@code ObjectMapper}：Worker 进程刻意不带 web starter，
     * 容器里根本没有那个 Bean（理由见 {@code JsonCodec}）。
     */
    private final JsonCodec json;

    public JdbcTaskStore(JdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------
    // 任务
    // ------------------------------------------------------------------

    @Override
    public long createTask(String projectRoot, String entryFile, int targetJdk, String name, boolean demo) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO migration_task (project_root, entry_file, target_jdk, name, status, demo)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, ID_COLUMN);
            ps.setString(1, projectRoot);
            ps.setString(2, entryFile);
            ps.setInt(3, targetJdk);
            ps.setString(4, name);
            ps.setString(5, TaskStatus.PENDING.name());
            ps.setBoolean(6, demo);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    @Override
    public Optional<MigrationTask> findTask(long taskId) {
        List<MigrationTask> rows = jdbc.query("""
                SELECT id, project_root, entry_file, target_jdk, name, status, metrics, fail_reason,
                       cancel_requested, created_at, updated_at, demo
                  FROM migration_task WHERE id = ?
                """, TASK_MAPPER, taskId);
        return rows.stream().findFirst();
    }

    @Override
    public void updateTaskStatus(long taskId, TaskStatus status, String failReason) {
        jdbc.update("""
                UPDATE migration_task SET status = ?, fail_reason = ?, updated_at = now() WHERE id = ?
                """, status.name(), failReason, taskId);
    }

    @Override
    public void saveTaskMetrics(long taskId, String metricsJson) {
        jdbc.update("""
                UPDATE migration_task SET metrics = CAST(? AS jsonb), updated_at = now() WHERE id = ?
                """, metricsJson, taskId);
    }

    @Override
    public List<Long> findPendingTaskIds(int limit) {
        return jdbc.queryForList("""
                SELECT id FROM migration_task
                 WHERE status = 'PENDING' ORDER BY created_at LIMIT ?
                """, Long.class, limit);
    }

    @Override
    public List<MigrationTask> findRecentTasks(int limit) {
        // 次级排序键用 id 而不是 updated_at：同一秒内创建的任务在时间上无法区分，
        // 加上 id 才能保证顺序稳定（否则列表会在两次请求之间莫名换位）
        // 演示任务（demo=true）不过滤：用户在工作台选样本跑完，要能在列表里看到自己那一条。
        // 它靠 demo 标记在前端显示「演示」角标来区分，而不是靠隐藏。
        return jdbc.query("""
                SELECT id, project_root, entry_file, target_jdk, name, status, metrics, fail_reason,
                       cancel_requested, created_at, updated_at, demo
                  FROM migration_task
                 ORDER BY created_at DESC, id DESC
                 LIMIT ?
                """, TASK_MAPPER, limit);
    }

    @Override
    public int countActiveDemoTasks() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM migration_task
                 WHERE demo = TRUE
                   AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                """, Integer.class);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------
    // 规划评审（阶段 2）
    // ------------------------------------------------------------------

    @Override
    public void approvePlan(long taskId) {
        jdbc.update("""
                UPDATE migration_task SET plan_approved = TRUE, updated_at = now() WHERE id = ?
                """, taskId);
    }

    @Override
    public boolean isPlanApproved(long taskId) {
        Boolean approved = jdbc.queryForObject(
                "SELECT plan_approved FROM migration_task WHERE id = ?", Boolean.class, taskId);
        return approved != null && approved;
    }

    // ------------------------------------------------------------------
    // 人工门禁（阶段 3）
    // ------------------------------------------------------------------

    @Override
    public long insertGate(long nodeId, String comment) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO human_gate (node_id, status, comment)
                    VALUES (?, ?, ?)
                    """, ID_COLUMN);
            ps.setLong(1, nodeId);
            ps.setString(2, GateStatus.PENDING.name());
            ps.setString(3, comment);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    @Override
    public Optional<HumanGate> findOpenGate(long taskId) {
        // human_gate 表没有 task_id 列，靠 node_id → dag_node.task_id 反查。
        // 取最新一行：同一任务先后可能有多道门，只有最后那道才是此刻挡路的。
        List<HumanGate> rows = jdbc.query("""
                SELECT g.* FROM human_gate g
                  JOIN dag_node n ON n.id = g.node_id
                 WHERE n.task_id = ? AND g.status = 'PENDING'
                 ORDER BY g.id DESC LIMIT 1
                """, GATE_MAPPER, taskId);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<HumanGate> findGate(long gateId) {
        List<HumanGate> rows = jdbc.query(
                "SELECT * FROM human_gate WHERE id = ?", GATE_MAPPER, gateId);
        return rows.stream().findFirst();
    }

    @Override
    public List<HumanGate> findGates(long taskId) {
        return jdbc.query("""
                SELECT g.* FROM human_gate g
                  JOIN dag_node n ON n.id = g.node_id
                 WHERE n.task_id = ?
                 ORDER BY g.id
                """, GATE_MAPPER, taskId);
    }

    @Override
    public int decideGate(long gateId, GateStatus status, String reviewer, String comment) {
        // WHERE status = 'PENDING' 是并发安全的护栏：两个人同时点批准，只有第一个 UPDATE
        // 会命中，第二个影响 0 行 → 调用方据此回 409，不会重复入队把任务跑两遍。
        return jdbc.update("""
                UPDATE human_gate
                   SET status = ?, reviewer = ?, comment = ?, decided_at = now()
                 WHERE id = ? AND status = 'PENDING'
                """, status.name(), reviewer, comment, gateId);
    }

    @Override
    public List<OpenGateRef> findOverdueGates(Instant threshold) {
        // 走 idx_human_gate_pending（partial index）拿到 PENDING 那几行，再 join 出归属任务与节点键。
        // 不按 decided_at 过滤：PENDING 的行 decided_at 恒为 null，时间判据只能是 created_at。
        return jdbc.query("""
                SELECT g.id AS gate_id, g.node_id, n.task_id, n.node_key, g.created_at
                  FROM human_gate g
                  JOIN dag_node n ON n.id = g.node_id
                 WHERE g.status = 'PENDING' AND g.created_at < ?
                 ORDER BY g.created_at
                """, (rs, rowNum) -> new OpenGateRef(
                rs.getLong("gate_id"),
                rs.getLong("node_id"),
                rs.getLong("task_id"),
                rs.getString("node_key"),
                toInstant(rs.getTimestamp("created_at"))), Timestamp.from(threshold));
    }

    // ------------------------------------------------------------------
    // 任务控制（取消 / 重跑）
    // ------------------------------------------------------------------

    @Override
    public void requestCancel(long taskId) {
        jdbc.update("""
                UPDATE migration_task SET cancel_requested = TRUE, updated_at = now() WHERE id = ?
                """, taskId);
    }

    @Override
    public boolean isCancelRequested(long taskId) {
        Boolean requested = jdbc.queryForObject(
                "SELECT cancel_requested FROM migration_task WHERE id = ?", Boolean.class, taskId);
        return requested != null && requested;
    }

    @Override
    public void clearCancelRequest(long taskId) {
        jdbc.update("""
                UPDATE migration_task SET cancel_requested = FALSE, updated_at = now() WHERE id = ?
                """, taskId);
    }

    // ------------------------------------------------------------------
    // 节点
    // ------------------------------------------------------------------

    @Override
    public long insertNode(long taskId, String nodeKey, NodeType nodeType,
                           List<Long> dependsOn, int attempt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO dag_node (task_id, node_key, node_type, depends_on, status, attempt)
                    VALUES (?, ?, ?, CAST(? AS bigint[]), ?, ?)
                    """, ID_COLUMN);
            ps.setLong(1, taskId);
            ps.setString(2, nodeKey);
            ps.setString(3, nodeType.name());
            ps.setString(4, toArrayLiteral(dependsOn));
            ps.setString(5, NodeStatus.PENDING.name());
            ps.setInt(6, attempt);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    @Override
    public Optional<DagNode> findNode(long taskId, String nodeKey, int attempt) {
        List<DagNode> rows = jdbc.query("""
                SELECT * FROM dag_node WHERE task_id = ? AND node_key = ? AND attempt = ?
                """, NODE_MAPPER, taskId, nodeKey, attempt);
        return rows.stream().findFirst();
    }

    @Override
    public List<DagNode> findNodes(long taskId) {
        return jdbc.query("SELECT * FROM dag_node WHERE task_id = ? ORDER BY id", NODE_MAPPER, taskId);
    }

    @Override
    public List<DagNode> findRunnable(long taskId) {
        return jdbc.query("""
                SELECT n.* FROM dag_node n
                 WHERE n.task_id = ?
                   AND n.status = 'PENDING'
                   AND NOT EXISTS (
                        SELECT 1 FROM unnest(n.depends_on) AS dep(id)
                         WHERE NOT EXISTS (
                               SELECT 1 FROM dag_node d WHERE d.id = dep.id AND d.status = 'SUCCEEDED')
                   )
                 ORDER BY n.id
                """, NODE_MAPPER, taskId);
    }

    @Override
    public void markNodeRunning(long nodeId) {
        jdbc.update("UPDATE dag_node SET status = 'RUNNING', started_at = now() WHERE id = ?", nodeId);
    }

    @Override
    public void markNodePending(long nodeId) {
        // 同时把 started_at 清掉：它还没跑完，不该挂着一个开始时刻让人以为卡在执行中
        jdbc.update("UPDATE dag_node SET status = 'PENDING', started_at = NULL WHERE id = ?", nodeId);
    }

    @Override
    public void markNodeSucceeded(long nodeId, String resultJson) {
        jdbc.update("""
                UPDATE dag_node SET status = 'SUCCEEDED', result = CAST(? AS jsonb),
                                    error = NULL, finished_at = now()
                 WHERE id = ?
                """, resultJson, nodeId);
    }

    @Override
    public void markNodeFailed(long nodeId, String error, String resultJson) {
        // 失败时也把 result 写下来：VERIFY 失败时那些覆盖率/单测统计恰恰是最该留痕的数据，
        // 只记一句「失败了」会导致事后完全无法回答「它当时离通过还差多少」
        jdbc.update("""
                UPDATE dag_node SET status = 'FAILED', error = ?, result = CAST(? AS jsonb),
                                    finished_at = now()
                 WHERE id = ?
                """, error, resultJson, nodeId);
    }

    @Override
    public void markNodeSkipped(long nodeId, String error) {
        jdbc.update("""
                UPDATE dag_node SET status = 'SKIPPED', error = ?, finished_at = now() WHERE id = ?
                """, error, nodeId);
    }

    @Override
    public int countNodes(long taskId, NodeStatus status) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM dag_node WHERE task_id = ? AND status = ?
                """, Integer.class, taskId, status.name());
        return count == null ? 0 : count;
    }

    @Override
    public int countVerifyRounds(long taskId) {
        // 轮次 = 真正执行过的 VERIFY 轮数（去重后的 distinct attempt），不是节点总数，
        // 也不是「最大 attempt + 1」。多文件计划里每个文件各有一串 VERIFY 节点，按 COUNT(*) 会把
        // 「N 个文件各跑一轮」误报成「回退了 N-1 次」；而被回退重铺、最终 SKIPPED 的 VERIFY 节点
        // （reissueBatchVerify 因 REWRITE 失败而自增的 attempt）必须排除，否则轮次会被胀穿。
        // 没有 VERIFY 节点时 COALESCE 回落 0 —— 那是「没跑过」，不能报 1。
        Integer rounds = jdbc.queryForObject("""
                SELECT COALESCE(COUNT(DISTINCT attempt), 0) FROM dag_node
                 WHERE task_id = ? AND node_type = 'VERIFY' AND status <> 'SKIPPED'
                """, Integer.class, taskId);
        return rounds == null ? 0 : rounds;
    }

    @Override
    public int resetStaleRunningNodes(long taskId) {
        // 条件里必须带 task_id：这是「多 Worker 并存时不会互相重置对方正在跑的节点」的全部依据
        return jdbc.update("""
                UPDATE dag_node SET status = 'PENDING', started_at = NULL
                 WHERE task_id = ? AND status = 'RUNNING'
                """, taskId);
    }

    @Override
    public int resetFailedNodes(long taskId) {
        // FAILED 与 SKIPPED 一起重置：回退链上它们成组出现，只放回一半会让最后一轮 VERIFY
        // 永远停在 SKIPPED，任务立刻再次判失败 —— 表现成「点了重跑但什么都没发生」。
        // error/result 一并清掉：留着上一轮的失败原因会让新的一轮在日志里看起来像没跑过。
        return jdbc.update("""
                UPDATE dag_node
                   SET status = 'PENDING', error = NULL, result = NULL,
                       started_at = NULL, finished_at = NULL
                 WHERE task_id = ? AND status IN ('FAILED', 'SKIPPED')
                """, taskId);
    }

    @Override
    public Optional<DagNode> findLatestSucceeded(long taskId, String nodeKey) {
        List<DagNode> rows = jdbc.query("""
                SELECT * FROM dag_node
                 WHERE task_id = ? AND node_key = ? AND status = 'SUCCEEDED'
                 ORDER BY attempt DESC LIMIT 1
                """, NODE_MAPPER, taskId, nodeKey);
        return rows.stream().findFirst();
    }

    // ------------------------------------------------------------------
    // 全链路 Trace
    // ------------------------------------------------------------------

    @Override
    public List<TraceSpan> findTraceSpans(long taskId) {
        return findTraceSpansByTasks(List.of(taskId)).getOrDefault(taskId, List.of());
    }

    @Override
    public Map<Long, List<TraceSpan>> findTraceSpansByTasks(Collection<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            // 空 IN 列表在 SQL 里是语法错误（`IN ()`），在 Java 侧就返回，
            // 而不是让调用方去猜「列表为空时这个方法会不会炸」。
            return Map.of();
        }
        // 占位符按需拼。这里的个数只由调用方给的 id 数量决定，不来自用户输入 ——
        // 值仍然全部走预编译参数，拼的只是 `?` 的个数。
        String placeholders = String.join(",", Collections.nCopies(taskIds.size(), "?"));
        List<TraceSpan> spans = jdbc.query("""
                SELECT id, trace_id, span_id, parent_span_id, task_id, node_id, name, kind, status,
                       start_at, end_at, duration_ms, attributes
                  FROM trace_span WHERE task_id IN (%s) ORDER BY task_id, start_at, id
                """.formatted(placeholders), TRACE_MAPPER, taskIds.toArray());

        // 分组时保持 LinkedHashMap：入参顺序（taskIds）不入结果，但每组内部
        // 保持 SQL 的 ORDER BY —— 上层要靠「按 start_at 升序」推出分组顺序。
        Map<Long, List<TraceSpan>> byTask = new LinkedHashMap<>();
        for (TraceSpan span : spans) {
            if (span.taskId() != null) {
                byTask.computeIfAbsent(span.taskId(), key -> new ArrayList<>()).add(span);
            }
        }
        return byTask;
    }

    @Override
    public Optional<String> findTraceId(long taskId) {
        // LIMIT 1 就够了：同一任务的全部 span 共用同一个 trace id（OTel 语义保证），
        // 不必去挑「根」那个 —— 按 start_at 取最早的一条只是为了结果稳定可预期。
        List<String> ids = jdbc.queryForList("""
                SELECT trace_id FROM trace_span WHERE task_id = ?
                 ORDER BY start_at, id LIMIT 1
                """, String.class, taskId);
        return ids.stream().findFirst();
    }

    // ------------------------------------------------------------------
    // 补丁
    // ------------------------------------------------------------------

    @Override
    public long insertPatch(long nodeId, String filePath, String diff, String originalHash) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO patch (node_id, file_path, diff, original_hash, applied)
                    VALUES (?, ?, ?, ?, TRUE)
                    """, ID_COLUMN);
            ps.setLong(1, nodeId);
            ps.setString(2, filePath);
            ps.setString(3, diff);
            ps.setString(4, originalHash);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    @Override
    public List<PatchRecord> findPatches(long taskId) {
        return jdbc.query("""
                SELECT p.id, p.node_id, p.file_path, p.diff, p.original_hash, p.applied, p.created_at
                  FROM patch p
                  JOIN dag_node n ON n.id = p.node_id
                 WHERE n.task_id = ?
                 ORDER BY p.id
                """, (rs, rowNum) -> new PatchRecord(
                rs.getLong("id"),
                rs.getLong("node_id"),
                rs.getString("file_path"),
                rs.getString("diff"),
                rs.getString("original_hash"),
                rs.getBoolean("applied"),
                toInstant(rs.getTimestamp("created_at"))), taskId);
    }

    // ------------------------------------------------------------------
    // 变更回写审计
    // ------------------------------------------------------------------

    @Override
    public long insertWriteBack(long taskId, String projectRoot, String backupDir,
                               String filesJson, int fileCount) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO source_write_back (task_id, project_root, backup_dir, files, file_count)
                    VALUES (?, ?, ?, CAST(? AS jsonb), ?)
                    """, ID_COLUMN);
            ps.setLong(1, taskId);
            ps.setString(2, projectRoot);
            ps.setString(3, backupDir);
            ps.setString(4, filesJson);
            ps.setInt(5, fileCount);
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    @Override
    public List<SourceWriteBack> findWriteBacks(long taskId) {
        return jdbc.query("""
                SELECT id, task_id, project_root, backup_dir, files, file_count, applied_at
                  FROM source_write_back
                 WHERE task_id = ?
                 ORDER BY id
                """, (rs, rowNum) -> new SourceWriteBack(
                rs.getLong("id"),
                rs.getLong("task_id"),
                rs.getString("project_root"),
                rs.getString("backup_dir"),
                // 读不出来就当作空清单：回写审计是事后查看用的，一条读不动不该让详情页整体 500
                json.readList(rs.getString("files"), SourceWriteBack.AppliedFile.class)
                        .orElse(List.of()),
                rs.getInt("file_count"),
                toInstant(rs.getTimestamp("applied_at"))), taskId);
    }

    // ------------------------------------------------------------------
    // 成本
    // ------------------------------------------------------------------

    @Override
    public void recordLlmCall(LlmCallRecord record) {
        jdbc.update("""
                INSERT INTO llm_call (task_id, node_id, model, purpose, prompt_tokens,
                                      completion_tokens, cost, latency_ms, trace_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.taskId(), record.nodeId(), record.model(), record.purpose(),
                record.promptTokens(), record.completionTokens(), record.cost(),
                record.latencyMs(), record.traceId());
    }

    @Override
    public List<LlmCallRecord> findLlmCalls(long taskId) {
        return jdbc.query("""
                SELECT id, task_id, node_id, model, purpose, prompt_tokens, completion_tokens,
                       cost, latency_ms, trace_id, created_at
                  FROM llm_call WHERE task_id = ? ORDER BY id
                """, (rs, rowNum) -> new LlmCallRecord(
                rs.getLong("id"),
                rs.getObject("task_id", Long.class),
                rs.getObject("node_id", Long.class),
                rs.getString("model"),
                rs.getString("purpose"),
                rs.getInt("prompt_tokens"),
                rs.getInt("completion_tokens"),
                rs.getDouble("cost"),
                rs.getLong("latency_ms"),
                rs.getString("trace_id"),
                toInstant(rs.getTimestamp("created_at"))), taskId);
    }

    @Override
    public CostSummary summarizeCost(long taskId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)                          AS calls,
                       COALESCE(SUM(prompt_tokens), 0)   AS prompt_tokens,
                       COALESCE(SUM(completion_tokens),0) AS completion_tokens,
                       COALESCE(SUM(cost), 0)            AS total_cost
                  FROM llm_call WHERE task_id = ?
                """, (rs, rowNum) -> new CostSummary(
                rs.getInt("calls"),
                rs.getLong("prompt_tokens"),
                rs.getLong("completion_tokens"),
                rs.getDouble("total_cost")), taskId);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static final RowMapper<MigrationTask> TASK_MAPPER = (rs, rowNum) -> new MigrationTask(
            rs.getLong("id"),
            rs.getString("project_root"),
            rs.getString("entry_file"),
            rs.getInt("target_jdk"),
            TaskStatus.valueOf(rs.getString("status")),
            rs.getString("metrics"),
            rs.getString("fail_reason"),
            rs.getBoolean("cancel_requested"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            rs.getString("name"),
            rs.getBoolean("demo"));

    private static final RowMapper<DagNode> NODE_MAPPER = (rs, rowNum) -> new DagNode(
            rs.getLong("id"),
            rs.getLong("task_id"),
            rs.getString("node_key"),
            NodeType.valueOf(rs.getString("node_type")),
            readLongArray(rs.getArray("depends_on")),
            NodeStatus.valueOf(rs.getString("status")),
            rs.getInt("attempt"),
            rs.getString("result"),
            rs.getString("error"),
            toInstant(rs.getTimestamp("started_at")),
            toInstant(rs.getTimestamp("finished_at")));

    private static final RowMapper<HumanGate> GATE_MAPPER = (rs, rowNum) -> new HumanGate(
            rs.getLong("id"),
            rs.getLong("node_id"),
            GateStatus.valueOf(rs.getString("status")),
            rs.getString("reviewer"),
            rs.getString("comment"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("decided_at")));

    /**
     * trace_span 行映射。
     *
     * <p>{@code task_id} / {@code node_id} / {@code duration_ms} 都用 {@code getObject} 取：
     * 这三列可空，而 {@code getLong} 在 NULL 上会返回 0 —— 「节点 id = 0」这种值会一路
     * 传进前端，表现成时间轴上多出一条指向不存在节点的空记录，很难联想到是取值方式的问题。
     */
    private static final RowMapper<TraceSpan> TRACE_MAPPER = (rs, rowNum) -> new TraceSpan(
            rs.getLong("id"),
            rs.getString("trace_id"),
            rs.getString("span_id"),
            rs.getString("parent_span_id"),
            rs.getObject("task_id", Long.class),
            rs.getObject("node_id", Long.class),
            rs.getString("name"),
            rs.getString("kind"),
            rs.getString("status"),
            toInstant(rs.getTimestamp("start_at")),
            toInstant(rs.getTimestamp("end_at")),
            rs.getObject("duration_ms", Long.class),
            rs.getString("attributes"));

    /** 把 List&lt;Long&gt; 转成 PostgreSQL 数组字面量 {@code "{1,2}"}。 */
    private static String toArrayLiteral(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "{}";
        }
        return ids.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }

    private static List<Long> readLongArray(Array array) {
        if (array == null) {
            return List.of();
        }
        try {
            Object raw = array.getArray();
            if (raw instanceof Object[] objects) {
                return Arrays.stream(objects)
                        .map(o -> o == null ? null : ((Number) o).longValue())
                        .filter(java.util.Objects::nonNull)
                        .toList();
            }
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static long requireKey(KeyHolder keyHolder) {
        // 优先按列名取。PostgreSQL 驱动在 RETURN_GENERATED_KEYS 模式下会把整行都塞进
        // KeyHolder，此时 keyHolder.getKey() 会因为「存在多个键」直接抛
        // InvalidDataAccessApiUsageException —— 报错内容完全没提 SQL，极难归因。
        // 我们在 prepareStatement 时已显式声明只要 id 列，这里再按列名取一次，
        // 把「驱动行为差异」挡在这一处。
        Map<String, Object> keys = keyHolder.getKeys();
        if (keys != null && keys.get("id") instanceof Number id) {
            return id.longValue();
        }
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入成功但没有返回自增主键");
        }
        return key.longValue();
    }
}
