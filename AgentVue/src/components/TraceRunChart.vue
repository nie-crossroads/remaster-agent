<script setup lang="ts">
/**
 * 一次运行的甘特图 —— 链路面板里的一张图，对应一条 trace。
 *
 * ## 为什么单独一个运行一张图
 * 一个任务会被跑好几次（建单那次 HTTP 请求、被取消的那次、重跑那次…）。
 * 曾经它们被拼在同一根时间轴上，结果是「取消 → 重跑」之间几小时的空档把每次真正干活的
 * 耗时压成 0.6% 的细线 —— 界面上看起来就是「好多条的进度是空的」。
 * 每张图各自以**本组**起点为原点，比例才说明得了「这次的时间花在哪」。
 *
 * ## 为什么层级用 depth 而不是自己算
 * 后端已经按 **父指针** 算好了缩进层级。前端不再算一遍的理由不只是省事：并行节点的 span
 * 在时间上会交叠（两个文件同时改写就是这样），用时间戳套嵌套关系必然算错 ——
 * 而算错的表现是「缩进看起来不太对」，没人会为此报警，但「时间花在哪一层」就成了假的。
 *
 * ## 为什么用相对时间画条，而不是绝对时间
 * 一条链路的起点是任意的（取决于你什么时候点提交）。用「相对最早开始时刻的偏移 + 占比」
 * 画出来，同一张图在不同任务之间可比；用绝对时间则需要一个坐标轴，而且大部分跨度是空闲。
 */
import { computed } from 'vue'

import type { TraceRunView, TraceSpanView } from '@/api/types'
import {
  SPAN_STATUS_COLOR,
  SPAN_STATUS_LABEL,
  formatDuration,
  spanCategoryColor,
} from '@/utils/status'

interface Props {
  /** 一次运行（一条 trace）的全部 span 与组级元信息。 */
  run: TraceRunView
}

const props = defineProps<Props>()

/**
 * 时间轴的原点 = **本组**最早那条 span 的开始时刻。
 *
 * 用它而不是「任务创建时间」：任务可能在队列里排了很久，把排队时间算进时间轴会让
 * 真正干活的那段被压成看不见的一条细线 —— 而「哪一跳最慢」正是这张图要回答的问题。
 */
const originMs = computed(() => {
  const starts = props.run.spans
    .map((span) => Date.parse(span.startedAt))
    .filter((value) => !Number.isNaN(value))
  return starts.length === 0 ? 0 : Math.min(...starts)
})

/**
 * 本组时间轴的总跨度。
 *
 * 取 max(后端算的组耗时, 最后一条 span 的结束偏移)，而不是只用后端那个值：
 * 未结束的 span 没有结束时间，后端算组耗时会漏掉它们的尾部；只用后端值会让这些条溢出画布。
 */
const spanMs = computed(() => {
  const fromSpans = props.run.spans.reduce((max, span) => {
    const start = Date.parse(span.startedAt)
    if (Number.isNaN(start)) return max
    return Math.max(max, start - originMs.value + span.durationMs)
  }, 0)
  return Math.max(props.run.durationMs, fromSpans, 1)
})

/** 每条 span 的横向位置与宽度（百分比）。0 耗时的 span 也给一个最小宽度，否则它彻底不可见。 */
function barStyle(span: TraceSpanView): Record<string, string> {
  const start = Date.parse(span.startedAt)
  const offset = Number.isNaN(start) ? 0 : start - originMs.value
  const left = Math.min(100, Math.max(0, (offset / spanMs.value) * 100))
  const rawWidth = (span.durationMs / spanMs.value) * 100
  return {
    left: `${left}%`,
    width: `${Math.min(100 - left, Math.max(rawWidth, 0.6))}%`,
    background: spanCategoryColor(span.name),
  }
}

/** 缩进：每一层 14px。后端已限制最大深度，这里不会再出现深到看不见的情况。 */
function indentStyle(span: TraceSpanView): Record<string, string> {
  return { paddingLeft: `${span.depth * 14}px` }
}
</script>

<template>
  <div class="trace-list">
    <div v-for="span in props.run.spans" :key="span.spanId" class="trace-row">
      <div class="trace-label" :style="indentStyle(span)">
        <span class="row-dot" :style="{ background: spanCategoryColor(span.name) }" />
        <span class="row-name" :title="span.name">{{ span.shortName }}</span>
        <span
          v-if="span.status && span.status !== 'OK'"
          class="row-status"
          :style="{ color: SPAN_STATUS_COLOR[span.status] ?? 'inherit' }"
        >
          {{ SPAN_STATUS_LABEL[span.status] ?? span.status }}
        </span>
      </div>
      <div class="trace-track">
        <div class="trace-bar" :style="barStyle(span)" />
      </div>
      <div class="trace-duration">
        <!-- 0 耗时且没有结束：说清楚「没跑完」，而不是显示成「0 ms 就干完了」 -->
        {{ span.durationMs > 0 ? formatDuration(span.durationMs) : '未结束' }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.trace-list {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.trace-row {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) 2fr 70px;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  font-family: 'SFMono-Regular', Consolas, monospace;
}

.trace-label {
  display: flex;
  align-items: center;
  gap: 6px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.row-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex: 0 0 auto;
}

.row-name {
  overflow: hidden;
  text-overflow: ellipsis;
}

.row-status {
  font-size: 10px;
  flex: 0 0 auto;
}

.trace-track {
  position: relative;
  height: 12px;
  background: rgba(144, 147, 153, 0.1);
  border-radius: 3px;
}

.trace-bar {
  position: absolute;
  top: 0;
  height: 100%;
  border-radius: 3px;
  min-width: 2px;
}

.trace-duration {
  text-align: right;
  color: var(--text-muted);
  font-size: 11px;
}
</style>
