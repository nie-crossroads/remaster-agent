import axios, { AxiosError } from 'axios'
import type { CreateTaskRequest, TaskDetail, TaskView } from './types'

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
