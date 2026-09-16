<script setup lang="ts">
/**
 * 规划结果人工评审面板（阶段 2）。
 *
 * ## 为什么要在「动手之前」评审
 * 等模型把文件都改完再让人审 diff，纠错成本已经付出去了（token 花完、代码改乱）。
 * 让他在 PLAN 之后先看一眼「打算改哪些文件、为什么」——此时拒绝的代价是零：
 * 一个 LLM 调用而已。这是本面板存在的全部理由。
 *
 * ## 按钮由「任务状态」驱动，而不是「计划有没有解析出来」
 * 计划 JSON 万一读不出来（老任务、格式漂移），评审这件事依然需要用户处理。
 * 若按钮跟着数据走，用户会看到一个卡在 WAITING_HUMAN、却没有任何操作入口的页面 ——
 * 那是最糟的状态：既不前进也不报错。所以 `awaiting` 只看任务状态。
 *
 * ## 但 WAITING_HUMAN 是共用的，必须让位给 GATE
 * 阶段 3 起 `WAITING_HUMAN` 同时表示「等规划评审」和「等一道人工门禁」。若这里只看状态，
 * GATE 挂起时本面板会挂出「批准并继续执行」，而它打的是 `/plan/approve` —— 后端回 409，
 * 用户看到一个点了没反应的按钮。所以 `awaiting` 额外要求<b>当前没有等待中的门禁</b>
 * （由 `gateOpen` 传入，与 store 的 `awaitingPlanReview` 同判据）。
 */
import { computed, ref } from 'vue'

import type { PlanView, TaskStatus } from '@/api/types'

interface Props {
  plan: PlanView | null
  status: TaskStatus
  /** 请求进行中 —— 用于禁用按钮，避免连点造成重复入队。 */
  busy: boolean
  error: string | null
  /** 是否有一道等待中的 GATE 门禁挡路；为 true 时本面板只展示计划、不给操作按钮。 */
  gateOpen?: boolean
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (event: 'approve'): void
  (event: 'reject', reason: string): void
}>()

const rejecting = ref(false)
const reason = ref('')

const awaiting = computed(() => props.status === 'WAITING_HUMAN' && !props.gateOpen)

/** 有内容可展示：有计划，或正在等待评审（此时即使计划缺失也要给操作入口）。 */
const visible = computed(() => props.plan !== null || awaiting.value)

function startReject(): void {
  rejecting.value = true
  reason.value = ''
}

function cancelReject(): void {
  rejecting.value = false
  reason.value = ''
}

function confirmReject(): void {
  emit('reject', reason.value.trim())
  rejecting.value = false
}

function shortPath(filePath: string): string {
  return filePath.split(/[\\/]/).slice(-2).join('/')
}
</script>

<template>
  <div v-if="visible" class="card plan-card">
    <div class="plan-head">
      <div class="section-title" style="margin: 0">迁移计划</div>
      <el-tag v-if="plan?.approved" type="success" size="small">已批准</el-tag>
      <el-tag v-else-if="awaiting" type="warning" size="small">待评审</el-tag>
    </div>

    <p v-if="plan?.summary" class="plan-summary">{{ plan.summary }}</p>

    <div v-if="plan && plan.steps.length > 0" class="plan-steps">
      <div v-for="(step, index) in plan.steps" :key="step.filePath" class="plan-step">
        <span class="step-index">{{ index + 1 }}</span>
        <div class="step-body">
          <div class="step-path" :title="step.filePath">{{ shortPath(step.filePath) }}</div>
          <div v-if="step.rationale" class="step-rationale">{{ step.rationale }}</div>
          <div v-else class="step-rationale muted">（模型未给出理由）</div>
        </div>
      </div>
    </div>

    <div v-else-if="awaiting" class="empty-hint">
      计划内容未能解析出来，但任务确实在等待评审 —— 仍可批准或驳回。
    </div>

    <div v-if="awaiting" class="plan-actions">
      <template v-if="!rejecting">
        <el-button type="primary" :disabled="busy" @click="emit('approve')">
          {{ busy ? '处理中…' : '批准并继续执行' }}
        </el-button>
        <el-button :disabled="busy" @click="startReject">驳回</el-button>
      </template>

      <template v-else>
        <el-input
          v-model="reason"
          size="small"
          placeholder="驳回理由（可选，会记进任务的失败原因）"
          class="reject-input"
          @keyup.enter="confirmReject"
        />
        <el-button type="danger" size="small" :disabled="busy" @click="confirmReject">
          确认驳回
        </el-button>
        <el-button size="small" :disabled="busy" @click="cancelReject">取消</el-button>
      </template>
    </div>

    <div v-if="error" class="plan-error">{{ error }}</div>
  </div>
</template>

<style scoped>
.plan-card {
  border-left: 3px solid var(--color-warning);
}

.plan-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.plan-summary {
  margin: 10px 0 12px;
  font-size: 13px;
  line-height: 1.6;
}

.plan-steps {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.plan-step {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 8px 10px;
  border: 1px solid var(--border-soft);
  border-radius: 4px;
}

.step-index {
  flex: 0 0 20px;
  height: 20px;
  line-height: 20px;
  text-align: center;
  border-radius: 50%;
  background: var(--bg-page);
  color: var(--text-muted);
  font-size: 11px;
}

.step-body {
  min-width: 0;
  flex: 1;
}

.step-path {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 12px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.step-rationale {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
}

.step-rationale.muted {
  opacity: 0.7;
}

.empty-hint {
  font-size: 12px;
  color: var(--text-muted);
  padding: 8px 0;
}

.plan-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px dashed var(--border-soft);
}

.reject-input {
  max-width: 320px;
}

.plan-error {
  margin-top: 10px;
  font-size: 12px;
  color: var(--color-danger);
}
</style>
