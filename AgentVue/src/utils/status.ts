import type { NodeStatus, NodeType, TaskStatus } from '@/api/types'

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
}

export const TASK_STATUS_LABEL: Record<TaskStatus, string> = {
  SUCCEEDED: '已完成',
  RUNNING: '运行中',
  PENDING: '等待中',
  WAITING_HUMAN: '待人工',
  FAILED: '失败',
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
  return `${Math.floor(totalSeconds / 60)}m ${totalSeconds % 60}s`
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
  if (value == null) return '¥ 0'
  if (value === 0) return '¥ 0'
  if (value < 0.01) return `¥ ${value.toFixed(4)}`
  return `¥ ${value.toFixed(4)}`
}
