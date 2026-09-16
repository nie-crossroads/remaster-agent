/**
 * 后端接口的前端镜像类型。
 *
 * 这些类型是**手抄**自后端的 DTO（不共享代码），原因：前后端是独立工程、独立语言，
 * 强行共享类型会引入跨语言的代码生成链，收益不抵复杂度。代价是后端改字段前端不会自动报错 ——
 * 所以字段名必须与后端 record 完全一致，改后端 DTO 时务必同步这里。
 *
 * 对应后端：
 * - TaskView  → com.remasteragent.web.api.dto.TaskView
 * - TaskDetail→ TaskQueryService.taskDetail()（{task, nodes, patches, cost, plan}）
 * - PlanView  → TaskDetailView.PlanView（阶段 2 规划评审）
 * - ProgressEvent → com.remasteragent.core.progress.ProgressEvent
 */

export type TaskStatus = 'PENDING' | 'RUNNING' | 'WAITING_HUMAN' | 'SUCCEEDED' | 'FAILED'

export type NodeType = 'ANALYZE' | 'PLAN' | 'REWRITE' | 'VERIFY' | 'GATE'

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
  entryFile: string
  targetJdk: number
  status: TaskStatus
  failReason: string | null
  createdAt: string
  updatedAt: string
  metrics: Metrics | null
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

export interface TaskDetail {
  task: TaskView
  nodes: DagNode[]
  patches: Patch[]
  cost: CostSummary
  /** 计划可为 null：未开启 PLAN 的部署（阶段 1 拓扑）本就没有计划，前端必须当成可能缺失。 */
  plan: PlanView | null
  /** 门禁可为 null：只有任务此刻卡在一道等待中的人工门禁上时才有值。 */
  gate: GateView | null
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
  entryFile: string
  targetJdk?: number
}
