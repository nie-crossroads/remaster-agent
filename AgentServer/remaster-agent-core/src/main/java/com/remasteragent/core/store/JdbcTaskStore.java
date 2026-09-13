package com.remasteragent.core.store;

import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.LlmCallRecord;
import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.NodeStatus;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.domain.PatchRecord;
import com.remasteragent.common.domain.TaskStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
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

    public JdbcTaskStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // 任务
    // ------------------------------------------------------------------

    @Override
    public long createTask(String projectRoot, String entryFile, int targetJdk) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO migration_task (project_root, entry_file, target_jdk, status)
                    VALUES (?, ?, ?, ?)
                    """, ID_COLUMN);
            ps.setString(1, projectRoot);
            ps.setString(2, entryFile);
            ps.setInt(3, targetJdk);
            ps.setString(4, TaskStatus.PENDING.name());
            return ps;
        }, keyHolder);
        return requireKey(keyHolder);
    }

    @Override
    public Optional<MigrationTask> findTask(long taskId) {
        List<MigrationTask> rows = jdbc.query("""
                SELECT id, project_root, entry_file, target_jdk, status, metrics, fail_reason,
                       created_at, updated_at
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
        return jdbc.query("""
                SELECT id, project_root, entry_file, target_jdk, status, metrics, fail_reason,
                       created_at, updated_at
                  FROM migration_task
                 ORDER BY created_at DESC, id DESC
                 LIMIT ?
                """, TASK_MAPPER, limit);
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
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM dag_node WHERE task_id = ? AND node_type = 'VERIFY'
                """, Integer.class, taskId);
        return count == null ? 0 : count;
    }

    @Override
    public int resetStaleRunningNodes() {
        return jdbc.update("""
                UPDATE dag_node SET status = 'PENDING', started_at = NULL WHERE status = 'RUNNING'
                """);
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
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")));

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
