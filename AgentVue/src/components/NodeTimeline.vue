<script setup lang="ts">
/**
 * 节点时间线 + 量化指标可视化。
 *
 * 一次迁移任务的执行序列在大多数情况下是 ANALYSIS → REWRITE → VERIFY；
 * VERIFY 失败就派生 (attempt+1) 的 REWRITE 和 VERIFY。
 *
 * 渲染策略：
 * - 节点按 `(nodeKey, attempt)` 排程：相同 key 的 attempt 0 → 1 → 2 在视觉上"沉淀"下来
 *   一目了然，外加节点小卡片之间的依赖箭头（来自后端 `dependsOn`）。
 * - VERIFY 的 `failureExcerpt` 默认折叠；展开后是诊断事件最有价值的内容。
 * - **运行中的节点自己会跳秒**：见下方 `now` —— 「正在跑多久了」是这个页面最需要实时性的数字，
 *   比进度条更能说明「它还活着」。它不能等节点结束才算得出来。
 */
import { computed, onUnmounted, ref, watch } from 'vue'

import type { DagNode, VerifyResult } from '@/api/types'
import {
  NODE_STATUS_LABEL,
  NODE_STATUS_TAG,
  NODE_TYPE_EMOJI,
  NODE_TYPE_LABEL,
  formatCoverage,
  formatDuration,
  formatRound,
} from '@/utils/status'

interface Props {
  nodes: DagNode[]
}

const props = defineProps<Props>()

/**
 * 把节点按下标顺序排列（后端给的顺序就是 DAG 调度顺序），同 key 多 attempt 紧挨着。
 * 不重新拓扑排序 —— 后端 DAG 调度器保证它给我们的就是合理顺序。
 */
const orderedNodes = computed(() => [...props.nodes].sort((a, b) => a.id - b.id))

// ------------------------------------------------------------------
// 运行中的秒表
// ------------------------------------------------------------------

/**
 * 「现在」的时间戳，仅在有节点处于 RUNNING 时按秒推进。
 *
 * 为什么要有这一层：节点的耗时代价是 `finishedAt - startedAt`，而运行中的节点
 * `finishedAt` 还是 null —— 不引入一个走动的「现在」，它就只能显示「-」，
 * 于是整个执行过程里最该动的那个数字是死的，页面看着像卡住了。
 *
 * 只在有 RUNNING 节点时才开定时器（而不是无条件每秒 tick）：
 * 任务跑完后页面会长时间停在那里，一个空转的 1Hz 定时器会让 Vue 每秒重算整棵子树。
 */
const now = ref(Date.now())
let ticker: ReturnType<typeof setInterval> | null = null

const hasRunningNode = computed(() => props.nodes.some((node) => node.status === 'RUNNING'))

function stopTicker(): void {
  if (ticker !== null) {
    clearInterval(ticker)
    ticker = null
  }
}

function syncTicker(): void {
  if (!hasRunningNode.value) {
    stopTicker()
    return
  }
  if (ticker === null) {
    now.value = Date.now()
    ticker = setInterval(() => {
      now.value = Date.now()
    }, 1_000)
  }
}

watch(hasRunningNode, syncTicker, { immediate: true })
onUnmounted(stopTicker)

/**
 * 节点耗时（毫秒）。三种情况：
 * - 已结束：`finishedAt - startedAt`
 * - 运行中：`now - startedAt`（**每次重算，所以数字会跳**）
 * - 还没开始（PENDING / 被跳过的占位节点）：null，显示「-」
 *
 * `startedAt` 为空时也返回 null：SSE 增量会补 startedAt，但历史数据或异常路径可能没有，
 * 猜一个起点会让「已运行 3 分 10 秒」变成一句假话。
 */
function elapsedMs(node: DagNode): number | null {
  if (!node.startedAt) return null
  const start = new Date(node.startedAt).getTime()
  if (Number.isNaN(start)) return null

  const end = node.finishedAt
    ? new Date(node.finishedAt).getTime()
    : node.status === 'RUNNING'
      ? now.value
      : null
  if (end === null || Number.isNaN(end)) return null
  return Math.max(0, end - start)
}

function isRunning(node: DagNode): boolean {
  return node.status === 'RUNNING'
}

/**
 * 后端「节点开始运行」时带的消息是 `REWRITE (attempt 0)` 这种机器文案。
 * 节点类型与轮次卡片上本来就有更好的展示，重复一遍只会挤占空间 ——
 * 所以只在 message 明显携带了额外信息时才展示它（目前主要就是重试提示）。
 */
const MACHINE_NODE_MESSAGE = /^[A-Z_]+ \(attempt \d+\)$/

/** 运行中节点要展示的进度消息；没有或不值得展示时返回 null。 */
function runningHint(node: DagNode): string | null {
  if (node.status !== 'RUNNING') return null
  const message = node.progressMessage
  if (!message || MACHINE_NODE_MESSAGE.test(message)) return null
  return message
}

/** 消息涉及失败/重试时用警示色 —— 这是用户最需要立刻看见的那一类状态。 */
function isRetrying(node: DagNode): boolean {
  return node.status === 'RUNNING' && /失败|重试/.test(node.progressMessage ?? '')
}

function verifyBadgeClass(v: VerifyResult | null): string {
  if (!v) return 'no-verify'
  if (v.compiled && v.testsFailed === 0 && v.testsTotal > 0) return 'verify-pass'
  if (v.compiled && v.testsTotal === 0) return 'verify-pass-norun'
  return 'verify-fail'
}

function summarizeVerify(v: VerifyResult | null): string {
  if (!v) return ''
  if (v.timedOut) return '验证超时'
  const parts: string[] = []
  parts.push(v.compiled ? '编译通过' : '编译失败')
  if (v.testsTotal > 0) {
    parts.push(`单测 ${v.testsPassed}/${v.testsTotal}`)
  }
  if (v.coverage >= 0) {
    parts.push(`覆盖 ${formatCoverage(v.coverage)}`)
  }
  return parts.join(' · ')
}
</script>

<template>
  <div>
    <div class="section-title">节点时间线</div>
    <div class="node-grid">
      <div
        v-for="node in orderedNodes"
        :key="node.id"
        class="node-card"
        :class="`node-${node.status.toLowerCase()}`"
      >
        <div class="node-head">
          <span class="emoji">{{ NODE_TYPE_EMOJI[node.nodeType] }}</span>
          <span class="title">{{ NODE_TYPE_LABEL[node.nodeType] }}</span>
          <!--
            后端 attempt 是 0 基下标（首个节点就是 0），直接显示会让人误读成
            「一次都没试过」。统一 formatRound 成「第 N 轮」，tooltip 里保留原始值方便对日志。
          -->
          <span
            class="attempt"
            :title="`后端 attempt=${node.attempt}（0 基下标，第 ${node.attempt + 1} 次尝试）`"
          >{{ formatRound(node.attempt) }}</span>
          <el-tag :type="NODE_STATUS_TAG[node.status]" size="small">
            {{ NODE_STATUS_LABEL[node.status] }}
          </el-tag>
        </div>

        <div class="node-meta">
          <!--
            运行中的节点显示实时秒表（1 秒一跳），结束后才显示最终耗时。
            「已运行 mm:ss」比一个静止的「-」有用得多 —— 它能区分「在跑」和「卡死」。
          -->
          <span class="elapsed" :class="{ 'elapsed-live': isRunning(node) }">
            <span v-if="isRunning(node)" class="live-dot" />
            {{ isRunning(node) ? '已运行' : '耗时' }}
            {{ formatDuration(elapsedMs(node)) }}
          </span>
          <span v-if="node.error" class="error">{{ node.error }}</span>
        </div>

        <!--
          重试提示。一次 LLM 调用的 HTTP 重试**不递增节点 attempt**，
          所以「正在重试 2/3」是页面上唯一能把「在重试」和「卡死」区分开的信息 ——
          没有它，一次 6 分钟的上游超时重试看起来就和程序挂了完全一样。
        -->
        <div
          v-if="runningHint(node)"
          class="retry-hint"
          :class="{ 'retry-hint-active': isRetrying(node) }"
        >
          {{ runningHint(node) }}
        </div>

        <div
          v-if="node.verify"
          class="verify-card"
          :class="verifyBadgeClass(node.verify)"
        >
          <div class="verify-summary">{{ summarizeVerify(node.verify) }}</div>
          <el-collapse v-if="node.verify.failureExcerpt">
            <el-collapse-item title="查看错误摘录" name="error">
              <pre>{{ node.verify.failureExcerpt }}</pre>
            </el-collapse-item>
          </el-collapse>
          <div v-else-if="!node.verify.compiled || node.verify.testsFailed > 0" class="empty-verify">
            <em>（沙箱未输出可解析的错误摘录）</em>
          </div>
        </div>
      </div>
    </div>

    <div v-if="orderedNodes.length === 0" class="empty-state">
      <p>暂无节点记录</p>
      <p class="hint">提交任务后这里会按调度顺序逐一出现节点</p>
    </div>
  </div>
</template>

<style scoped>
.node-grid {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.node-card {
  background: var(--bg-card);
  border-radius: 6px;
  padding: 12px 16px;
  border-left: 3px solid var(--border-soft);
  box-shadow: var(--shadow-card);
}

.node-card.node-running {
  border-left-color: var(--el-color-primary);
  background: rgba(64, 158, 255, 0.04);
}

.node-card.node-succeeded {
  border-left-color: var(--color-success);
}

.node-card.node-failed {
  border-left-color: var(--color-danger);
  background: rgba(245, 108, 108, 0.04);
}

.node-card.node-skipped {
  border-left-color: var(--color-warning);
}

.node-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.emoji {
  font-size: 16px;
}

.title {
  font-weight: 600;
}

.attempt {
  font-size: 11px;
  color: var(--text-muted);
  margin-left: 4px;
  border: 1px solid var(--border-soft);
  border-radius: 3px;
  padding: 0 4px;
  cursor: help;
}

.node-meta {
  margin-top: 6px;
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: var(--text-muted);
}

/** 实时秒表用等宽字体：不等宽的话数字每跳一下整行都会左右抖。 */
.elapsed {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-family: 'SFMono-Regular', Consolas, monospace;
}

.elapsed-live {
  color: var(--el-color-primary);
  font-weight: 600;
}

/** 呼吸圆点：一眼区分「正在跑」和「已经跑完的同一个数字」。 */
.live-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-color-primary);
  animation: live-pulse 1.4s ease-in-out infinite;
}

@keyframes live-pulse {
  0%,
  100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.35;
    transform: scale(0.75);
  }
}

.error {
  color: var(--color-danger);
}

/**
 * 运行中节点的进度消息（目前主要是「正在重试 2/3」）。
 * 用警示色而不是普通灰：它出现时意味着这次调用已经失败过一次，
 * 值得比「已运行 32s」更显眼。
 */
.retry-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted);
}

.retry-hint-active {
  color: var(--color-warning);
}

.verify-card {
  margin-top: 10px;
  padding: 8px 12px;
  border-radius: 4px;
  background: rgba(0, 0, 0, 0.02);
  border: 1px solid var(--border-soft);
  font-size: 12px;
}

.verify-card.verify-pass {
  background: rgba(103, 194, 58, 0.06);
  border-color: rgba(103, 194, 58, 0.4);
}

.verify-card.verify-pass-norun {
  background: rgba(144, 147, 153, 0.05);
}

.verify-card.verify-fail {
  background: rgba(245, 108, 108, 0.05);
  border-color: rgba(245, 108, 108, 0.4);
}

.verify-card.no-verify {
  background: rgba(0, 0, 0, 0.02);
}

.verify-summary {
  margin-bottom: 4px;
}

pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 12px;
  background: rgba(0, 0, 0, 0.04);
  padding: 8px;
  border-radius: 4px;
  max-height: 320px;
  overflow-y: auto;
}

.empty-verify {
  color: var(--text-muted);
  font-size: 11px;
  margin-top: 4px;
}
</style>
