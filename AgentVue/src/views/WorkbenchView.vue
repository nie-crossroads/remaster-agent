<script setup lang="ts">
/**
 * 一页工作台主入口（从原 App.vue 平移而来，现为路由 `/tasks` 与 `/tasks/:id` 的视图）。
 *
 * 布局：
 *   [ 顶栏 ：标题 + SSE 状态 ]
 *   [ 左栏 ：任务列表 | 任务表单 ]
 *   [ 右栏 ：任务详情——指标 + 节点时间线 + 补丁 / 事件流 ]
 *
 * 任务状态推送完毕后，store 自动暂停 SSE 连接；切任务由 store 重新订阅。
 *
 * 路由整合：带 `:id` 时（`/tasks/:id`）打开即自动选中该任务，复用既有详情视图与 SSE 订阅；
 * 不带 `id`（`/tasks`）则停在「新建任务」模式。
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'

import DagGraph from '@/components/DagGraph.vue'
import DiffViewer from '@/components/DiffViewer.vue'
import GateReview from '@/components/GateReview.vue'
import MetricsPanel from '@/components/MetricsPanel.vue'
import NodeTimeline from '@/components/NodeTimeline.vue'
import PlanReview from '@/components/PlanReview.vue'
import TaskForm from '@/components/TaskForm.vue'
import TaskList from '@/components/TaskList.vue'
import TraceTimeline from '@/components/TraceTimeline.vue'
import WriteBackPanel from '@/components/WriteBackPanel.vue'
import { useTasksStore } from '@/stores/tasks'
import {
  TASK_STATUS_LABEL,
  TASK_STATUS_TAG,
  formatDuration,
  formatDurationPair,
  formatRound,
  slowestHop,
} from '@/utils/status'

const tasks = useTasksStore()
const route = useRoute()

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
  const project = t.projectRoot.split(/[\\/]/).slice(-1)[0]
  // 没有入口文件 = 整仓升级模式（只升编译级别，不改代码），标题里要看得出来
  const target = t.entryFile ? (t.entryFile.split('/').pop() ?? t.entryFile) : '整仓升级'
  const location = `${project}/${target}`
  // 有任务名时把它放进标题：它是用户给这次迁移起的名字，比路径更容易认
  return t.name ? `任务 #${t.id} · ${t.name} · ${location}` : `任务 #${t.id} · ${location}`
})

/**
 * 最慢一跳的提示语 —— 链路面板的「不用展开也看得见」的那半句。
 *
 * 之所以不在页面顶部复述完整链路（几百条 span）：这一句已经回答了最常被问的那个问题
 * （「时间花在哪」），而细节仍然需要用户主动展开 —— 按需加载，不是藏着。
 *
 * 只看**最近一次执行**：一个任务会被跑好几次（取消、重跑），把历史运行一起算进来，
 * 「最慢一跳」会变成在回答一个没人问过的问题（「这个任务历史上哪一跳最慢」）。
 */
const slowestSpanHint = computed(() => {
  const runs = tasks.trace?.runs ?? []
  const latest = runs[runs.length - 1]
  if (!latest || latest.spans.length === 0) return null
  const slowest = slowestHop(latest.spans)
  if (!slowest) return null
  return `最近一次（第 ${runs.length} 次执行）最慢一跳：${slowest.shortName}（${formatDuration(slowest.durationMs)}）`
})

/**
 * 实时运行计时：任务 RUNNING 时每秒按 createdAt 起算「已运行 mm:ss」；
 * 非 RUNNING（终态）则停表，改用后端给的耗时 —— 而且是**两个**：
 * 「实际运行」= 各次运行的墙钟之和（机器一共干了多久），
 * 「端到端」= 入队到本次运行结束（你一共等了多久，含排队、含被取消那次的等待）。
 * 取消重跑过的任务上两者差出数量级（实测 78 秒 vs 2 小时 33 分），
 * 只给一个数时读者无从知道它答的是哪个问题，所以这里也走共用的成对格式化。
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
  return formatDurationPair(t.runDurationMs, t.metrics?.durationMs)
})

watch(() => detail.value?.task?.status, syncTicker)

/**
 * 取消任务 —— 先确认再发请求。
 *
 * <h3>为什么要确认</h3>
 * 取消是**不可逆的一步**：重跑虽然能从断点续上，但当前节点已经烧掉的时间和 token 拿不回来。
 *
 * <h3>为什么确认文案对 RUNNING 与其它状态不一样</h3>
 * 因为两者发生的事情完全不同：RUNNING 下请求只是「挂号」，任务还会再跑一会儿；
 * 其它状态下它会立刻停。用同一句文案，必然对其中一种情况撒谎 ——
 * 而用户据此形成的预期（「点完就停」/「点完还要等」）恰好决定了他会不会反复点击。
 */
async function onCancel(): Promise<void> {
  const running = detail.value?.task.status === 'RUNNING'
  try {
    await ElMessageBox.confirm(
      running
        ? '任务正在执行。取消不会立刻中断：当前节点（可能是沙箱里的 mvn test）会先跑完，'
          + '回到节点边界后才停 —— 强行杀子进程会留下半截工作目录。确定取消吗？'
        : '确定取消这个任务吗？之后可以用「重跑」从断点继续，已成功的节点不会重跑。',
      '取消任务',
      { confirmButtonText: '确定取消', cancelButtonText: '再想想', type: 'warning' },
    )
  } catch {
    // 用户点了「再想想」，什么都不做
    return
  }
  const ok = await tasks.cancelCurrentTask()
  if (ok) {
    ElMessage.success(running ? '已请求取消，等当前节点跑完即停' : '任务已取消')
  }
}

/**
 * 第一步：预检 —— 只查不写。
 *
 * 它存在的意义就是「让危险动作前面隔着一张清单」。用户按下之后什么都不会发生，
 * 只会看到「要写哪几个文件、有没有拦路的事」，然后才轮到决定。
 */
async function onWriteBackPreflight(): Promise<void> {
  const report = await tasks.preflightWriteBack()
  if (report && report.blocked.length > 0) {
    ElMessage.warning('预检未通过，请看清单里的拦路项')
  }
}

/**
 * 第二步：真正写入源工程 —— 再要一次确认。
 *
 * <h3>为什么清单之外还要一个弹窗</h3>
 * 清单回答的是「写什么」，弹窗回答的是「代价是什么、怎么退回去」——
 * 这两件事在按下不可逆按钮之前都该被说一遍。弹窗里刻意点明
 * 「源文件会被覆盖」和「备份在哪个目录」，因为那正是用户此刻最该知道、
 * 而清单的表格里没有的两条信息。
 *
 * 用 warning 而不是 error 配色：这是一次正常的、有人主动要求的操作，
 * 不是系统出错。过了这一关，写的动作本身只写本地文件 —— 不会 commit、不会 push。
 */
async function onWriteBackApply(): Promise<void> {
  const report = tasks.writeBackReport
  if (!report) return
  try {
    await ElMessageBox.confirm(
      `将用沙箱里已通过验证的版本，覆盖源工程 ${report.projectRoot ?? ''} 中的 `
        + `${report.files.length} 个文件。原件会先备份，出错可用备份目录整棵拷回；`
        + '此操作只写本地文件，不会替你提交或推送。',
      '写入源工程',
      { confirmButtonText: '确定写入', cancelButtonText: '再想想', type: 'warning' },
    )
  } catch {
    return
  }
  const ok = await tasks.applyWriteBack()
  if (ok) {
    ElMessage.success('已写入源工程，备份目录见下方清单')
  } else {
    ElMessage.error(tasks.writeBackError ?? '写入未执行，请看清单中的拦路项')
  }
}

onMounted(async () => {
  await tasks.refreshList()
  // 带任务 id 直达详情（来自 /tasks/:id，例如演示运行后跳转）；否则停在新建模式
  const idParam = route.params.id
  if (typeof idParam === 'string' && idParam.trim() !== '' && /^\d+$/.test(idParam)) {
    await tasks.selectTask(Number(idParam))
  } else {
    syncTicker()
  }
})

onUnmounted(() => {
  // 离开工作台时确保 SSE 关闭，避免悬挂的 EventSource
  tasks.teardown()
  stopTicker()
})
</script>

<template>
  <div class="workbench">
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
              <div class="detail-head-row">
                <h2>{{ lastTaskHeaderLabel }}</h2>
                <!--
                  任务控制按钮。可用性判据与后端闸门同源（store 的 canCancel / canRetry），
                  避免出现「按钮亮着、点下去 409」—— 一个不承认自己不可用的界面比没有按钮更糟。

                  尺寸与表单里的「提交任务」保持同一套（默认尺寸、不 plain）：
                  这两个动作和提交是同一层级的动作，不该因为渲染在卡片头部就矮一截。
                  颜色按**动作性质**分：重跑是主色（可逆、接着往下跑），
                  取消是危险色（不可逆，且会丢掉当前节点已烧掉的时间和 token）。
                  二者互斥（RUNNING 只给取消、FAILED|CANCELLED 只给重跑），不存在并排比较。
                -->
                <div class="controls">
                  <el-button
                    v-if="tasks.canCancel"
                    type="danger"
                    :loading="tasks.controlling"
                    :disabled="tasks.cancellingInProgress"
                    @click="onCancel"
                  >
                    {{ tasks.cancellingInProgress ? '正在取消…' : '取消任务' }}
                  </el-button>
                  <el-button
                    v-if="tasks.canRetry"
                    type="primary"
                    :loading="tasks.controlling"
                    @click="tasks.retryCurrentTask()"
                  >
                    重跑
                  </el-button>
                </div>
              </div>

              <!--
                取消是协作式的：点下去之后任务可能还要跑几分钟才停。
                这段时间界面必须说话，否则用户会以为按钮没生效而反复点击。
              -->
              <div v-if="tasks.cancellingInProgress" class="cancel-pending">
                已请求取消，将在当前节点跑完后停止（节点内部无法安全中断，强行杀子进程会留下半截工作目录）
              </div>

              <div v-if="tasks.controlError" class="control-error">
                {{ tasks.controlError }}
              </div>

              <div v-if="detail.task.failReason" class="fail-reason">
                {{ detail.task.status === 'CANCELLED' ? '取消原因' : '失败原因' }}：{{ detail.task.failReason }}
              </div>
            </div>

            <MetricsPanel
              :metrics="detail.task.metrics"
              :run-duration-ms="detail.task.runDurationMs ?? null"
            />
          </div>

          <!--
            规划评审与人工门禁都是「要你做的事」，一起放在指标之后、执行图之前。
            二者互斥（同一时刻任务只可能卡在其中一个），所以谁出现都先入视线。
          -->
          <PlanReview
            :plan="detail.plan"
            :status="detail.task.status"
            :busy="tasks.reviewing"
            :error="tasks.reviewError"
            :gate-open="detail.gate !== null"
            @approve="tasks.approveCurrentPlan()"
            @reject="(reason) => tasks.rejectCurrentPlan(reason)"
          />

          <GateReview
            :gate="detail.gate"
            :busy="tasks.reviewing"
            :error="tasks.reviewError"
            @approve="(reviewer) => tasks.approveCurrentGate(reviewer)"
            @reject="(comment, reviewer) => tasks.rejectCurrentGate(comment, reviewer)"
          />

          <div class="card">
            <DagGraph :nodes="detail.nodes" :gate-node-id="detail.gate?.nodeId ?? null" />
          </div>

          <div class="card">
            <NodeTimeline :nodes="detail.nodes" />
          </div>

          <div class="card">
            <TraceTimeline
              :trace="tasks.trace"
              :loading="tasks.traceLoading"
              :error="tasks.traceError"
              @refresh="tasks.loadTrace()"
            />
            <div v-if="slowestSpanHint" class="trace-hint">{{ slowestSpanHint }}</div>
          </div>

          <div class="card">
            <DiffViewer :patches="detail.patches" />
          </div>

          <!--
            回写放在补丁之后：看完了 diff 才谈得上「那就写回去」。
            它自己是一整张卡片而不是标题栏上的一个按钮 —— 因为它必须先摆出清单
            （写哪几个文件、有没有拦路的事），而不能让人盲签。理由见组件头部注释。
          -->
          <WriteBackPanel
            :report="tasks.writeBackReport"
            :applied="detail.writeBack"
            :can-write-back="tasks.canWriteBack"
            :loading="tasks.writeBackLoading"
            :applying="tasks.writeBackApplying"
            :error="tasks.writeBackError"
            @preflight="onWriteBackPreflight"
            @apply="onWriteBackApply"
            @dismiss="tasks.clearWriteBack()"
          />

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
            <p>👈 点「新建任务」创建迁移，或从左侧列表选一个任务查看详情</p>
            <p class="hint">ANALYZE → PLAN → 按文件 REWRITE → VERIFY 的全流程会在右栏实时滚动</p>
          </div>
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
/*
 * 整屏布局：一条顶栏 + 下面左右两栏，整体塞进「视口减去全局导航」的那块高度里。
 *
 * 高度在这里量一次就够，分栏（.app-shell）用 flex 吃掉剩余部分 —— 好处是不必知道顶栏
 * 精确多少像素（它随内容变），也就不会因为它长高几像素而在页面右侧多出一条
 * 本不该存在的竖向滚动条。overflow: hidden 是兜底：这一屏的滚动只发生在内层两栏里。
 */
.workbench {
  display: flex;
  flex-direction: column;
  height: calc(100vh - var(--topnav-h));
  overflow: hidden;
}

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

/* 标题与操作按钮一行：按钮靠右，标题过长时省略而不是把按钮挤下去 */
.detail-head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.detail-head-row h2 {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.controls {
  display: flex;
  gap: 6px;
  flex: 0 0 auto;
}

/* 间距只由 gap 决定：Element Plus 会给相邻 el-button 再补一层 margin-left，
   与 gap 叠加会变成双倍（按钮尺寸从 small 提到默认后更明显）。 */
.controls :deep(.el-button + .el-button) {
  margin-left: 0;
}

/* 「已请求取消」的提示：不染成红色 —— 取消不是错误，只是还没停下来 */
.cancel-pending {
  font-size: 12px;
  color: var(--color-warning);
}

.control-error {
  font-size: 12px;
  color: var(--color-danger);
}

.trace-hint {
  margin-top: 8px;
  font-size: 11px;
  color: var(--text-muted);
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
