package com.remasteragent.core.store;

import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.LlmCallRecord;
import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.NodeStatus;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.domain.PatchRecord;
import com.remasteragent.common.domain.TaskStatus;

import java.util.List;
import java.util.Optional;

/**
 * 任务状态存储 —— 编排层唯一的持久化出口。
 *
 * <p>抽成接口有两个具体收益，都不是理论上的洁癖：
 * <ol>
 *   <li><b>调度逻辑可以脱离数据库单测</b>：用内存实现跑「VERIFY 失败 → attempt+1」这条路径，
 *       单测是确定性的、毫秒级的，而不是需要连生产库、跑真模型、还依赖 Maven 的集成测试。</li>
 *   <li><b>换存储不影响编排</b>：从 PostgreSQL 换成别的，改的是这个接口的实现。</li>
 * </ol>
 *
 * <p>所有「按 (task_id, node_key, attempt) 定位」的方法都刻意带全三元组 —— 因为回退重写
 * 会让同一个 node_key 存在多行，只用 (task_id, node_key) 定位是不明确的。
 */
public interface TaskStore {

    // ------------------------------------------------------------------
    // 任务
    // ------------------------------------------------------------------

    /** 创建任务，返回自增 id。 */
    long createTask(String projectRoot, String entryFile, int targetJdk);

    Optional<MigrationTask> findTask(long taskId);

    void updateTaskStatus(long taskId, TaskStatus status, String failReason);

    /** 写入量化指标（JSON 字符串）。 */
    void saveTaskMetrics(long taskId, String metricsJson);

    /** 需要执行的任务 id，按创建时间升序。 */
    List<Long> findPendingTaskIds(int limit);

    /**
     * 最近创建的任务，按创建时间倒序 —— 供列表页使用。
     *
     * <p>刻意<b>不带分页参数</b>：阶段 1 是单人使用的内部工具，任务量级是几十条。
     * 引入完整分页会在 API、前端、SQL 三处各加一层概念，而这个阶段没有任何一处用得上。
     * 真到需要分页时，改的是这一个方法加查询参数，不是重写。
     */
    List<MigrationTask> findRecentTasks(int limit);

    // ------------------------------------------------------------------
    // 规划评审（阶段 2）
    // ------------------------------------------------------------------

    /** 人工批准该任务的迁移计划 —— 评审界面点「批准」后调用，配合把任务重新入队。 */
    void approvePlan(long taskId);

    /** 该任务的迁移计划是否已被人工批准。未开启评审时无意义（调度器会先看配置开关）。 */
    boolean isPlanApproved(long taskId);

    // ------------------------------------------------------------------
    // 节点（checkpoint）
    // ------------------------------------------------------------------

    /** 插入节点，返回自增 id；dependsOn 里是上游节点 id。 */
    long insertNode(long taskId, String nodeKey, NodeType nodeType, List<Long> dependsOn, int attempt);

    Optional<DagNode> findNode(long taskId, String nodeKey, int attempt);

    List<DagNode> findNodes(long taskId);

    /**
     * 就绪节点：状态为 PENDING，且全部上游节点都已 SUCCEEDED。
     * 调度循环每一轮都问一次这个方法，这是拓扑排序的实际落地方式 ——
     * 比起预先算好拓扑序，按需查询天然支持运行中动态追加节点（回退重写就是这样加进来的）。
     */
    List<DagNode> findRunnable(long taskId);

    void markNodeRunning(long nodeId);

    void markNodeSucceeded(long nodeId, String resultJson);

    void markNodeFailed(long nodeId, String error, String resultJson);

    void markNodeSkipped(long nodeId, String error);

    int countNodes(long taskId, NodeStatus status);

    /** VERIFY 节点的总执行轮次，&gt;1 说明发生过回退重写。 */
    int countVerifyRounds(long taskId);

    /**
     * 把遗留的 RUNNING 节点重置为 PENDING —— Worker 进程上次被杀时的残留。
     * 在 Worker 启动时调用一次，这是「断点续跑」能成立的入口。
     *
     * @return 被重置的节点数
     */
    int resetStaleRunningNodes();

    /** 最近一个成功的节点，用于读取上游产出（如 ANALYZE 的分析结果）。 */
    Optional<DagNode> findLatestSucceeded(long taskId, String nodeKey);

    // ------------------------------------------------------------------
    // 补丁
    // ------------------------------------------------------------------

    long insertPatch(long nodeId, String filePath, String diff, String originalHash);

    List<PatchRecord> findPatches(long taskId);

    // ------------------------------------------------------------------
    // 成本
    // ------------------------------------------------------------------

    void recordLlmCall(LlmCallRecord record);

    List<LlmCallRecord> findLlmCalls(long taskId);

    /** 单个任务的成本汇总。 */
    CostSummary summarizeCost(long taskId);

    /**
     * 成本汇总。
     *
     * @param calls           调用次数
     * @param promptTokens    输入 token 合计
     * @param completionTokens 输出 token 合计
     * @param totalCost       成本合计
     */
    record CostSummary(int calls, long promptTokens, long completionTokens, double totalCost) {

        public static CostSummary empty() {
            return new CostSummary(0, 0L, 0L, 0d);
        }
    }
}
