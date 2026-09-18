/**
 * 后端接口的前端镜像类型。
 *
 * 这些类型是**手抄**自后端的 DTO（不共享代码），原因：前后端是独立工程、独立语言，
 * 强行共享类型会引入跨语言的代码生成链，收益不抵复杂度。代价是后端改字段前端不会自动报错 ——
 * 所以字段名必须与后端 record 完全一致，改后端 DTO 时务必同步这里。
 *
 * 对应后端：
 * - TaskView  → com.remasteragent.web.api.dto.TaskView
 * - TaskDetail→ TaskQueryService.taskDetail()（{task, nodes, patches, cost, plan, gate, writeBack}）
 * - PlanView  → TaskDetailView.PlanView（阶段 2 规划评审）
 * - GateView  → TaskDetailView.GateView（阶段 3 通用 GATE 门禁）
 * - WriteBackView / WriteBackReport → TaskDetailView.WriteBackView / WriteBackReportView（变更回写）
 * - TaskTrace → TaskTraceView（阶段 3 全链路 Trace）
 * - ProgressEvent → com.remasteragent.core.progress.ProgressEvent
 */

/**
 * 任务状态。
 *
 * `CANCELLED` 是**终态**，与 `FAILED` 并列但语义不同：失败是「试过了没成」，
 * 取消是「人不让它继续」。报表统计通过率时必须能把这两者分开 ——
 * 把主动取消算成失败，会让「这个 Agent 到底行不行」这个结论变得不可信。
 */
export type TaskStatus = 'PENDING' | 'RUNNING' | 'WAITING_HUMAN' | 'SUCCEEDED' | 'FAILED'
  | 'CANCELLED'

/**
 * DAG 节点类型。
 *
 * `POM_REWRITE` 是「整仓 JDK 升级」的编译级别改写（改 `maven.compiler.release` 等），
 * 它必须在所有 `REWRITE` **之前**执行 —— 否则每个文件的 verify 都会因为
 * 「编译级别还是 8」而失败。它不调用模型，是纯文本定点替换，所以不产生补丁里的 diff 之外的东西。
 */
export type NodeType = 'ANALYZE' | 'PLAN' | 'POM_REWRITE' | 'REWRITE' | 'VERIFY' | 'GATE'

export type NodeStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED'

/**
 * 量化指标汇总。任务未结束时后端返回 null。
 *
 * ## 为什么三个字段是可选的（这不是「后端有时不返回」的敷衍，是踩出来的）
 *
 * 指标有两个来源，后端给它们的**形状**曾经不一致：
 * - REST 快照（`GET /api/tasks/{id}`）走显式映射，字段齐全；
 * - SSE 增量（`type: task_metrics`）曾经直接把后端的存储形状序列化发出来，而
 *   `compilePassRate` / `testPassRate` / `retried` 在后端是**派生方法**（不是 record 字段），
 *   于是整条增量事件里根本没有这三个键。
 *
 * 前端收到增量后是**整体覆盖** `task.metrics` 的，于是文件刚跑完那一刻通过率变成 undefined，
 * 进度条归零、状态被判失败显示 ✗ —— 而任务明明成功。最迷惑的是 `coverage` 恰好是后端 record
 * 字段，所以「行覆盖率」一直正常显示，看起来像进度条组件坏了。
 *
 * 后端已修（事件改用与快照同形状的 `TaskMetricsSnapshot`），但前端这里**仍然不信任**
 * 这两个比率字段：始终优先用 `compilePassed / filesTotal` 现算。
 * 计数是永远都在的，比率是能被算出来的 —— 能从源数据得到的结论，就不要依赖第二份拷贝。
 */
export interface Metrics {
  filesTotal: number
  compilePassed: number
  /** 编译通过率 0~1。**可选**：SSE 增量历史上不带该字段，用计数现算即可。 */
  compilePassRate?: number
  testsTotal: number
  testsPassed: number
  /** 单测通过率 0~1。**可选**，同上。 */
  testPassRate?: number
  /** 行覆盖率 0~1；**-1 表示未采集**（不是 0，前端必须区分） */
  coverage: number
  llmCalls: number
  promptTokens: number
  completionTokens: number
  totalCost: number
  verifyAttempts: number
  /** 是否发生过回退重写。**可选**，可由 `verifyAttempts > 1` 现算。 */
  retried?: boolean
  durationMs: number
}

export interface TaskView {
  id: number
  projectRoot: string
  /**
   * 入口文件（相对项目根的路径）。
   *
   * 为 `null` 表示**整仓升级**模式：只把全仓 `pom.xml` 的编译级别抬到 `targetJdk`，
   * 不做任何代码改写（拓扑只有 POM_REWRITE → VERIFY）。
   *
   * 它不是「没填」—— 那是一种合法的任务形态。展示时不能留空，否则看不出与数据缺失的区别。
   */
  entryFile: string | null
  /**
   * 任务名（建单页手动输入，可选）。
   *
   * 为 `null` 表示建单时没填（评测任务以前建的、或用户留空）—— 界面要回退成 `#id`，
   * 而不是显示空白，否则列表里会出现几行看不出是哪个任务的条目。
   */
  name: string | null
  targetJdk: number
  status: TaskStatus
  failReason: string | null
  /**
   * 是否已被请求取消。
   *
   * 与 `status` **并列**而不合并：`RUNNING + cancelRequested` 是一个真实存在的组合 ——
   * 那正是「点了取消、当前节点还在跑」的状态。一个节点内部（尤其沙箱里的 `mvn test`）
   * 没法安全中断，所以后端只能置标志位、等节点跑到边界才停。
   *
   * 前端据此把按钮显示成「正在取消…」，否则点了取消页面毫无变化，看起来像没生效。
   */
  cancelRequested: boolean
  createdAt: string
  updatedAt: string
  metrics: Metrics | null
  /**
   * 累计运行时长 = 各次运行的墙钟之和，**不含**排队与「取消到重跑之间人去吃饭」的空档。
   *
   * 它回答「机器一共干了多久」，而 `metrics.durationMs` 回答「你一共等了多久」
   * （入队到本次运行结束）。取消重跑过的任务上两者能差出数量级 —— 实测任务 #10 是
   * `77 秒` vs `2 小时 33 分`。只给一个数时，读者无从知道它答的是哪个问题。
   *
   * **可选**：它由查询接口从 `trace_span` 现算（见后端 `TaskView#runDurationMs`），
   * SSE 的 `task_metrics` 增量载荷里没有它 —— 增量只覆盖 `metrics`，
   * 所以增量事件不该把它一起抹掉（`patchListTask` 里显式保留）。
   *
   * `null` / `undefined` = 这个任务一条 span 都没有（埋点接上之前的老任务、或埋点被关掉），
   * 也就是**不知道**，绝不能显示成「运行了 0 秒」。
   */
  runDurationMs?: number | null
}

/** VERIFY 节点的产出 —— 唯一的事实来源。 */
export interface VerifyResult {
  compiled: boolean
  exitCode: number
  testsTotal: number
  testsPassed: number
  testsFailed: number
  testsSkipped: number
  coverage: number
  failureExcerpt: string
  durationMs: number
  timedOut: boolean
}

export interface DagNode {
  id: number
  nodeKey: string
  nodeType: NodeType
  status: NodeStatus
  attempt: number
  dependsOn: number[]
  error: string | null
  startedAt: string | null
  finishedAt: string | null
  verify: VerifyResult | null
  /**
   * 最近一条进度消息 —— **前端从 `node_status` 增量事件累积的派生字段**，
   * 详情接口不返回它（刷新页面后会短暂为空，随后被新事件补上）。
   *
   * 存在的理由：一次 LLM 调用的 HTTP 重试**不递增节点 `attempt`**，
   * 所以「正在重试 2/3」这句话是页面上唯一能把「在重试」和「卡死」区分开的信息。
   */
  progressMessage?: string
}

/**
 * 补丁。`diff` 由**服务端本地生成**（java-diff-utils），不是模型拼的字符串。
 *
 * `attempt` 是产出它的 REWRITE 节点的轮次（0 起）：回退重写会给同一个文件产生多份补丁，
 * 只按文件名分不清哪份是哪轮。标签要标「第 2 轮」就得靠它。
 */
export interface Patch {
  nodeId: number
  filePath: string
  diff: string
  attempt: number
}

export interface CostSummary {
  calls: number
  promptTokens: number
  completionTokens: number
  totalCost: number
}

/** 计划中的一步：一个待迁移文件 + 为什么改它。 */
export interface PlanStep {
  filePath: string
  rationale: string
}

/**
 * 迁移计划（阶段 2）。
 *
 * `approved` 是「这份计划批过没有」的判断题，前端据此决定要不要显示批准/驳回按钮 ——
 * 与计划本身放在一起，是因为它只对计划有意义。
 */
export interface PlanView {
  summary: string
  steps: PlanStep[]
  approved: boolean
}

/**
 * 一道等待人工处理的门禁（GATE 节点，阶段 3「通用人在回路」）。
 *
 * 只有任务此刻被某道门挡住时 `TaskDetail.gate` 才有值 —— 它一旦出现就确实在等人。
 * 与 `PlanView` 的区别是语义方向：计划是「已经发生过的事」（PLAN 的产出，一直在），
 * 门禁是「正挡在路上、要你去处理的事」（处理完就没了，字段随即变回 null）。
 *
 * 后端：`TaskDetailView.GateView`。
 */
export interface GateView {
  id: number
  /** 对应的 GATE 节点 id，用于在 DAG 图上定位这道门。 */
  nodeId: number
  /** 节点键，形如 `gate:com/foo/Bar.java`。 */
  nodeKey: string | null
  /** 被门禁拦下的文件（后端从 nodeKey 解析），无则 null。 */
  filePath: string | null
  status: string
  /** 挂起说明：「已改写 X，请确认补丁后再继续验证」。 */
  comment: string | null
  createdAt: string
  decidedAt: string | null
}

/**
 * 「变更回写」的预检报告 / 执行结果。
 *
 * 后端：`WriteBackReportView`。预检与执行**共用同一个形状**，前端只渲染一种卡片 ——
 * 「先看清单再点确认」这条交互不需要两套数据结构，也就不会出现
 * 「预检说有 3 个文件、执行后报告说 2 个」这种两边对不上的情况。
 *
 * ## ⚠️ 判定必须从源数据现算，**不要**指望 `ready` / `applied` 两个布尔
 *
 * 后端那个 record 上确实有 `ready()` / `applied()` 两个便捷方法，但它们**不是 record 组件**
 * （名字也没有 `get`/`is` 前缀），Jackson 序列化 record 时只认组件 —— 所以线上载荷里
 * 根本没有这两个键。后端有一条 `WriteBackReportViewShapeTest` 把这一点钉死了。
 *
 * 本项目在 `TaskMetrics` 上已经为此付过一次代价：派生方法序列化时全丢，
 * 前端整体覆盖后指标面板静默清零，任务却完全正常，没有任何报错。所以这里的规矩是：
 * **计数与时间戳永远都在，布尔结论是能被算出来的** —— 用 `utils/writeback.ts` 里的
 * `isWriteBackReady` / `isWriteBackApplied`，不要自己写 `report.ready`。
 */
export interface WriteBackReport {
  taskId: number
  /** 源工程根目录（写回目标）。 */
  projectRoot: string | null
  /** 沙箱工作目录（内容来源）。 */
  workspace: string | null
  /** 备份目录；回滚就是把它整棵拷回去。未执行时为 null。 */
  backupDir: string | null
  files: WriteBackFileEntry[]
  /** 拦路项 —— **非空即拒绝执行**，一个字节都不写。 */
  blocked: WriteBackBlocked[]
  /** 放行但不放心的提示（如目录不是 git 工作区）。 */
  warnings: string[]
  /** 建议的提交信息。写回止于工作区，提交与否由人决定。 */
  suggestedCommitMessage: string | null
  /** 实际写回时间；null 表示只做了预检。**这是判定「写没写」的唯一依据。** */
  appliedAt: string | null
}

/** 一个待写回 / 已写回的文件。 */
export interface WriteBackFileEntry {
  filePath: string
  bytes: number
  sha256: string
  /** 该文件的「改写前基线」与源工程现状是否一致。 */
  baseOk: boolean
}

/**
 * 回写的拦路项。
 *
 * `code` 是机器可判的代号（`TASK_NOT_SUCCEEDED` / `DIRTY_WORKING_TREE` / `BASE_MISMATCH` …），
 * `message` 是给人看的、**带怎么解决**的说明。前端直接渲染 message，
 * 不要自己按 code 编一句 —— 两处各写一份必然漂移。
 */
export interface WriteBackBlocked {
  code: string
  message: string
}

/**
 * 「这个任务已经回写过源工程」的留痕（详情接口随详情一起返回）。
 *
 * 后端：`TaskDetailView.WriteBackView`。为 null 表示从没回写过。
 *
 * 为什么不只在上次操作成功时弹个提示：回写是本项目里**唯一会改动用户原有文件**的动作，
 * 隔天回到详情页时最需要重新确认的是「写进去了吗 / 写了哪几个文件 / 出事了去哪找原件」，
 * 这三件事都不能依赖当时那一次弹窗。
 */
export interface WriteBackView {
  projectRoot: string | null
  /** 回滚入口：把这里的文件拷回原位。 */
  backupDir: string | null
  fileCount: number
  /** 写回**后**的 sha256，可核对磁盘上现在这份是不是当时写的。 */
  files: WriteBackAppliedFile[]
  appliedAt: string | null
}

/** 一个已被写回的文件。 */
export interface WriteBackAppliedFile {
  filePath: string
  bytes: number
  sha256: string
}

export interface TaskDetail {
  task: TaskView
  nodes: DagNode[]
  patches: Patch[]
  cost: CostSummary
  /** 计划可为 null：未开启 PLAN 的部署（阶段 1 拓扑）本就没有计划，前端必须当成可能缺失。 */
  plan: PlanView | null
  /** 门禁可为 null：只有任务此刻卡在一道等待中的人工门禁上时才有值。 */
  gate: GateView | null
  /**
   * 已回写留痕，可为 null（从没回写过）。
   *
   * 注意它只回答「**曾经**写过什么」，不回答「**现在**能不能写」——
   * 后者要现场检查源工程工作区（git status），挂在 `/write-back` 端点上按需触发，
   * 不随详情页每次刷新都跑一遍。
   */
  writeBack: WriteBackView | null
}

/**
 * SSE 增量事件类型。
 *
 * `heartbeat` 是后端的**链路探活**事件（`taskId=0`，前端不渲染）：API 进程定期把它发进
 * Pub/Sub 再自己收回，用来确认「订阅连接还活着」—— 公网链路会静默回收只读的订阅连接，
 * 那种故障下连接看着是好的，只是永远收不到消息。收到心跳说明链路通，但它不该出现在事件流里。
 */
export type ProgressEventType = 'node_status' | 'task_status' | 'task_metrics' | 'heartbeat'

/**
 * 增量进度事件。
 *
 * 注意 `type=task_metrics` 时 `message` 里装的是**一段 JSON 字符串**（指标快照），
 * 需要二次 JSON.parse —— 这是后端的既有形状，前端如实处理而不是让后端改。
 */
export interface ProgressEvent {
  taskId: number
  nodeId: number | null
  nodeKey: string | null
  type: ProgressEventType
  status: string | null
  attempt: number
  message: string | null
  at: string
}

export interface CreateTaskRequest {
  projectRoot: string
  /**
   * 入口文件（相对项目根的路径）。
   *
   * 省略 / 留空 = **整仓升级**模式：只抬全仓 `pom.xml` 的编译级别，不改任何代码。
   * 后端据此把任务的入口文件存成 `null`，编排层再据此铺一条不含 REWRITE 的拓扑。
   */
  entryFile?: string | null
  targetJdk?: number
  /** 任务名（可选，仅展示用；留空则后端存 null，列表回退显示 #id）。 */
  name?: string
}

/**
 * 一个执行阶段（全链路 Trace 的最小单元）。
 *
 * 后端：`TaskTraceView.SpanView`。层级 `depth`、短名 `shortName` 都是**服务端算好的** ——
 * 父指针是还原层级的唯一依据（并行节点的 span 在时间上会交叠，用时间戳套嵌套必然算错）。
 *
 * `durationMs` 为 0 表示这个 span 没走完（进程被杀那一类），不是「瞬间完成」。
 */
export interface TraceSpanView {
  id: number | null
  traceId: string
  spanId: string
  parentSpanId: string | null
  nodeId: number | null
  /** 完整 span 名，形如 `node:rewrite:com/foo/Bar.java`。 */
  name: string
  /** 压缩后的短名，形如 `node: Bar.java` —— 长键会把时间轴挤爆。 */
  shortName: string
  /** OTel StatusCode：UNSET / OK / ERROR。 */
  status: string | null
  startedAt: string
  durationMs: number
  /** 缩进层级，0 = 根。 */
  depth: number
}

/**
 * 一次任务执行（一条 trace）。
 *
 * 每次「批准规划 / 批准门禁 / 重跑」都会重新入队，那是**一次新的执行**、由一次新的
 * HTTP 请求触发，因此自成一条 trace。
 *
 * `durationMs` 是**这一组内部**的墙钟跨度（不是各 span 耗时之和 —— 后者会重复计算嵌套）。
 * 每组甘特图各自以本组的 `startedAt` 为原点，所以组与组之间不互相压缩：
 * 曾经把多次运行画在同一根轴上，两次执行之间几小时的空档把每次真正的耗时都压成了
 * 0.6% 的细线 —— 看着像「进度条是空的」。
 */
export interface TraceRunView {
  traceId: string
  /** 本组最早 span 的开始时刻。 */
  startedAt: string
  /** 本组最晚 span 的结束时刻；组内有 span 没跑完（进程被杀那一类）时为 null。 */
  endedAt: string | null
  /** 本组墙钟跨度：startedAt → 最晚结束。 */
  durationMs: number
  spanCount: number
  /** 根 span 的短名，用作这组运行的可读标签（如 `task` / `api:POST /api/tasks`）。 */
  rootName: string
  spans: TraceSpanView[]
}

/**
 * 任务的全链路 Trace —— **按运行分组**，不是一条被硬拼起来的假链路。
 *
 * 一个任务会被跑好几次（建单那次 HTTP 请求、被取消的那次、重跑那次…），
 * 它们各有各的 traceId，`runs` 里一组一条。后端按 `startedAt` **升序**返回，
 * 展示时倒序一次即可做到「最新一次在上」。
 */
export interface TaskTrace {
  /** 这个任务一共有几次执行。 */
  traceCount: number
  runs: TraceRunView[]
}
