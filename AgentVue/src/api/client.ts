import axios, { AxiosError } from 'axios'
import type { CreateTaskRequest, TaskDetail, TaskTrace, TaskView, WriteBackReport } from './types'

/**
 * REST 客户端。
 *
 * `baseURL` 用相对路径 `/api`：开发期由 Vite 代理到后端，生产期由同域的网关/反向代理转发。
 * 好处是前端代码里不含任何环境地址，「换环境要改代码」这件事从根上消失。
 *
 * 超时 15s：建任务、查详情都是毫秒级操作。真正的长任务不走这里 ——
 * 那由 Redis 队列 + SSE 承担，HTTP 请求永远不该等着任务跑完。
 */
const http = axios.create({
  baseURL: '/api',
  timeout: 15_000,
})

/** 任务列表（按后端返回顺序，通常是最近更新的在前或按 id 倒序）。 */
export async function listTasks(): Promise<TaskView[]> {
  const { data } = await http.get<TaskView[]>('/tasks')
  return data
}

/** 任务详情：状态 + 节点 + 补丁 + 成本。SSE 的 snapshot 事件就是这个结构。 */
export async function getTask(id: number): Promise<TaskDetail> {
  const { data } = await http.get<TaskDetail>(`/tasks/${id}`)
  return data
}

/** 建任务并投递到队列，后端返回 202 + 任务概要。 */
export async function createTask(request: CreateTaskRequest): Promise<TaskView> {
  const { data } = await http.post<TaskView>('/tasks', request)
  return data
}

/** SSE 事件流地址。交给 EventSource 使用，不走 axios。 */
export function eventsUrl(id: number): string {
  return `/api/tasks/${id}/events`
}

/**
 * 批准迁移计划 —— 后端的动作里含一次「重新入队」，任务随即从 checkpoint 续跑。
 *
 * 后端只接受 `WAITING_HUMAN` 状态的任务，否则返回 409。前端因此<b>不能在乐观 UI 里
 * 自己先把状态改成 RUNNING</b>：真发生冲突时页面会显示一个后端并不认可的状态。
 * 一律以服务端返回的 TaskView 为准。
 */
export async function approvePlan(id: number): Promise<TaskView> {
  const { data } = await http.post<TaskView>(`/tasks/${id}/plan/approve`)
  return data
}

/** 驳回迁移计划：任务直接判失败，不执行任何改写。理由可选，会写进失败原因。 */
export async function rejectPlan(id: number, reason?: string): Promise<TaskView> {
  const { data } = await http.post<TaskView>(`/tasks/${id}/plan/reject`, { reason })
  return data
}

/**
 * 批准当前等待中的人工门禁（GATE 节点）—— 后端动作里含一次「重新入队」，
 * 任务随即从 checkpoint 续跑。
 *
 * 与 approvePlan 同理：后端只接受「WAITING_HUMAN 且存在 PENDING 门禁」的任务，否则 409。
 * 前端因此不能在乐观 UI 里先把状态改成 RUNNING：真冲突时会显示后端并不认可的状态。
 * 一律以服务端返回的 TaskView 为准。
 */
export async function approveGate(
  id: number,
  reviewer?: string,
  comment?: string,
): Promise<TaskView> {
  const { data } = await http.post<TaskView>(`/tasks/${id}/gate/approve`, { reviewer, comment })
  return data
}

/** 驳回当前等待中的人工门禁：对应节点判失败，任务直接判失败，不再往下执行。 */
export async function rejectGate(
  id: number,
  comment?: string,
  reviewer?: string,
): Promise<TaskView> {
  const { data } = await http.post<TaskView>(`/tasks/${id}/gate/reject`, { reviewer, comment })
  return data
}

/**
 * 取消任务 —— **协作式**，后端不硬杀。
 *
 * 返回的 TaskView 有两种可能，前端必须如实展示、不能乐观改状态：
 * - 原先 PENDING / WAITING_HUMAN → 立刻变成 `CANCELLED`；
 * - 原先 RUNNING → 状态**仍是 RUNNING**（`cancelRequested=true`），
 *   要等 Worker 在当前节点结束、回到节点边界时才真正停下。
 *
 * 第二种情况下前端显示「正在取消…」并继续等 SSE，而不是假装已经停了 ——
 * 一个会说谎的状态机在排查时毫无价值。
 */
export async function cancelTask(id: number, reason?: string): Promise<TaskView> {
  const { data } = await http.post<TaskView>(`/tasks/${id}/cancel`, { reason })
  return data
}

/**
 * 重跑失败 / 被取消的任务。
 *
 * 后端会把 FAILED 与 SKIPPED 的节点一起退回 PENDING（已成功的节点不动，
 * 所以已经烧掉的 token 不会被重复花一遍），清掉取消标志，然后重新入队。
 * 返回 202 + 任务概要（状态已回到 PENDING）。
 */
export async function retryTask(id: number): Promise<TaskView> {
  const { data } = await http.post<TaskView>(`/tasks/${id}/retry`)
  return data
}

/**
 * 任务的全链路链路数据。
 *
 * 单独一个请求而不是塞进详情：一次任务可能产生几百条 span，
 * 而详情是每次刷新都在拉的东西 —— 让「打开链路面板」这个动作自己去取，
 * 详情页的体积和延迟不受影响。
 */
export async function getTaskTrace(id: number): Promise<TaskTrace> {
  const { data } = await http.get<TaskTrace>(`/tasks/${id}/trace`)
  return data
}

/**
 * 变更回写的**预检** —— 只检查、不写盘。
 *
 * 单独一个请求，是为了让「应用到源工程」这个危险动作前面永远隔着一张清单：
 * 人得先看见「要写 3 个文件到 /path/to/repo，工作区干净」，再决定点不点确认。
 *
 * 它返回 200 即使有拦路项 —— 拦路项是结果**本身的内容**（「工作区不干净，请先 git stash」），
 * 前端直接渲染 `blocked[].message` 即可。别按 HTTP 状态码猜结果，看 `ready`。
 */
export async function preflightWriteBack(id: number): Promise<WriteBackReport> {
  const { data } = await http.get<WriteBackReport>(`/tasks/${id}/write-back`)
  return data
}

/**
 * 变更回写**执行**：把沙箱里已经验证过的产出落回源工程。
 *
 * 服务端顺序是「重新预检 → 先备份 → 再覆盖 → 落审计」，
 * 预检不过就一个字节都不写（不做「尽力而为写一部分」）。
 *
 * **它只写本地工作区文件，绝不 commit、绝不 push** ——
 * 提交是 git 的语义、是人的动作，Agent 不该持有远端凭据。
 * 建议的提交信息随报告返回（`suggestedCommitMessage`），供人直接采用。
 *
 * 因此前端确认弹窗必须说清「会改动你的文件」，并按危险动作配色（`type="danger"`）。
 */
export async function applyWriteBack(id: number): Promise<WriteBackReport> {
  const { data } = await http.post<WriteBackReport>(`/tasks/${id}/write-back`)
  return data
}

/**
 * 把任意异常翻成一句能给人看的话。
 *
 * 后端用 `{"error": "..."}` 或 ProblemDetail 返回业务错误；拿不到就退回状态码。
 * 直接把 axios 的异常对象丢进界面会显示「[object Object]」，对排查毫无帮助。
 */
export function describeError(error: unknown): string {
  if (error instanceof AxiosError) {
    const payload = error.response?.data as Record<string, unknown> | string | undefined
    if (typeof payload === 'string' && payload.trim() !== '') {
      return payload
    }
    if (payload && typeof payload === 'object') {
      const message = payload.detail ?? payload.message ?? payload.error
      if (typeof message === 'string' && message.trim() !== '') {
        return message
      }
    }
    if (error.code === 'ECONNABORTED') {
      return '请求超时：后端 API 进程没有响应，确认它已启动（默认 8080）。'
    }
    if (!error.response) {
      return '连不上后端 API 进程：确认它已启动（默认 8080），或代理配置是否正确。'
    }
    return `请求失败（HTTP ${error.response.status}）`
  }
  return error instanceof Error ? error.message : String(error)
}
