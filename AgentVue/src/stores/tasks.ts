import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { approveGate as approveGateApi, approvePlan as approvePlanApi, createTask, describeError, getTask, listTasks, rejectGate as rejectGateApi, rejectPlan as rejectPlanApi } from '@/api/client'
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

/**
 * 实时事件静默多久之后开始兜底轮询。
 *
 * 取得比一次大模型调用（十几秒到一分多钟）还长是没意义的 —— 那期间本来就不会有事件；
 * 取得太短又会让兜底变成主力。18 秒是个折中：正常的「节点开始/结束」间隔都在它之内。
 */
const FALLBACK_IDLE_MS = 18_000

/** 兜底轮询间隔。 */
const FALLBACK_INTERVAL_MS = 12_000

/**
 * 「重新拉一次权威详情」的合并延迟。
 *
 * 节点的终态往往是连着来的（重写成功 → 验证开始 → 验证失败 → 重写开始），
 * 每次都单独打一次 GET 是浪费。250ms 足够把一阵抖动合并成一次请求，
 * 又短到用户感觉不到延迟。
 */
const DETAIL_REFRESH_MERGE_MS = 250

/** 事件流保留的条数上限 —— 与后端补发历史的条数一致。 */
const RECENT_EVENT_LIMIT = 50

/** 已经不会再自己变的终态 —— 到这一步就不必再轮询了。 */
const TERMINAL_STATUSES: ReadonlySet<string> = new Set(['SUCCEEDED', 'FAILED'])

/** 节点终态 —— 到达它意味着「这一轮的产物已经可读」（补丁在节点内部就写库了）。 */
const TERMINAL_NODE_STATUSES: ReadonlySet<string> = new Set(['SUCCEEDED', 'FAILED', 'SKIPPED'])

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

  /**
   * 最后一次收到实时事件（快照或增量）的时刻。
   *
   * 它是「实时链路到底还在不在供数」的唯一判据。前端无法从这里判断后端那条 Redis 订阅
   * 是不是已经死了（那种故障下 EventSource 连接完好、状态显示「已连接」），
   * 所以这里不做任何「链路健康」的推断，只老老实实记录「多久没收到过东西」，
   * 并把「页面为什么会自己刷新」如实告诉用户 —— 不撒谎比看起来聪明重要。
   */
  const lastEventAt = ref(0)

  /** 当前是否在靠兜底轮询维持最新（实时事件已经静默超过阈值）。 */
  const pollingFallback = ref(false)

  /** 防止并发拉取当前详情（节点补齐时可能短时间触发多次）。 */
  const detailRefreshing = ref(false)

  /** 当前是否正在提交新任务（防止双击表单）。 */
  const submitting = ref(false)
  const submitError = ref<string | null>(null)
  /** 新任务的 id，建任务成功后直接切到它的详情。 */
  const lastCreatedId = ref<number | null>(null)

  /** 规划评审请求进行中（防止连点批准导致重复入队）。 */
  const reviewing = ref(false)
  const reviewError = ref<string | null>(null)

  /** 用于关闭当前 SSE 连接的 cleanup。 */
  let closeCurrentEvents: (() => void) | null = null

  /** 兜底轮询定时器。 */
  let fallbackTimer: ReturnType<typeof setInterval> | null = null

  /** 「重新拉权威详情」的合并定时器。 */
  let detailRefreshTimer: ReturnType<typeof setTimeout> | null = null

  /** 拉取进行中又来了新请求：记下来，等这一次落地后再补一次。 */
  let detailRefreshQueued = false

  // -------- getters --------

  const hasCurrent = computed(() => currentDetail.value !== null)
  const currentStatus = computed(() => currentDetail.value?.task.status ?? null)

  /** 仅按状态过滤一次的列表视图（搜索留给 UI 自己再加）。 */
  const sortedList = computed(() => {
    return [...list.value].sort((a, b) => b.id - a.id)
  })

  /**
   * 当前任务是否正卡在规划评审这一关。
   *
   * 判据是<b>任务状态 + 没有等待中的门禁行</b>：计划 JSON 万一读不出来（老任务、
   * 格式漂移），评审这件事依然需要用户处理 —— 所以不放宽到「计划存在」这个条件；
   * 但 `WAITING_HUMAN` 现在被规划评审与 GATE 门禁共用，必须用 `gate` 字段把后者排除掉，
   * 否则 GATE 挂起时这面板会挂出「批准并继续执行」按钮，而它打的是 /plan/approve ——
   * 后端会回 409。计划面板是「给你看的」，能点的按钮才是「要你做的」。
   */
  const awaitingPlanReview = computed(
    () => currentStatus.value === 'WAITING_HUMAN' && currentDetail.value?.gate == null
  )

  /**
   * 当前任务是否正卡在一道人工门禁（GATE 节点）上。
   *
   * 判据是「后端返回了等待中的门禁」（`gate` 字段非空），而<b>不是</b>任务状态 ——
   * 因为 `WAITING_HUMAN` 同时被「规划评审」和「GATE 门禁」复用，只有 `gate` 字段
   * 能区分此刻挡路的到底是哪一种。这与 `awaitingPlanReview` 的判据刻意相反：
   * 规划评审没有独立的门禁行，只好看状态；门禁有行，就该看行。
   */
  const awaitingGateReview = computed(() => currentDetail.value?.gate != null)

  // -------- mutations --------

  /**
   * 事件的去重键。
   *
   * <h2>为什么需要它</h2>
   * 后端在连接建立时会把「最近事件」连同快照一起补发，而缓冲是在登记连接**之前**取的。
   * 理论上存在一条事件既落在补发历史里、又作为增量到达的可能（丢一条日志不算故障，
   * 但同一行显示两遍很像 bug）。用「发生时间 + 节点 + 类型 + 状态」做键：
   * `at` 是纳秒精度的 ISO-8601 字符串，同型同态的事件不可能落在同一纳秒上。
   */
  function eventKey(event: ProgressEvent): string {
    return `${event.at}|${event.nodeId ?? ''}|${event.type}|${event.status ?? ''}`
  }

  function pushRecent(event: ProgressEvent): void {
    if (recentEvents.value.some((existing) => eventKey(existing) === eventKey(event))) {
      return
    }
    recentEvents.value.unshift(event)
    if (recentEvents.value.length > RECENT_EVENT_LIMIT) {
      recentEvents.value.length = RECENT_EVENT_LIMIT
    }
  }

  /**
   * 把后端补发的历史事件并进「最近事件流」。
   *
   * <p>是**并**不是**换**：重连时会重发历史，而这段时间本地也收过一些事件，
   * 直接替换会在「后端缓冲恰好为空」（例如 API 刚重启）时把本地已有的记录抹掉 ——
   * 那正好是用户最不该丢失信息的时刻。
   */
  function mergeHistory(history: ProgressEvent[]): void {
    if (history.length === 0) return
    const seen = new Set<string>()
    const merged: ProgressEvent[] = []
    for (const event of [...recentEvents.value, ...history]) {
      const key = eventKey(event)
      if (seen.has(key)) continue
      seen.add(key)
      merged.push(event)
    }
    merged.sort((a, b) => Date.parse(b.at) - Date.parse(a.at))
    recentEvents.value = merged.slice(0, RECENT_EVENT_LIMIT)
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
    stopFallback()
    cancelScheduledDetailRefresh()
    detailRefreshQueued = false
    sseState.value = 'connecting'
    currentLoading.value = true
    currentError.value = null
    currentDetail.value = null
    recentEvents.value = []
    // 这次 HTTP 拉取本身就是一次「刚拿到权威状态」，所以先记一次；
    // 否则刚切过来还没等到快照就会被兜底轮询抢先触发一次多余的请求。
    markEvent()
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
        markEvent()
      },
      onHistory(events) {
        // 刷新页面后补回「刚才发生了什么」：快照能还原状态，还原不了过程
        mergeHistory(events)
        markEvent()
      },
      onProgress(event) {
        // 链路探活心跳：后端的 API 进程定期把它发进 Pub/Sub 再自己收回。
        //
        // 它对我们**只有一件事有意义** —— 证明「订阅链路还活着」，所以要 markEvent()；
        // 但它不是一个业务事件，进了「最近事件流」只会把真正的节点事件刷掉。
        //
        // 正常情况下这里收不到它（后端订阅端已经把它拦下、不派发），
        // 留着这一支是为了「后端版本比前端新/旧」时不至于把探活事件当成业务事件渲染。
        if (event.type === 'heartbeat') {
          markEvent()
          return
        }
        pushRecent(event)
        // 把增量事件「摊平」进 currentDetail 的视图里：
        // - 节点状态变化 → 同步对应节点的 status
        // - 任务状态/指标变化 → 同步顶层 status 与 metrics
        applyEventToDetail(event)
        markEvent()
      },
      onStateChange(state) {
        sseState.value = state
      },
    })
    startFallback()
  }

  /**
   * 重新拉取当前任务的权威详情并替换 currentDetail。
   *
   * 两个触发源：
   * - SSE 增量事件引用了本地还没有的节点（DAG 边跑边落库，快照里可能一个节点都没有）；
   * - 节点或任务到达终态 —— **补丁、成本、验证明细只有详情接口才返回**，
   *   而它们在增量事件里根本没有对应字段。
   */
  async function refreshCurrentDetail(): Promise<void> {
    const cur = currentDetail.value
    if (!cur) return
    if (detailRefreshing.value) {
      // 已经有一次在飞。直接 return 会丢掉这次请求 —— 而「最后一次节点终态」触发的
      // 恰恰是最该拿到补丁的那一次，丢了它就等于补丁永远差一份。记下来，落地后再补。
      detailRefreshQueued = true
      return
    }
    detailRefreshing.value = true
    try {
      const fresh = await getTask(cur.task.id)
      carryOverProgressMessages(fresh, currentDetail.value)
      currentDetail.value = fresh
      patchListTask(fresh.task)
    } catch (e) {
      currentError.value = describeError(e)
    } finally {
      detailRefreshing.value = false
      if (detailRefreshQueued) {
        detailRefreshQueued = false
        void refreshCurrentDetail()
      }
    }
  }

  /**
   * 把本地累积的 `progressMessage` 搬到刚拉回来的权威详情上。
   *
   * <h2>为什么必须搬</h2>
   * `progressMessage` 是**前端派生字段**：后端详情接口不返回它，它的唯一来源是
   * `node_status` 增量事件。而刷新详情是整体替换 `currentDetail` —— 不搬的话，
   * 一次刷新就会把正在运行节点上那句「正在重试 2/3」擦掉，而那正是重试期间
   * 页面上唯一能把「在重试」和「卡死」区分开的信息。
   *
   * <p>只搬「拉回来之后仍在运行」的节点：已经跑完的节点不该再顶着一条重试提示。
   */
  function carryOverProgressMessages(fresh: TaskDetail, local: TaskDetail | null): void {
    if (!local) return
    const carried = new Map<number, string>()
    for (const node of local.nodes) {
      if (node.progressMessage) carried.set(node.id, node.progressMessage)
    }
    if (carried.size === 0) return
    for (const node of fresh.nodes) {
      const message = carried.get(node.id)
      if (message && node.status === 'RUNNING') {
        node.progressMessage = message
      }
    }
  }

  /**
   * 排一次详情重拉，短时间内多次调用只真正打一次。
   *
   * <h2>它修的是什么</h2>
   * 补丁在 REWRITE 节点内部就写库了（`RewriteNode` 先 `insertPatch`，节点随后才转 SUCCEEDED），
   * 所以**节点到达终态**是「补丁已可读」的最早时刻。原先刷新详情只有一个触发条件 ——
   * 「事件引用了本地还不存在的节点」，于是首轮就通过的任务永远等不到刷新：
   * 它的 REWRITE 节点在快照里早就有了，成功时不会触发任何拉取，
   * 页面上的代码补丁只能靠手动刷新才出现。
   *
   * <p>兜底轮询救不了这件事：补丁落库之后事件反而变密，12 秒一跳的静默检查
   * 每次都被「刚刚还有事件」挡回去，等任务到终态轮询就停了。
   */
  function scheduleDetailRefresh(): void {
    if (detailRefreshTimer !== null) return
    detailRefreshTimer = setTimeout(() => {
      detailRefreshTimer = null
      void refreshCurrentDetail()
    }, DETAIL_REFRESH_MERGE_MS)
  }

  function cancelScheduledDetailRefresh(): void {
    if (detailRefreshTimer !== null) {
      clearTimeout(detailRefreshTimer)
      detailRefreshTimer = null
    }
  }

  /** 记一次「实时链路刚刚供过数」。快照与增量都算 —— 两者都是链路活着的证据。 */
  function markEvent(): void {
    lastEventAt.value = Date.now()
    pollingFallback.value = false
  }

  /**
   * 兜底轮询：任务还没到终态、但实时事件已经静默超过阈值时，主动重新拉一次权威详情。
   *
   * <h2>为什么必须有这一层</h2>
   * 后端的进度是「Worker → Redis Pub/Sub → API → SSE」四段接力，任何一段静默失效，
   * 浏览器侧都看不出异常：EventSource 连接好端端的，只是永远不再有消息。
   * 实测踩到过 —— 公网 Redis 的订阅连接被网络设备回收后，「快照能到、心跳能到、增量一条不到」，
   * 页面就停在打开那一刻。后端已经加了链路看门狗会自愈，但前端不能把「页面必须会动」
   * 押在另一侧的健壮性上：这里用一次廉价的 GET 把正确性兜住。
   *
   * <h2>为什么不去判断「链路是不是坏了」</h2>
   * 因为前端没有这个信息：一次大模型调用 60 秒不产生任何事件是完全正常的，
   * 它和「链路死了」在这一侧不可区分。所以这里只做一件不依赖判断的事：
   * 静默久了就自己拉一次，无论原因是什么。代价是偶发一次多余的 GET。
   */
  async function fallbackTick(): Promise<void> {
    const detail = currentDetail.value
    if (!detail) return

    if (TERMINAL_STATUSES.has(detail.task.status)) {
      // 已经不会再变了，没必要继续问后端
      pollingFallback.value = false
      return
    }
    if (Date.now() - lastEventAt.value <= FALLBACK_IDLE_MS) {
      return
    }
    pollingFallback.value = true
    await refreshCurrentDetail()
  }

  function stopFallback(): void {
    if (fallbackTimer !== null) {
      clearInterval(fallbackTimer)
      fallbackTimer = null
    }
  }

  function startFallback(): void {
    stopFallback()
    fallbackTimer = setInterval(() => {
      void fallbackTick()
    }, FALLBACK_INTERVAL_MS)
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
      if (!node) {
        // 节点还没进到本地快照（任务刚提交、DAG 边跑边建节点）：拉一次权威详情补齐，
        // 时间线才能实时出现新节点。否则这些增量事件会被静默丢弃，只能手动刷新才看得到。
        scheduleDetailRefresh()
        return
      }
      const reachedTerminal = event.status != null && TERMINAL_NODE_STATUSES.has(event.status)
      if (event.status) {
        node.status = event.status as typeof node.status
        node.startedAt ??= event.at
        if (reachedTerminal) {
          node.finishedAt = event.at
        }
      }
      // 记下最近一条进度消息。一次调用的 HTTP 重试**不会**新增节点 attempt，
      // 所以「正在重试 2/3」这句话是页面上唯一能区分「在重试」与「卡死」的信息。
      if (event.message) {
        node.progressMessage = event.message
      }
      if (reachedTerminal) {
        // 节点跑完了，这一轮的产物（补丁 / 验证明细）刚刚可读 —— 把它们拉回来。
        // 不能等兜底轮询：补丁落库后事件变密，轮询的静默判定每次都不成立。
        scheduleDetailRefresh()
      }
    }

    if (event.type === 'task_status' && event.status) {
      detail.task.status = event.status as typeof detail.task.status
      patchListTask(detail.task)
      if (TERMINAL_STATUSES.has(event.status)) {
        // 收尾：成本汇总、最终指标、以及最后一份补丁都只在详情接口里。
        // 那之后兜底轮询就停了（终态不再轮询），所以这一次必须主动拉。
        scheduleDetailRefresh()
      }
    }

    if (event.type === 'task_metrics' && event.message) {
      const metrics = parseMetricsPayload(event.message)
      if (metrics) {
        detail.task.metrics = metrics
        patchListTask(detail.task)
      }
    }
  }

  /**
   * 解析 `task_metrics` 事件的载荷，并在**缺关键字段时拒绝整条**。
   *
   * <h2>为什么不直接 JSON.parse 完就赋值</h2>
   * 这份载荷会被整体覆盖到 `task.metrics` 上，而它曾经与服务端快照**形状不一致**
   * （后端把存储用的 record 直接序列化，派生出来的通过率字段全丢），
   * 结果任务刚成功、指标面板却归零显示失败。后端已改为与快照同形状，但这类
   * 「两个来源、一个语义」的地方一旦再漂移，症状依旧是**静默把好数据换成坏数据**。
   *
   * 所以这里显式校验两个原始计数：它们在两种形状里都在，也是面板唯一真正依赖的分母来源。
   * 缺了就丢掉这一条并告警 —— 保住上一份可信的指标，比用坏数据覆盖它强。
   */
  function parseMetricsPayload(message: string): TaskDetail['task']['metrics'] {
    let parsed: unknown
    try {
      parsed = JSON.parse(message)
    } catch (e) {
      console.warn('[metrics] 载荷不是合法 JSON，已忽略这一条', message, e)
      return null
    }
    if (typeof parsed !== 'object' || parsed === null) {
      console.warn('[metrics] 载荷不是对象，已忽略这一条', message)
      return null
    }
    const candidate = parsed as Record<string, unknown>
    if (typeof candidate.filesTotal !== 'number' || typeof candidate.testsTotal !== 'number') {
      console.warn(
        '[metrics] 载荷缺少 filesTotal/testsTotal，疑似与快照形状不一致，已忽略以免覆盖可信指标',
        candidate,
      )
      return null
    }
    return candidate as unknown as TaskDetail['task']['metrics']
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
    stopFallback()
    cancelScheduledDetailRefresh()
  }

  // -------- 规划评审（阶段 2） --------

  /**
   * 批准当前任务的迁移计划。
   *
   * 以服务端返回的 TaskView 为准回写状态，而不是本地先改成 RUNNING ——
   * 服务端对非 WAITING_HUMAN 的任务会返回 409，本地乐观改状态会在冲突时
   * 显示出一个后端并不认可的状态。批准成功后紧接一次详情刷新，
   * 是为了立刻拿到新铺出来的 REWRITE/VERIFY 节点（DAG 视图要跟着变）。
   */
  async function approveCurrentPlan(): Promise<boolean> {
    const id = currentDetail.value?.task.id
    if (id == null || reviewing.value) return false
    reviewing.value = true
    reviewError.value = null
    try {
      const updated = await approvePlanApi(id)
      if (currentDetail.value) currentDetail.value.task = updated
      patchListTask(updated)
      await refreshCurrentDetail()
      // 刚刚拿到过权威状态，重置静默计时，避免紧接着多打一次兜底请求
      markEvent()
      return true
    } catch (e) {
      reviewError.value = describeError(e)
      // 冲突（409）往往意味着别的页面/标签已经处理过它了，拉一次权威状态纠偏
      await refreshCurrentDetail()
      return false
    } finally {
      reviewing.value = false
    }
  }

  /** 驳回当前任务的迁移计划：任务判失败，不执行任何改写。 */
  async function rejectCurrentPlan(reason?: string): Promise<boolean> {
    const id = currentDetail.value?.task.id
    if (id == null || reviewing.value) return false
    reviewing.value = true
    reviewError.value = null
    try {
      const updated = await rejectPlanApi(id, reason)
      if (currentDetail.value) currentDetail.value.task = updated
      patchListTask(updated)
      await refreshCurrentDetail()
      markEvent()
      return true
    } catch (e) {
      reviewError.value = describeError(e)
      await refreshCurrentDetail()
      return false
    } finally {
      reviewing.value = false
    }
  }

  // -------- 通用人工门禁（阶段 3 —— GATE 节点） --------

  /**
   * 批准当前等待中的人工门禁。
   *
   * 与规划评审完全同构（同一套 `reviewing` / `reviewError` 状态、同样以服务端返回为准、
   * 成功后刷新详情）—— 二者只是「同一件事（人在回路）的两种触发源」，
   * 没必要各写一套状态管理。区别只在调用的接口不同。
   */
  async function approveCurrentGate(reviewer?: string, comment?: string): Promise<boolean> {
    const id = currentDetail.value?.task.id
    if (id == null || reviewing.value) return false
    reviewing.value = true
    reviewError.value = null
    try {
      const updated = await approveGateApi(id, reviewer, comment)
      if (currentDetail.value) currentDetail.value.task = updated
      patchListTask(updated)
      await refreshCurrentDetail()
      markEvent()
      return true
    } catch (e) {
      reviewError.value = describeError(e)
      await refreshCurrentDetail()
      return false
    } finally {
      reviewing.value = false
    }
  }

  /** 驳回当前等待中的人工门禁：对应节点判失败，任务直接判失败。 */
  async function rejectCurrentGate(comment?: string, reviewer?: string): Promise<boolean> {
    const id = currentDetail.value?.task.id
    if (id == null || reviewing.value) return false
    reviewing.value = true
    reviewError.value = null
    try {
      const updated = await rejectGateApi(id, comment, reviewer)
      if (currentDetail.value) currentDetail.value.task = updated
      patchListTask(updated)
      await refreshCurrentDetail()
      markEvent()
      return true
    } catch (e) {
      reviewError.value = describeError(e)
      await refreshCurrentDetail()
      return false
    } finally {
      reviewing.value = false
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
    lastEventAt,
    pollingFallback,
    submitting,
    submitError,
    lastCreatedId,
    reviewing,
    reviewError,
    // getters
    sortedList,
    hasCurrent,
    currentStatus,
    awaitingPlanReview,
    awaitingGateReview,
    // actions
    refreshList,
    selectTask,
    submit,
    approveCurrentPlan,
    rejectCurrentPlan,
    approveCurrentGate,
    rejectCurrentGate,
    teardown,
  }
})
