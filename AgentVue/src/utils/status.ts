import type { NodeStatus, NodeType, TaskStatus, TraceSpanView } from '@/api/types'

/**
 * 状态/节点类型的展示辅助。
 *
 * 后端给的是英文枚举（SUCCEEDED / FAILED ...）。ElementPlus 的 tag 组件需要 type 字段，
 * 但只有内置的 success / warning / danger / info 四个值，缺一个就退化成灰色 —— 所以
 * 这里做一个稳定的映射表，并把「为什么是这个颜色」写在键里。
 */

export const TASK_STATUS_TAG: Record<TaskStatus, 'success' | 'warning' | 'danger' | 'info' | 'primary'> = {
  SUCCEEDED: 'success',
  RUNNING: 'primary',
  PENDING: 'info',
  WAITING_HUMAN: 'warning',
  FAILED: 'danger',
  // 取消用 info 而不是 danger：它不是错误，是人的决定。
  // 染成和失败一样的红，会把「试过了没成」和「人不让它跑」混为一谈。
  CANCELLED: 'info',
}

export const TASK_STATUS_LABEL: Record<TaskStatus, string> = {
  SUCCEEDED: '已完成',
  RUNNING: '运行中',
  PENDING: '等待中',
  WAITING_HUMAN: '待人工',
  FAILED: '失败',
  CANCELLED: '已取消',
}

export const NODE_STATUS_TAG: Record<NodeStatus, 'success' | 'warning' | 'danger' | 'info' | 'primary'> = {
  SUCCEEDED: 'success',
  RUNNING: 'primary',
  PENDING: 'info',
  FAILED: 'danger',
  SKIPPED: 'warning',
}

export const NODE_STATUS_LABEL: Record<NodeStatus, string> = {
  SUCCEEDED: '成功',
  RUNNING: '运行',
  PENDING: '等待',
  FAILED: '失败',
  SKIPPED: '跳过',
}

export const NODE_TYPE_LABEL: Record<NodeType, string> = {
  ANALYZE: '分析',
  PLAN: '规划',
  REWRITE: '重写',
  VERIFY: '验证',
  GATE: '人工门',
}

/**
 * 节点类型的图标 —— 用 emoji 而不是 icon 库，可以让 bundle 不依赖字体文件，
 * 同时在 diff 视图等文本紧贴的窄区域也能识别出来。
 */
export const NODE_TYPE_EMOJI: Record<NodeType, string> = {
  ANALYZE: '🔍',
  PLAN: '🧭',
  REWRITE: '🛠️',
  VERIFY: '✅',
  GATE: '🚦',
}

/**
 * 把后端的毫秒数字渲染成人能读的形式：「1m 23s」、「2.4 s」、「750 ms」。
 *
 * 故意不做「几天前 / 几小时前」式相对时间 —— 这个项目里数字都是「这次跑了多久」，
 * 用相对时间反而产生「duration ago」这种让人困惑的语义。
 */
export function formatDuration(ms: number | null | undefined): string {
  if (ms == null) return '-'
  if (ms < 0) return '-'
  if (ms < 1_000) return `${ms} ms`
  if (ms < 60_000) return `${(ms / 1_000).toFixed(1)} s`
  // 先把总毫秒一次性折算成整秒再拆分，避免「分取整 + 秒四舍五入」各自为政：
  // 59.9 秒会被四舍五入成 60，于是出现「36m 60s」这种不存在的写法（实测见到过）。
  const totalSeconds = Math.round(ms / 1_000)
  // 超过一小时改用「2h 33m」：「153m 48s」算得没错，但没有谁是这样读时间的。
  // 这个分支是被「取消后重跑」那条链路逼出来的 —— 端到端耗时会把两段空档全吃进去，
  // 动辄两三小时，而它恰恰是用户最需要一眼看懂的那个数。
  if (totalSeconds >= 3_600) {
    return `${Math.floor(totalSeconds / 3_600)}h ${Math.floor((totalSeconds % 3_600) / 60)}m`
  }
  return `${Math.floor(totalSeconds / 60)}m ${totalSeconds % 60}s`
}

/**
 * 是否值得把「累计运行时长」和「端到端耗时」两个数都摆出来。
 *
 * 两者本来是不同的问题：前者是「机器一共干了多久」（各次运行墙钟之和），
 * 后者是「你一共等了多久」（入队到本次运行结束，含排队、含被取消那次、含
 * 「取消到重跑之间人去吃饭」的整段空档）。取消重跑过的任务上它们能差出数量级 ——
 * 实测任务 #10 是 78 秒 vs 2 小时 33 分。
 *
 * 差不到 1 秒就不拆开说：那时它们本来就是同一件事，并排摆出来只会让人以为看错了。
 * 累计值为 null 也不拆 —— null 是「没有链路数据」（埋点接上之前的老任务），
 * 此时**不知道**它跑了多久，拿 0 去和端到端比会得出「机器一秒没跑」这种假结论。
 */
export function shouldSplitDuration(
  runMs: number | null | undefined,
  wallMs: number | null | undefined,
): boolean {
  if (runMs == null) return false
  return (wallMs ?? 0) - runMs >= 1_000
}

/**
 * 耗时文案：实际运行 vs 端到端。
 *
 * 放在这里而不是各组件里各写一份：指标面板、任务列表、顶部状态行说的是同一件事，
 * 三处各写一遍必然会漂移（本项目在「同一语义两个来源」上已经付过代价）。
 */
export function formatDurationPair(
  runMs: number | null | undefined,
  wallMs: number | null | undefined,
): string {
  if (!shouldSplitDuration(runMs, wallMs)) return formatDuration(wallMs)
  return `${formatDuration(runMs)}（端到端 ${formatDuration(wallMs)}）`
}

/**
 * 最慢的一「跳」= 除根 span 之外最慢的那条。
 *
 * 根 span 的耗时**就是**整组耗时，拿它来比永远是「占 100%」——
 * 那句提示看起来在回答「时间花在哪」，其实什么都没说。
 * 只跑了根一个 span 的运行（建单那次 HTTP 请求、被取消那次）没有第二个选手，才退回根。
 *
 * 放在这里而不是各组件里各写一份：链路面板的 headline 和页面顶部的提示语
 * 说的是同一件事，两处各写一份迟早会不一致（本项目在 `TaskMetrics` 上踩过一次）。
 */
export function slowestHop(spans: TraceSpanView[]): TraceSpanView | null {
  const inner = spans.filter((span) => span.depth > 0)
  const pool = inner.length > 0 ? inner : spans
  return [...pool].sort((a, b) => b.durationMs - a.durationMs)[0] ?? null
}

/**
 * 把后端的 `attempt` 渲染成「第 N 轮」。
 *
 * **后端是 0 基下标**：首次执行 `attempt=0`，回退一次变 1、再回退变 2。
 * 界面上直接显示 `attempt 0` 会让人以为是「失败了 0 次」或者「还没开始」，
 * 而它其实就是「第一轮」—— 所以统一在这里 +1 并换成人话，三处调用点共用同一个函数，
 * 避免节点时间线说「第 2 轮」而事件流说「#1」这种自相矛盾。
 */
export function formatRound(attempt: number | null | undefined): string {
  if (attempt == null || attempt < 0) return '第 ? 轮'
  return `第 ${attempt + 1} 轮`
}

/** 把 -1 表示的「未采集」覆盖率变成显眼的「—」，避免与 0% 混淆。 */
export function formatCoverage(coverage: number | null | undefined): string {
  if (coverage == null || coverage < 0) return '—'
  return `${(coverage * 100).toFixed(1)}%`
}

/** 货币。这里只显示「¥ 0.0139」这种级别，4 位小数够用。 */
export function formatCost(value: number | null | undefined): string {
  // 返回值【自带】¥ 前缀 —— 调用方不要再拼一个。曾经 TaskList 里写成
  // `¥ {{ formatCost(...) }}`，页面上就出现了「¥ ¥ 0.0151」。
  if (value == null || value === 0) return '¥ 0'
  return `¥ ${value.toFixed(4)}`
}

/**
 * 任务是否已经到了「不会再自己变」的状态。
 *
 * 三个终态放一处，是因为它被用在两个必须一致的地方：要不要继续兜底轮询、
 * 要不要允许取消/重跑。各写一份的结果是「列表说已结束、详情还在轮询」这类不一致。
 */
export function isTerminalStatus(status: string | null | undefined): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED' || status === 'CANCELLED'
}

/** 只有非终态才能取消 —— 与后端 `/cancel` 的 404/409 判据保持一致。 */
export function canCancelStatus(status: string | null | undefined): boolean {
  return status === 'PENDING' || status === 'RUNNING' || status === 'WAITING_HUMAN'
}

/** 只有失败/取消才能重跑 —— 与后端 `/retry` 的 409 判据保持一致。 */
export function canRetryStatus(status: string | null | undefined): boolean {
  return status === 'FAILED' || status === 'CANCELLED'
}

/**
 * span 状态的展示色。
 *
 * `UNSET` 是「既没成功也没失败」的第三种结果（典型是 GATE 挂起）——
 * 把它染成灰而不是红/绿，是因为硬塞进任何一档都会让「出错率」这个指标失去意义。
 */
export const SPAN_STATUS_LABEL: Record<string, string> = {
  OK: '成功',
  ERROR: '错误',
  UNSET: '未表态',
}

export const SPAN_STATUS_COLOR: Record<string, string> = {
  OK: 'var(--color-success)',
  ERROR: 'var(--color-danger)',
  UNSET: 'var(--color-info)',
}

/**
 * 按 span 名的前缀决定时间轴色条的颜色 —— 一眼能分出「模型调用 / 沙箱执行 / 节点本身」。
 *
 * 前缀就是后端命名约定（`task` / `node:` / `llm:` / `sandbox:` / `api:`），
 * 在这里翻译成颜色，而不是让每个组件各自写一遍 startsWith。
 */
export function spanCategoryColor(name: string | null | undefined): string {
  if (!name) return 'var(--color-info)'
  if (name.startsWith('llm:')) return 'var(--el-color-primary)'
  if (name.startsWith('sandbox:')) return 'var(--color-warning)'
  if (name.startsWith('node:')) return 'var(--color-success)'
  if (name.startsWith('api:')) return '#909399'
  return '#b1b3b8'
}

/** span 名的中文类别（用于图例与 tooltip）。 */
export function spanCategoryLabel(name: string | null | undefined): string {
  if (!name) return '其它'
  if (name.startsWith('llm:')) return '模型调用'
  if (name.startsWith('sandbox:')) return '沙箱执行'
  if (name.startsWith('node:')) return 'DAG 节点'
  if (name.startsWith('api:')) return 'HTTP 接口'
  if (name === 'task') return '任务'
  return '其它'
}
