<script setup lang="ts">
/**
 * 全链路链路面板 —— 一次任务**每次执行**一张时间轴瀑布图。
 *
 * ## 它回答的问题
 * 「这次任务跑的这 1 分 17 秒里，哪一跳占了多少？」在这之前，这个问题只能靠 `node_status`
 * 事件的时间戳手工推。埋点之后，每个 DAG 节点、每次模型调用、每次沙箱执行都有一条 span，
 * 这里按父子层级和真实耗时画出来。
 *
 * ## 为什么按运行分成多张图
 * 一个任务会被跑好几次：建单那次 HTTP 请求、被取消的那次、重跑那次…… 它们各有各的 traceId。
 * 早先把这些 span 平铺在同一根时间轴上，结果是「取消 → 重跑」之间几小时的空档把每次真正
 * 干活的耗时压成 0.6% 的细线 —— 界面上看起来就是「好多条的进度是空的」。
 * 现在一次执行一张图、各自一把时间轴（见 `TraceRunChart.vue`），**最新一次在最上面**。
 *
 * ## 空态必须说清楚「是没有，还是还没拉」
 * 「这个任务没有链路数据」（旧任务、埋点关闭）与「还没点刷新」是两个完全不同的结论。
 * 前者要告诉用户为什么（比如任务跑在埋点开启之前），后者只需要一个按钮。
 */
import { computed } from 'vue'

import type { TaskTrace, TraceRunView } from '@/api/types'
import { formatDuration, slowestHop, spanCategoryColor } from '@/utils/status'

import TraceRunChart from './TraceRunChart.vue'

interface Props {
  trace: TaskTrace | null
  loading: boolean
  error: string | null
}

const props = defineProps<Props>()
const emit = defineEmits<{ refresh: [] }>()

/**
 * 图例项。
 *
 * 显式列出而不是从 span 列表里归纳：归纳出来的图例在「这次任务没有模型调用」时
 * 会少一项，于是同一种颜色在不同任务里的含义不稳定 —— 而图例的全部价值就是稳定。
 */
const legend = [
  { prefix: 'task', label: '任务' },
  { prefix: 'node:', label: 'DAG 节点' },
  { prefix: 'llm:', label: '模型调用' },
  { prefix: 'sandbox:', label: '沙箱执行' },
  { prefix: 'api:', label: 'HTTP 接口' },
]

/**
 * 展示顺序：最新一次在最上面，但**序号仍按真实执行顺序**（「第 1 次执行」永远是最早那次）。
 *
 * 倒序只做一次（而不是在模板里反复调用），多组时也不会退化成 O(n²)。
 */
const orderedRuns = computed(() =>
  (props.trace?.runs ?? [])
    .map((run, index) => ({ run, ordinal: index + 1 }))
    .reverse(),
)

const isEmpty = computed(() => (props.trace?.runs?.length ?? 0) === 0)

/** 概括一句话：这个任务被跑了几次、最近一次多久、最慢的一跳是谁。 */
const headline = computed(() => {
  const runs = props.trace?.runs ?? []
  if (runs.length === 0) return ''
  const latest = runs[runs.length - 1]
  const head = `共 ${runs.length} 次执行 · 最近一次 ${formatDuration(latest.durationMs)}`
  const slowest = slowestHop(latest.spans)
  if (!slowest || latest.durationMs <= 0) return head
  const share = Math.round((slowest.durationMs / latest.durationMs) * 100)
  return `${head}，最慢一跳「${slowest.shortName}」占 ${share}%（${formatDuration(slowest.durationMs)}）`
})

/** 组标题里的时刻：显示**本地时间**（后端的 Instant 是 UTC，直接截字符串会让人对不上钟）。 */
function formatClock(iso: string | null): string {
  if (!iso) return '—'
  const ms = Date.parse(iso)
  if (Number.isNaN(ms)) return '—'
  const d = new Date(ms)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** traceId 只露前 8 位：完整 32 位在界面上没人读，需要时去库里按前缀查即可。 */
function shortTraceId(traceId: string): string {
  return traceId.length > 8 ? traceId.slice(0, 8) : traceId
}

/**
 * 起止时刻写成一段。
 *
 * 起止落在同一秒时（只跑了一个 span 的运行，如建单那次 HTTP 请求）不写成
 * 「10:54:04 → 10:54:04」—— 那看起来像数据错了，其实只是精确到秒不够细。
 */
function formatRange(run: TraceRunView): string {
  const from = formatClock(run.startedAt)
  if (!run.endedAt) return `${from} → 进行中`
  const to = formatClock(run.endedAt)
  return to === from ? from : `${from} → ${to}`
}
</script>

<template>
  <div>
    <div class="section-title">
      链路耗时
      <!-- 链路这块唯一的动作，用主色让它可被发现（默认灰按钮在这个标题行里几乎看不见） -->
      <el-button
        class="trace-refresh"
        type="primary"
        size="small"
        :loading="props.loading"
        @click="emit('refresh')"
      >
        {{ props.trace ? '重新拉取' : '拉取链路' }}
      </el-button>
    </div>

    <div v-if="props.error" class="error-banner">{{ props.error }}</div>

    <div v-else-if="props.loading && !props.trace" class="empty-state">
      <p>正在拉取链路数据…</p>
    </div>

    <!--
      空态分两句：一句是「确实没有」，一句是「还没拉」。它们是完全不同的结论 ——
      合并成一句会让人以为「这个任务没有埋点」，而其实只是还没点。
    -->
    <div v-else-if="!props.trace" class="empty-state">
      <p>尚未拉取链路数据</p>
      <p class="hint">
        链路是按需加载的 —— 一次任务可能产生几百条 span，没必要跟着每次详情刷新一起拉
      </p>
    </div>

    <div v-else-if="isEmpty" class="empty-state">
      <p>这个任务没有链路数据</p>
      <p class="hint">
        常见原因：任务跑在埋点开启之前（`remaster.trace.enabled=false`），
        或 span 还没来得及批量落库（攒批默认 2 秒）
      </p>
    </div>

    <template v-else>
      <div class="trace-summary">
        <span class="headline">{{ headline }}</span>
      </div>

      <!-- 图例：色条的含义写在界面上，而不是让人去读代码 -->
      <div class="legend">
        <span v-for="item in legend" :key="item.prefix" class="legend-item">
          <span class="legend-dot" :style="{ background: spanCategoryColor(item.prefix) }" />
          {{ item.label }}
        </span>
      </div>

      <!--
        给「节点条与模型调用条几乎等宽」一个固定解释。
        它看起来就像进度条画重了，其实那是这一层的真实结构 —— 与其每次都被当成 bug 问一遍，
        不如把答案钉在图下面（实测任务 64：node: plan 11.7s / llm: PLAN 11.2s）。
      -->
      <p class="trace-note">
        节点条与它的子操作条是<b>嵌套</b>关系：<code>node:*</code> 包住自己那一跳唯一的子操作
        （规划与重写下面是模型调用，验证下面是沙箱 <code>mvn</code>），所以两者几乎等宽 ——
        差的那一点是本次操作的准备与收尾，通常在毫秒级。要读的是它占总耗时的比例。
      </p>

      <!--
        一次执行一张图。滚动放在这个容器上（而不是每张图各自滚动）：
        多组时各自出现滚动条会让「哪次是哪次」的关系更难读。
      -->
      <div class="run-scroll">
        <div v-for="item in orderedRuns" :key="item.run.traceId" class="run-block">
          <div class="run-head">
            <span class="run-title">
              第 {{ item.ordinal }} 次执行 · {{ item.run.rootName ?? 'trace' }}
            </span>
            <span class="run-meta">
              {{ formatRange(item.run) }}
              · {{ formatDuration(item.run.durationMs) }}
              · {{ item.run.spanCount }} {{ item.run.spanCount === 1 ? 'span' : 'spans' }}
              · trace {{ shortTraceId(item.run.traceId) }}
            </span>
          </div>
          <TraceRunChart :run="item.run" />
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.trace-refresh {
  float: right;
  margin-top: -2px;
}

.trace-summary {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
  font-size: 12px;
}

.headline {
  font-weight: 600;
}

.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 10px;
  font-size: 11px;
  color: var(--text-muted);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

/**
 * 一句固定解释：节点条与子操作条几乎等宽是这一层的真实结构，不是进度条算错。
 * 用常规说明色而不是警示色 —— 它是「怎么读这张图」，不是「这里有问题」。
 */
.trace-note {
  margin: 0 0 10px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--text-muted);
}

.trace-note code {
  background: var(--bg-page);
  padding: 0 4px;
  border-radius: 3px;
  font-family: 'SFMono-Regular', Consolas, monospace;
}

.legend-dot {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  display: inline-block;
}

.run-scroll {
  max-height: 420px;
  overflow-y: auto;
}

/* 组与组之间用一道虚线分开：它们是两次独立的执行，不是同一次里的两个阶段 */
.run-block + .run-block {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--border-soft);
}

.run-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 4px;
  font-size: 11px;
}

.run-title {
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.run-meta {
  color: var(--text-muted);
  font-family: 'SFMono-Regular', Consolas, monospace;
  white-space: nowrap;
  flex: 0 0 auto;
}

.error-banner {
  background: rgba(245, 108, 108, 0.08);
  color: var(--color-danger);
  padding: 10px 12px;
  border-radius: 4px;
  font-size: 12px;
}
</style>
