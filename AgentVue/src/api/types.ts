/**
 * 后端接口的前端镜像类型。
 *
 * 这些类型是**手抄**自后端的 DTO（不共享代码），原因：前后端是独立工程、独立语言，
 * 强行共享类型会引入跨语言的代码生成链，收益不抵复杂度。代价是后端改字段前端不会自动报错 ——
 * 所以字段名必须与后端 record 完全一致，改后端 DTO 时务必同步这里。
 *
 * 对应后端：
 * - TaskView  → com.remasteragent.web.api.dto.TaskView
 * - TaskDetail→ TaskQueryService.taskDetail()（{task, nodes, patches, cost}）
 * - ProgressEvent → com.remasteragent.core.progress.ProgressEvent
 */

export type TaskStatus = 'PENDING' | 'RUNNING' | 'WAITING_HUMAN' | 'SUCCEEDED' | 'FAILED'

export type NodeType = 'ANALYZE' | 'PLAN' | 'REWRITE' | 'VERIFY' | 'GATE'

export type NodeStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED'

/** 量化指标汇总。任务未结束时后端返回 null。 */
export interface Metrics {
  filesTotal: number
  compilePassed: number
  compilePassRate: number
  testsTotal: number
  testsPassed: number
  testPassRate: number
  /** 行覆盖率 0~1；**-1 表示未采集**（不是 0，前端必须区分） */
  coverage: number
  llmCalls: number
  promptTokens: number
  completionTokens: number
  totalCost: number
  verifyAttempts: number
  retried: boolean
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
}

/** 补丁。`diff` 由**服务端本地生成**（java-diff-utils），不是模型拼的字符串。 */
export interface Patch {
  nodeId: number
  filePath: string
  diff: string
}

export interface CostSummary {
  calls: number
  promptTokens: number
  completionTokens: number
  totalCost: number
}

export interface TaskDetail {
  task: TaskView
  nodes: DagNode[]
  patches: Patch[]
  cost: CostSummary
}

/** SSE 增量事件类型。 */
export type ProgressEventType = 'node_status' | 'task_status' | 'task_metrics'

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
