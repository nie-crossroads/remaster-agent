import { eventsUrl } from './client'
import type { ProgressEvent, TaskDetail } from './types'

/**
 * SSE 订阅。
 *
 * ## 为什么必须用 addEventListener，而不是 `es.onmessage`
 *
 * 后端发的是**具名事件**：`event: snapshot` / `event: progress`。
 * 浏览器的 `EventSource.onmessage` **只接收没有事件名的消息** —— 具名事件必须用
 * `addEventListener('<name>', ...)` 才能收到。这是最容易踩的坑：订阅成功、连接正常、
 * 心跳也在，但 `onmessage` 一次都不触发，现象是「后端说发了但前端什么都没显示」。
 *
 * ## 为什么快照与增量要分开处理
 *
 * SSE 是「连接建立后才开始收」。而这个任务可能已经在跑了 —— 此时快照（数据库当前状态）
 * 是**唯一**能让界面立刻正确的东西，增量只负责后续变化。丢增量只损失实时性，
 * 丢快照则会让人以为任务卡在最初状态。
 *
 * ## 为什么还有第三个事件（history）
 *
 * 快照还原的是「状态」，还原不了「过程」：「刚才发生了什么」不属于任何一张表。
 * 所以后端为每个任务在内存里留了一份最近事件的缓冲，连接建立时紧跟着快照补发一帧
 * `history`。没有它，刷新一次页面「最近事件流」就永远是空的 —— 而用户恰恰是靠那段
 * 滚动日志判断「它在动，还是在重试」的。
 *
 * 三帧的到达顺序由服务端保证：snapshot → history → 之后才是 progress。
 */

export interface TaskEventHandler {
  /** 连接建立时后端推的完整状态快照（TaskDetail 结构）。 */
  onSnapshot: (detail: TaskDetail) => void
  /**
   * 连接建立时后端补发的最近事件（新 → 旧，最多 50 条）。
   *
   * 可选：它只是锦上添花，收不到照样能用（快照 + 增量已经保证了正确性）。
   */
  onHistory?: (events: ProgressEvent[]) => void
  /** 增量进度事件。 */
  onProgress: (event: ProgressEvent) => void
  /** 连接建立/断开/出错，用于在界面上体现「实时链路是否健康」。 */
  onStateChange?: (state: 'connecting' | 'open' | 'closed' | 'error') => void
}

/**
 * 打开某个任务的实时事件流，返回一个关闭函数。
 *
 * 调用方**必须**在切换任务或组件卸载时调用返回的函数 —— EventSource 会自动重连，
 * 不显式 close 就会残留一条永远在重连的连接。
 */
export function openTaskEvents(taskId: number, handler: TaskEventHandler): () => void {
  handler.onStateChange?.('connecting')
  const source = new EventSource(eventsUrl(taskId))
  let closed = false

  const parseOrNull = <T>(raw: string): T | null => {
    try {
      return JSON.parse(raw) as T
    } catch (e) {
      // 单条坏载荷不能让整条流死掉：丢掉这一条，连接继续用
      console.warn('[sse] 载荷不是合法 JSON，已跳过', raw, e)
      return null
    }
  }

  source.addEventListener('snapshot', (event) => {
    const detail = parseOrNull<TaskDetail>((event as MessageEvent).data)
    if (detail) {
      handler.onSnapshot(detail)
    }
  })

  source.addEventListener('history', (event) => {
    const events = parseOrNull<ProgressEvent[]>((event as MessageEvent).data)
    if (events) {
      handler.onHistory?.(events)
    }
  })

  source.addEventListener('progress', (event) => {
    const progress = parseOrNull<ProgressEvent>((event as MessageEvent).data)
    if (progress) {
      handler.onProgress(progress)
    }
  })

  source.onopen = () => handler.onStateChange?.('open')

  source.onerror = () => {
    // EventSource 的 error 事件在自动重连期间也会触发，不能据此判定流已死。
    // 只有 readyState=CLOSED 才是真的不会自己恢复了（例如服务端返回 4xx/5xx）。
    if (source.readyState === EventSource.CLOSED) {
      handler.onStateChange?.('closed')
    } else {
      handler.onStateChange?.('error')
    }
  }

  return () => {
    if (closed) return
    closed = true
    source.close()
    handler.onStateChange?.('closed')
  }
}
