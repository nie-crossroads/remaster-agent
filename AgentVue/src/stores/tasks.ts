import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { createTask, describeError, getTask, listTasks } from '@/api/client'
import { openTaskEvents } from '@/api/sse'
import type { CreateTaskRequest, ProgressEvent, TaskDetail, TaskView } from '@/api/types'

/**
 * 任务工作台的核心状态。
 *
 * ## 关键设计
 * - **当前任务**用 ref<TaskDetail | null> 而非 ref<TaskView>：
 *   列表只用来勾选「现在看哪个」，细节视图需要节点/补丁/成本——粒度大不一样。
 * - **SSE 订阅生命周期**完全集中在 store：openTaskEvents 返回的 close 函数由 store 持有，
 *   切任务前先 close 旧的。组件卸载时也应该 close（onUnmounted 在 App.vue 里挂）。
 *
 * ## 为什么不直接用全局对象
 * Pinia store 是单例，本身就接近全局；另起一个 context API 反而又制造一层空状态。
 */

/** EventSource 的当前连接状态。 */
export type SseState = 'idle' | 'connecting' | 'open' | 'closed' | 'error'

export const useTasksStore = defineStore('tasks', () => {
  // -------- state --------

  const list = ref<TaskView[]>([])
  const listLoading = ref(false)
  const listError = ref<string | null>(null)

  const currentDetail = ref<TaskDetail | null>(null)
  const currentLoading = ref(false)
  const currentError = ref<string | null>(null)

  /** 最近一次 SSE 接收到的增量事件（保留最近 50 条，便于界面显示「刚才发生了什么」）。 */
  const recentEvents = ref<ProgressEvent[]>([])

  const sseState = ref<SseState>('idle')

  /** 当前是否正在提交新任务（防止双击表单）。 */
  const submitting = ref(false)
  const submitError = ref<string | null>(null)
  /** 新任务的 id，建任务成功后直接切到它的详情。 */
  const lastCreatedId = ref<number | null>(null)

  /** 用于关闭当前 SSE 连接的 cleanup。 */
  let closeCurrentEvents: (() => void) | null = null

  // -------- getters --------

  const hasCurrent = computed(() => currentDetail.value !== null)
  const currentStatus = computed(() => currentDetail.value?.task.status ?? null)

  /** 仅按状态过滤一次的列表视图（搜索留给 UI 自己再加）。 */
  const sortedList = computed(() => {
    return [...list.value].sort((a, b) => b.id - a.id)
  })

  // -------- mutations --------

  function pushRecent(event: ProgressEvent): void {
    recentEvents.value.unshift(event)
    if (recentEvents.value.length > 50) {
      recentEvents.value.length = 50
    }
  }

  /**
   * 把任务的最新状态/指标回写到左侧列表。
   *
   * store 同时存在「列表(list)」与「当前详情(currentDetail)」两个数据源：列表来自
   * GET /api/tasks，详情来自 getTask + SSE。SSE 的增量事件原本只更新 currentDetail，
   * 若不回写 list，任务跑完后右上角徽标已 SUCCEEDED，左侧列表却还停在 RUNNING/PENDING ——
   * 这就是「列表和右上角对不上」的根因。这里在快照与每次状态/指标增量时同步 list 对应项。
   */
  function patchListTask(task: TaskView): void {
    const idx = list.value.findIndex((t) => t.id === task.id)
    if (idx < 0) return
    const cur = list.value[idx]
    list.value[idx] = {
      ...cur,
      status: task.status,
      failReason: task.failReason,
      metrics: task.metrics ?? cur.metrics,
    }
  }

  // -------- actions --------

  async function refreshList(): Promise<void> {
    listLoading.value = true
    listError.value = null
    try {
      list.value = await listTasks()
    } catch (e) {
      listError.value = describeError(e)
    } finally {
      listLoading.value = false
    }
  }

  /**
   * 切换当前查看的任务。会先关掉旧的 SSE 连接，再拉一次详情（兜底：万一刚切到就被刷新
   * 列表冲掉，详情视图也能单独可用），最后才打开新的 SSE。
   */
  async function selectTask(id: number): Promise<void> {
    if (closeCurrentEvents) {
      closeCurrentEvents()
      closeCurrentEvents = null
    }
    sseState.value = 'connecting'
    currentLoading.value = true
    currentError.value = null
    currentDetail.value = null
    recentEvents.value = []
    try {
      currentDetail.value = await getTask(id)
    } catch (e) {
      currentError.value = describeError(e)
      sseState.value = 'closed'
      return
    } finally {
      currentLoading.value = false
    }
    closeCurrentEvents = openTaskEvents(id, {
      onSnapshot(detail) {
        // 后续的快照（重连时也会再发一次）覆盖当前状态
        currentDetail.value = detail
        // 同步左侧列表，避免列表与右上角详情对不上
        patchListTask(detail.task)
      },
      onProgress(event) {
        pushRecent(event)
        // 把增量事件「摊平」进 currentDetail 的视图里：
        // - 节点状态变化 → 同步对应节点的 status
        // - 任务状态/指标变化 → 同步顶层 status 与 metrics
        applyEventToDetail(event)
      },
      onStateChange(state) {
        sseState.value = state
      },
    })
  }

  /**
   * 把增量事件投影到 currentDetail 上 —— 不依赖后端再回放全部状态，
   * 让 UI 在事件发生那一刻就立刻显得在动。
   */
  function applyEventToDetail(event: ProgressEvent): void {
    const detail = currentDetail.value
    if (!detail || detail.task.id !== event.taskId) return

    if (event.type === 'node_status') {
      const node = detail.nodes.find((n) => n.id === event.nodeId)
      if (node && event.status) {
        node.status = event.status as typeof node.status
        node.startedAt ??= event.at
        if (event.status === 'SUCCEEDED' || event.status === 'FAILED' || event.status === 'SKIPPED') {
          node.finishedAt = event.at
        }
      }
    }

    if (event.type === 'task_status' && event.status) {
      detail.task.status = event.status as typeof detail.task.status
      patchListTask(detail.task)
    }

    if (event.type === 'task_metrics' && event.message) {
      try {
        const metrics = JSON.parse(event.message)
        detail.task.metrics = metrics
        patchListTask(detail.task)
      } catch {
        /* 单条坏载荷不应该让整个 UI 卡死 */
      }
    }
  }

  async function submit(req: CreateTaskRequest): Promise<TaskView | null> {
    submitting.value = true
    submitError.value = null
    try {
      const created = await createTask(req)
      lastCreatedId.value = created.id
      // 列表拉一次，再切到新建任务的详情
      await refreshList()
      await selectTask(created.id)
      return created
    } catch (e) {
      submitError.value = describeError(e)
      return null
    } finally {
      submitting.value = false
    }
  }

  function teardown(): void {
    if (closeCurrentEvents) {
      closeCurrentEvents()
      closeCurrentEvents = null
    }
  }

  return {
    // state
    list,
    listLoading,
    listError,
    currentDetail,
    currentLoading,
    currentError,
    recentEvents,
    sseState,
    submitting,
    submitError,
    lastCreatedId,
    // getters
    sortedList,
    hasCurrent,
    currentStatus,
    // actions
    refreshList,
    selectTask,
    submit,
    teardown,
  }
})
