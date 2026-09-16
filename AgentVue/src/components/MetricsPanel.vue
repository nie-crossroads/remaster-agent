<script setup lang="ts">
/**
 * 量化指标面板。
 *
 * 显示：编译/单测通过率、覆盖率、LLM 调用次数与花费、attempt 数、单轮总时长。
 *
 * ## 三条刻意定下的显示规则
 *
 * 1. **通过率一律由计数现算，不用后端给的比率字段。**
 *    `compilePassed / filesTotal` 这两个计数在 REST 快照与 SSE 增量里**都在**，
 *    而比率字段曾经在增量里缺失（后端把存储形状直接序列化，派生字段全丢）——
 *    于是任务刚成功的那一刻，指标面板被一份没有比率的载荷覆盖，进度条归零、
 *    状态被判成失败显示 ✗。能从计数算出来的结论，就不要依赖第二份拷贝。
 *
 * 2. **「没有样本」和「0%」是两件事。**
 *    分母为 0（没编过任何文件 / 这个工程没有单测）时显示灰色说明文字，
 *    **不画进度条、也不判失败** —— 画一条 0% 的红条会把「没跑」说成「跑挂了」，
 *    这正是指标撒谎。分母为 0 时后端算出的通过率也是 0，光看比率根本分不出来。
 *
 * 3. **数值与结论都写出来，不靠图标暗示。**
 *    进度条同一行给「100%」和「通过 / 未通过 / 部分通过」的文字：
 *    一个绿色对勾和一个小图标在不同缩放下很容易被看错（实际踩到过）。
 */
import { computed } from 'vue'

import type { Metrics } from '@/api/types'
import { formatCost, formatDuration, shouldSplitDuration } from '@/utils/status'

interface Props {
  metrics: Metrics | null
  /**
   * 累计运行时长（各次运行墙钟之和）。来自任务视图而非 metrics ——
   * 查询接口从 trace_span 现算，SSE 增量里没有它。
   * `null` / 不传 = 没有链路数据（埋点接上之前的老任务），此时只显示端到端。
   */
  runDurationMs?: number | null
  taskTitle?: string
}

/** 一个通过率指标的展示形态。 */
interface RateView {
  /** 0~100 的整数；null = 没有样本，不该画条 */
  pct: number | null
  state: RateState
  /** 分子/分母的原始计数，回答「分母是什么」 */
  detail: string
}

type RateState = 'pass' | 'partial' | 'fail' | 'empty'

const props = defineProps<Props>()

const hasMetrics = computed(() => props.metrics !== null)

/**
 * 耗时要不要拆成两个数显示。
 *
 * 「累计运行时长」答的是「机器一共干了多久」，「端到端耗时」答的是「你一共等了多久」——
 * 取消重跑过的任务上两者差出数量级（实测任务 #10 是 78 秒 vs 2 小时 33 分）。
 * 判据与任务列表、顶部状态行共用 `shouldSplitDuration`，三处不能各写一份。
 */
const splitDuration = computed(() =>
  shouldSplitDuration(props.runDurationMs, props.metrics?.durationMs),
)

/** 大数字显示哪个：能拆开时显示「实际运行」，否则退回端到端。 */
const primaryDurationMs = computed(() =>
  splitDuration.value ? props.runDurationMs : props.metrics?.durationMs,
)

function clampPercent(value: number): number {
  return Math.min(100, Math.max(0, Math.round(value)))
}

/**
 * 由原始计数算通过率。
 *
 * 分母为 0 时返回「无样本」而不是 0% —— 见文件头第 2 条。
 * 这里**刻意不接受**后端传的比率作为兜底：一份能算出计数、却算不出比率的实现，
 * 说明这份载荷的形状本身可疑，静默回退只会把问题藏起来。
 */
function rateView(passed: number | undefined, total: number | undefined,
                  unit: string, emptyHint: string): RateView {
  const denominator = total ?? 0
  const numerator = passed ?? 0

  if (denominator <= 0) {
    return { pct: null, state: 'empty', detail: emptyHint }
  }
  const pct = clampPercent((numerator / denominator) * 100)
  const state: RateState = pct === 100 ? 'pass' : pct === 0 ? 'fail' : 'partial'
  return { pct, state, detail: `${numerator}/${denominator} ${unit}` }
}

const compileRate = computed(() =>
  rateView(props.metrics?.compilePassed, props.metrics?.filesTotal, '个文件', '本工程没有需要迁移的源文件'),
)

const testRate = computed(() =>
  rateView(props.metrics?.testsPassed, props.metrics?.testsTotal, '条用例', '本工程没有单测（不是失败）'),
)

/** 覆盖率 0~1；-1 = 未采集，必须与「真的 0%」分开显示。 */
const coveragePct = computed(() => {
  const c = props.metrics?.coverage
  if (c == null || !Number.isFinite(c) || c < 0) return null
  // 容忍两种量纲：0.97 与 97（后端约定是 0~1，但夹一下不至于把 97 画成 9700%）
  return clampPercent(c > 1 ? c : c * 100)
})

/** 是否发生过回退：优先看计数，字段缺失时退回布尔值。 */
const retried = computed(() => {
  const m = props.metrics
  if (!m) return false
  return m.verifyAttempts > 1 || m.retried === true
})

/** 进度条配色。'empty' 不会走到这里（那种情况根本不画条）。 */
function progressStatus(state: RateState): 'success' | 'warning' | 'exception' {
  if (state === 'pass') return 'success'
  if (state === 'partial') return 'warning'
  return 'exception'
}

function verdictLabel(state: RateState): string {
  switch (state) {
    case 'pass':
      return '通过'
    case 'partial':
      return '部分通过'
    case 'fail':
      return '未通过'
    default:
      return '未采集'
  }
}
</script>

<template>
  <div class="card">
    <div class="section-title">量化指标</div>
    <div v-if="!hasMetrics" class="empty-state">
      <p>任务尚未产出指标</p>
      <p class="hint">至少完成 ANALYZE 阶段后会开始有数据</p>
    </div>
    <div v-else class="metrics-grid">
      <div class="metric">
        <div class="label">编译通过率</div>
        <div v-if="compileRate.pct === null" class="muted">{{ compileRate.detail }}</div>
        <template v-else>
          <el-progress
            :percentage="compileRate.pct"
            :status="progressStatus(compileRate.state)"
            :stroke-width="14"
            :show-text="false"
          />
          <div class="small">
            <span class="pct">{{ compileRate.pct }}%</span>
            <span class="verdict" :class="`verdict-${compileRate.state}`">
              {{ verdictLabel(compileRate.state) }}
            </span>
            · {{ compileRate.detail }}
          </div>
        </template>
      </div>

      <div class="metric">
        <div class="label">单测通过率</div>
        <div v-if="testRate.pct === null" class="muted">{{ testRate.detail }}</div>
        <template v-else>
          <el-progress
            :percentage="testRate.pct"
            :status="progressStatus(testRate.state)"
            :stroke-width="14"
            :show-text="false"
          />
          <div class="small">
            <span class="pct">{{ testRate.pct }}%</span>
            <span class="verdict" :class="`verdict-${testRate.state}`">
              {{ verdictLabel(testRate.state) }}
            </span>
            · {{ testRate.detail }}
          </div>
        </template>
      </div>

      <div class="metric">
        <div class="label">行覆盖率</div>
        <template v-if="coveragePct !== null">
          <el-progress :percentage="coveragePct" :stroke-width="14" :show-text="false" />
          <div class="small"><span class="pct">{{ coveragePct }}%</span> · JaCoCo 行覆盖</div>
        </template>
        <div v-else class="muted">未采集（任务非 SUCCEEDED 或沙箱未挂 JaCoCo）</div>
      </div>

      <div class="metric">
        <div class="label">LLM 调用</div>
        <div class="big">{{ props.metrics?.llmCalls ?? 0 }} 次</div>
        <div class="small">
          prompt {{ props.metrics?.promptTokens ?? 0 }} + completion {{ props.metrics?.completionTokens ?? 0 }} tokens
        </div>
      </div>

      <div class="metric">
        <div class="label">总花费</div>
        <div class="big">{{ formatCost(props.metrics?.totalCost) }}</div>
        <div class="small">
          rewrite verify 循环 {{ props.metrics?.verifyAttempts ?? 0 }} 次
          <span v-if="retried" class="badge">已回退</span>
        </div>
      </div>

      <div class="metric">
        <div class="label">{{ splitDuration ? '实际运行耗时' : '总耗时' }}</div>
        <div class="big">{{ formatDuration(primaryDurationMs) }}</div>
        <div v-if="splitDuration" class="small">
          端到端 {{ formatDuration(props.metrics?.durationMs) }} · 含排队与等待
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 16px;
}

.metric {
  background: rgba(0, 0, 0, 0.02);
  border-radius: 4px;
  padding: 12px;
}

.label {
  font-size: 11px;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin-bottom: 6px;
}

.big {
  font-size: 22px;
  font-weight: 600;
  color: var(--text-primary);
  font-family: 'SFMono-Regular', Consolas, monospace;
}

.small {
  margin-top: 4px;
  font-size: 11px;
  color: var(--text-muted);
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

/** 百分比必须是重点：图标在不同缩放下容易被看错，数字不会。 */
.pct {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-weight: 600;
  color: var(--text-primary);
}

.verdict {
  padding: 0 5px;
  border-radius: 3px;
  font-size: 10px;
  font-weight: 500;
}

.verdict-pass {
  background: rgba(103, 194, 58, 0.15);
  color: var(--color-success);
}

.verdict-partial {
  background: rgba(230, 162, 60, 0.15);
  color: var(--color-warning);
}

.verdict-fail {
  background: rgba(245, 108, 108, 0.15);
  color: var(--color-danger);
}

.verdict-empty {
  background: rgba(144, 147, 153, 0.15);
  color: var(--color-info);
}

.muted {
  color: var(--text-muted);
  font-size: 12px;
  font-style: italic;
}

.badge {
  margin-left: 6px;
  padding: 1px 6px;
  background: rgba(230, 162, 60, 0.15);
  color: var(--color-warning);
  border-radius: 3px;
  font-size: 10px;
  font-weight: 500;
}
</style>
