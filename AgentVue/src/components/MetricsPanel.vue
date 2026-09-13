<script setup lang="ts">
/**
 * 量化指标面板。
 *
 * 显示：编译/单测通过率、覆盖率、LLM 调用次数与花费、attempt 数、单轮总时长。
 *
 * 设计：把指标分成"是否通过"与"花了多少"两组 —— 前者决定任务状态，后者决定成本治理。
 * 用 el-progress 既能表达百分比又能一眼看出是否 100%/0% 这两种"绝对值"。
 */
import { computed } from 'vue'

import type { Metrics } from '@/api/types'
import { formatCost, formatDuration } from '@/utils/status'

interface Props {
  metrics: Metrics | null
  taskTitle?: string
}

const props = defineProps<Props>()

const hasMetrics = computed(() => props.metrics !== null)

const compilePassRate = computed(() => {
  const m = props.metrics
  if (!m || m.compilePassRate == null) return 0
  return Math.round(m.compilePassRate * 100)
})

const testPassRate = computed(() => {
  const m = props.metrics
  if (!m || m.testPassRate == null) return 0
  return Math.round(m.testPassRate * 100)
})

const coveragePct = computed(() => {
  const m = props.metrics
  if (!m || m.coverage == null || m.coverage < 0) return null
  return Math.round(m.coverage * 100)
})

const isCoverageCollected = computed(() => coveragePct.value !== null)
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
        <el-progress
          :percentage="compilePassRate"
          :status="compilePassRate === 100 ? 'success' : 'exception'"
          :stroke-width="14"
        />
        <div class="small">{{ props.metrics?.compilePassed }}/{{ props.metrics?.filesTotal }} 个文件</div>
      </div>

      <div class="metric">
        <div class="label">单测通过率</div>
        <el-progress
          :percentage="testPassRate"
          :status="testPassRate === 100 && (props.metrics?.testsTotal ?? 0) > 0 ? 'success' : 'exception'"
          :stroke-width="14"
        />
        <div class="small">{{ props.metrics?.testsPassed }}/{{ props.metrics?.testsTotal }} 条用例</div>
      </div>

      <div class="metric">
        <div class="label">行覆盖率</div>
        <el-progress
          v-if="isCoverageCollected"
          :percentage="coveragePct!"
          :stroke-width="14"
        />
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
          <span v-if="props.metrics?.retried" class="badge">已回退</span>
        </div>
      </div>

      <div class="metric">
        <div class="label">总耗时</div>
        <div class="big">{{ formatDuration(props.metrics?.durationMs) }}</div>
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
