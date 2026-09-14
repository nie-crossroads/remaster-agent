<script setup lang="ts">
/**
 * 一页工作台主入口。
 *
 * 布局：
 *   [ 顶栏 ：标题 + SSE 状态 ]
 *   [ 左栏 ：任务列表 | 任务表单 ]
 *   [ 右栏 ：任务详情——指标 + 节点时间线 + 补丁 / 事件流 ]
 *
 * 任务状态推送完毕后，store 自动暂停 SSE 连接；切任务由 store 重新订阅。
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

import DagGraph from '@/components/DagGraph.vue'
import DiffViewer from '@/components/DiffViewer.vue'
import MetricsPanel from '@/components/MetricsPanel.vue'
import NodeTimeline from '@/components/NodeTimeline.vue'
import PlanReview from '@/components/PlanReview.vue'
import TaskForm from '@/components/TaskForm.vue'
import TaskList from '@/components/TaskList.vue'
import { useTasksStore } from '@/stores/tasks'
import {
  TASK_STATUS_LABEL,
  TASK_STATUS_TAG,
  formatDuration,
  formatRound,
} from '@/utils/status'

const tasks = useTasksStore()

/**
 * 实时链路指示灯。
 *
 * **「已连接」不等于「还有数据」**：后端的进度要经 Worker → Redis Pub/Sub → API → SSE 四段接力，
 * 中间那段静默失效时 EventSource 连接依旧完好。所以这里优先报「还有没有实时事件」，
 * 而不是只报 TCP 连接状态 —— 一个永远显示绿色的灯比没有灯更误导人。
 */
const sseLabel = computed(() => {
  if (tasks.pollingFallback) {
    return '暂无实时事件，已启用轮询兜底'
  }
  switch (tasks.sseState) {
    case 'open':
      return '实时链路正常'
    case 'connecting':
      return '正在连接…'
    case 'error':
      return '连接出错，正在重试'
    case 'closed':
      return '连接已断开'
    default:
      return '未订阅'
  }
})

const sseHint = computed(() =>
  tasks.pollingFallback
    ? '已经有一段时间没收到实时进度事件（大模型调用期间属于正常），页面改为定时拉取最新状态。任务本身不受影响，事件恢复后会自动切回实时推送。'
    : '实时进度由 SSE 推送；若长时间没有事件，页面会自动改为轮询兜底。',
)

const detail = computed(() => tasks.currentDetail)
const lastTaskHeaderLabel = computed(() => {
  const t = detail.value?.task
  if (!t) return null
  return `任务 #${t.id} · ${t.projectRoot.split(/[\\/]/).slice(-1)[0]}/${t.entryFile.split('/').pop()}`
})

/**
 * 实时运行计时：任务 RUNNING 时每秒按 createdAt 起算「已运行 mm:ss」；
 * 非 RUNNING（终态）则停表，改用后端给的 metrics.durationMs（权威、含排队+执行全量）。
 */
const now = ref(Date.now())
let ticker: ReturnType<typeof setInterval> | null = null

function stopTicker(): void {
  if (ticker !== null) {
    clearInterval(ticker)
    ticker = null
  }
}

function syncTicker(): void {
  if (detail.value?.task.status === 'RUNNING') {
    if (ticker === null) {
      now.value = Date.now()
      ticker = setInterval(() => {
        now.value = Date.now()
      }, 1000)
    }
  } else {
    stopTicker()
  }
}

const topDuration = computed(() => {
  const t = detail.value?.task
  if (!t) return ''
  if (t.status === 'RUNNING') {
    const start = new Date(t.createdAt).getTime()
    if (Number.isNaN(start)) return ''
    return formatDuration(Math.max(0, now.value - start))
  }
  return formatDuration(t.metrics?.durationMs)
})

watch(() => detail.value?.task?.status, syncTicker)

onMounted(async () => {
  await tasks.refreshList()
  syncTicker()
})

onUnmounted(() => {
  // 组件卸载（如后续嵌入其它页面）时确保 SSE 关闭
  tasks.teardown()
  stopTicker()
})
</script>

<template>
  <div>
    <header class="app-header">
      <h1>RemasterAgent · 遗留代码现代化工作台</h1>
      <div class="header-meta">
        <span :title="sseHint">
          <span class="sse-state-dot" :class="tasks.pollingFallback ? 'polling' : tasks.sseState" />
          {{ sseLabel }}
        </span>
        <span v-if="detail?.task" class="current">
          <el-tag :type="TASK_STATUS_TAG[detail.task.status]" size="small">
            {{ TASK_STATUS_LABEL[detail.task.status] }}
          </el-tag>
          <span class="duration">
            {{ topDuration }}
          </span>
        </span>
      </div>
    </header>

    <div class="app-shell">
      <aside>
        <TaskList />
      </aside>

      <main>
        <TaskForm />

        <template v-if="detail">
          <div class="card">
            <div class="detail-header">
              <h2>{{ lastTaskHeaderLabel }}</h2>
              <div v-if="detail.task.failReason" class="fail-reason">
                失败原因：{{ detail.task.failReason }}
              </div>
            </div>

            <MetricsPanel :metrics="detail.task.metrics" />
          </div>

          <!--
            规划评审放在指标之后、执行图之前：
            「要你做的事」优先于「看进度」。等待评审时这个卡片的边框会变成警示色，
            视线自然先落到它上面。
          -->
          <PlanReview
            :plan="detail.plan"
            :status="detail.task.status"
            :busy="tasks.reviewing"
            :error="tasks.reviewError"
            @approve="tasks.approveCurrentPlan()"
            @reject="(reason) => tasks.rejectCurrentPlan(reason)"
          />

          <div class="card">
            <DagGraph :nodes="detail.nodes" />
          </div>

          <div class="card">
            <NodeTimeline :nodes="detail.nodes" />
          </div>

          <div class="card">
            <DiffViewer :patches="detail.patches" />
          </div>

          <div class="card">
            <div class="section-title">最近事件流</div>
            <div v-if="tasks.recentEvents.length === 0" class="empty-state">
              <p>暂无增量事件</p>
              <p class="hint">启动后收到的实时进度会出现在这里</p>
            </div>
            <div v-else class="event-log">
              <div v-for="ev in tasks.recentEvents" :key="ev.at + (ev.nodeId ?? '') + ev.type" class="event-row">
                <span class="event-time">{{ new Date(ev.at).toLocaleTimeString() }}</span>
                <el-tag size="small" :type="ev.type === 'task_metrics' ? 'primary' : 'info'">
                  {{ ev.type }}
                </el-tag>
                <span v-if="ev.nodeKey" class="event-node">{{ ev.nodeKey }} · {{ formatRound(ev.attempt) }}</span>
                <span v-if="ev.status" class="event-status">{{ ev.status }}</span>
                <span v-if="ev.message" class="event-msg">{{ ev.message.slice(0, 80) }}</span>
              </div>
            </div>
          </div>
        </template>

        <div v-else-if="tasks.currentLoading" class="card">
          <div class="empty-state">
            <p>正在加载任务详情…</p>
          </div>
        </div>

        <div v-else-if="tasks.currentError" class="card">
          <div class="error-banner">{{ tasks.currentError }}</div>
        </div>

        <div v-else class="card">
          <div class="empty-state">
            <p>👈 从左侧列表选一个任务，或在右上表单新建</p>
            <p class="hint">ANALYZE → PLAN → 按文件 REWRITE → VERIFY 的全流程会在右栏实时滚动</p>
          </div>
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
.detail-header {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 14px;
  padding-bottom: 14px;
  border-bottom: 1px dashed var(--border-soft);
}

.detail-header h2 {
  font-size: 14px;
  margin: 0;
  font-family: 'SFMono-Regular', Consolas, monospace;
}

.fail-reason {
  font-size: 12px;
  color: var(--color-danger);
}

.current {
  display: flex;
  align-items: center;
  gap: 6px;
}

.duration {
  font-size: 11px;
  color: var(--text-muted);
  font-family: 'SFMono-Regular', Consolas, monospace;
}

.error-banner {
  background: rgba(245, 108, 108, 0.08);
  color: var(--color-danger);
  padding: 12px 14px;
  border-radius: 4px;
  font-size: 13px;
}

.event-log {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 280px;
  overflow-y: auto;
}

.event-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  padding: 4px 0;
  font-family: 'SFMono-Regular', Consolas, monospace;
}

.event-time {
  color: var(--text-muted);
}

.event-node {
  font-weight: 600;
}

.event-status {
  color: var(--el-color-primary);
}

.event-msg {
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 320px;
}
</style>
