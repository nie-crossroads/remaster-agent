package com.remasteragent.core.store;

import com.remasteragent.common.domain.DagNode;
import com.remasteragent.common.domain.GateStatus;
import com.remasteragent.common.domain.HumanGate;
import com.remasteragent.common.domain.LlmCallRecord;
import com.remasteragent.common.domain.MigrationTask;
import com.remasteragent.common.domain.NodeStatus;
import com.remasteragent.common.domain.NodeType;
import com.remasteragent.common.domain.PatchRecord;
import com.remasteragent.common.domain.TaskStatus;
import com.remasteragent.common.domain.TraceSpan;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /** 创建任务，返回自增 id。name 为任务名（可选，仅展示用，可为 null）。 */
    long createTask(String projectRoot, String entryFile, int targetJdk, String name);

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
    // 人工门禁（阶段 3 —— 通用 GATE 节点）
    // ------------------------------------------------------------------

    /**
     * 为某个 GATE 节点登记一道待审批的门禁，返回自增 id。
     *
     * <p>挂起动作与「插入一行 PENDING」是同一件事的两面：把行落下来，
     * 「这道门存在过、正在等人」这件事才有了持久化的依据，进程被杀也不会丢。
     *
     * @param comment 挂起时附带的说明（展示给审批人看「要审什么」），可为空
     */
    long insertGate(long nodeId, String comment);

    /**
     * 取该任务当前<b>唯一</b>一道等待中的门禁（{@code PENDING}）。
     *
     * <p>调度器每一轮都问一次它，作为「该不该挂起」的判据。取最新一行而非第一行：
     * 同一任务可能先后经历多道门，只有最新的那道才是此刻挡路的。
     *
     * <p>返回 {@code Optional.empty()} 表示没有门在等 —— 任务可以继续跑。
     */
    Optional<HumanGate> findOpenGate(long taskId);

    /** 按 id 取一道门禁（审批接口需要先确认它存在且确实在等待）。 */
    Optional<HumanGate> findGate(long gateId);

    /** 该任务的全部门禁记录，按创建时间升序 —— 供详情页展示「这道门当时怎么过的」。 */
    List<HumanGate> findGates(long taskId);

    /**
     * 落定一次审批决定：写入状态、审批人、意见与决定时间。
     *
     * <p>只允许把 {@code PENDING} 改成 {@code APPROVED}/{@code REJECTED}；
     * 重复决定在 SQL 层用 {@code WHERE status = 'PENDING'} 挡掉，返回受影响行数供调用方判断。
     *
     * @return 真正被更新的行数（1 = 落定成功，0 = 这道门已经被处理过）
     */
    int decideGate(long gateId, GateStatus status, String reviewer, String comment);

    /**
     * 超过给定时刻仍未被处理的等待中门禁 —— 门禁超时回收的输入。
     *
     * <p>只返回「此刻确实挡在路上」的门（{@code PENDING}），不返回历史上已经批过/驳过的：
     * 后者的 {@code decided_at} 可能很旧，但它们早就不是阻塞点了，对它们做任何动作都是错的。
     *
     * @param threshold 判定阈值；{@code created_at < threshold} 即视为超期
     */
    List<OpenGateRef> findOverdueGates(Instant threshold);

    /**
     * 一道等待中的门禁的定位信息。
     *
     * <p>它比 {@link HumanGate} 多带了 {@code taskId} 与 {@code nodeKey}：{@code human_gate}
     * 表本身只有 {@code node_id}，而超时回收要写任务的失败原因、要在日志里说清「拦的是哪个文件」。
     * 让这两个 join 在 SQL 里一次做掉，避免调用方拿着 node_id 再问一次「这属于哪个任务」。
     */
    record OpenGateRef(long gateId, long nodeId, long taskId, String nodeKey, Instant createdAt) {
    }

    // ------------------------------------------------------------------
    // 任务控制（阶段 3 收尾：取消 / 重跑）
    // ------------------------------------------------------------------

    /**
     * 请求取消任务 —— 只置标志位，<b>不直接改状态</b>。
     *
     * <p>为什么不一步到位置成 {@code CANCELLED}：真正在跑的节点（尤其是沙箱里的
     * {@code mvn test}）无法安全中断，能被停下的位置只有节点边界。若此刻就把状态写成
     * 「已取消」，而 Worker 还要过几分钟才停，这个状态就是假的 —— 排查时唯一能信的线索
     * 一旦会说谎，这套状态机就废了。标志位表达的是「请求已发出」，状态由 Worker 在真正停下时落定。
     */
    void requestCancel(long taskId);

    /** 是否已请求取消。调度器在每一轮循环开头问它一次。 */
    boolean isCancelRequested(long taskId);

    /** 清掉取消标志 —— 任务重跑时用，否则刚入队就会被自己立刻停掉。 */
    void clearCancelRequest(long taskId);

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

    /**
     * 把节点从 RUNNING 退回 PENDING —— GATE 节点挂起时用。
     *
     * <p>它挂起时并<b>没有</b>执行完，所以不能置成任何一个终态；但也不该留在 RUNNING，
     * 否则 {@code resetStaleRunningNodes}（Worker 重启时的全局清残骸）会把它当成
     * 「上次被杀的残留」再重置一遍。退回 PENDING 是唯一诚实的表达：
     * 「它还没跑完，只是现在动不了」。真正阻止它被重复执行的是调度器的门禁判据，
     * 不是节点状态本身 —— 见 {@code DagScheduler.pauseForGateIfNeeded}。
     */
    void markNodePending(long nodeId);

    void markNodeSucceeded(long nodeId, String resultJson);

    void markNodeFailed(long nodeId, String error, String resultJson);

    void markNodeSkipped(long nodeId, String error);

    int countNodes(long taskId, NodeStatus status);

    /**
     * VERIFY 的<b>执行轮次</b>（最大 attempt + 1），&gt;1 说明发生过回退重写。
     *
     * <p>注意是「轮次」不是「节点数」：PLAN 可以为一次任务规划多个文件，
     * 每个文件各有一串 {@code verify:<file>} 节点（attempt 从 0 起）。
     * 早先这里统计的是 VERIFY 节点总数，于是「4 个文件各跑一轮」也会得到 4，
     * 被 {@code TaskMetrics.retried()} 读成「回退过 3 次」——
     * 一次干净的多文件迁移会被报告成反复重试。轮次必须取 attempt 的最大值。
     */
    int countVerifyRounds(long taskId);

    /**
     * 把<b>该任务</b>遗留的 RUNNING 节点重置为 PENDING —— 上次 Worker 被杀时的残留。
     *
     * <p>在 {@code DagScheduler.runTask} 的开头按任务调用，这是「断点续跑」能成立的入口。
     *
     * <p><b>为什么必须按任务</b>：不重置的话这些节点永远等不到调度（{@code findRunnable}
     * 只看 PENDING），任务会静默卡住 —— 但一个全局的重置（扫整张表）会在多 Worker 部署时
     * 把<b>别的 Worker 正在跑的节点</b>也重置掉，导致同一个节点被两个进程同时执行。
     * 按任务重置天然避免了这件事：重置范围恰好等于「本进程接下来要跑的那份 DAG」。
     *
     * @return 被重置的节点数
     */
    int resetStaleRunningNodes(long taskId);

    /**
     * 把该任务里所有 FAILED / SKIPPED 的节点退回 PENDING，供人工重跑。
     *
     * <p>为什么连 SKIPPED 一起重置：回退链上「上游 REWRITE 失败 → 本轮 GATE/VERIFY 被跳过」
     * 是成组发生的。只重置失败的 REWRITE，那些被跳过的节点会永远停在 SKIPPED ——
     * 而它们恰好是 <b>最后一轮</b>的 VERIFY（判定任务成败的那一个），任务会立刻再次判失败。
     * 两者必须一起回到起跑线，整条链才真的能重跑一遍。
     *
     * <p>已 SUCCEEDED 的节点<b>不动</b>：重跑的意义是「再试一次没成功的部分」，
     * 让成功的节点重跑会白烧一次模型调用。
     *
     * @return 被重置的节点数
     */
    int resetFailedNodes(long taskId);

    /** 最近一个成功的节点，用于读取上游产出（如 ANALYZE 的分析结果）。 */
    Optional<DagNode> findLatestSucceeded(long taskId, String nodeKey);

    // ------------------------------------------------------------------
    // 全链路 Trace（阶段 3 收尾）
    // ------------------------------------------------------------------

    /**
     * 该任务的全部 span，按开始时间升序。
     *
     * <p>只读不写：span 由 {@code JdbcSpanExporter} 在 OTel 侧批量落库，
     * 它走的是自己的连接与批次，不经过这个接口 —— 埋点是观测，不是业务状态，
     * 让业务存储为它让出一个写方法没有好处。
     */
    List<TraceSpan> findTraceSpans(long taskId);

    /**
     * 一次取回多个任务的 span，按 task_id 分组。**列表页专用**。
     *
     * <p>存在的唯一理由是不让「任务列表」退化成 N+1：列表要显示每个任务的耗时拆解，
     * 而耗时拆解要读 span —— 逐个任务调 {@link #findTraceSpans(long)} 会让一次列表请求
     * 变成「1 + N」条 SQL，N 随列表上限涨到 200。
     *
     * <p>返回的 Map **不含没有 span 的任务**（而不是给空列表）：
     * 调用方要能区分「这个任务没跑过」与「跑过但一条 span 都没有」，
     * 用 {@code getOrDefault(id, List.of())} 按需降级即可。
     */
    Map<Long, List<TraceSpan>> findTraceSpansByTasks(Collection<Long> taskIds);

    /**
     * 该任务的 trace id（用于在前端/日志里把一次执行和它的调用树对上）。
     *
     * <p>取任意一个 span 的值即可 —— 同一任务的全部 span 共用一个 trace id，
     * 这是 OTel 的语义保证，不必去挑「根」那个。返回空表示这次任务还没落任何 span
     * （老任务，或埋点被关掉）。
     */
    Optional<String> findTraceId(long taskId);

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
