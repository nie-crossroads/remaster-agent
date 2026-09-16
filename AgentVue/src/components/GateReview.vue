<script setup lang="ts">
/**
 * 人工门禁评审卡片（阶段 3「通用人在回路」）。
 *
 * ## 它和「迁移计划评审」是什么关系
 * 两者都是「人在回路」，但触发点不同：
 * - **计划评审**（PlanReview）在 PLAN 之后 —— 动手前看一眼「打算改哪些文件」，拒绝代价为零；
 * - **门禁评审**（本组件）在 REWRITE 之后、VERIFY 之前 —— 改完看一眼「这批补丁靠不靠谱」，
 *   再决定要不要花一次沙箱构建去验证它。
 *
 * 所以本卡片的落点正是「已经花了改写成本、但还没花验证成本」的那个决策点 ——
 * 在这里拦下，省掉的是一次完整的编译 + 跑测试。
 *
 * ## 数据来源
 * 由后端 `TaskDetail.gate` 驱动：它非空 = 确实有一道等待中的门禁（human_gate 行 PENDING）。
 * 批准 / 驳回走 `POST /api/tasks/{id}/gate/approve | reject`，与计划评审同一套交互。
 */
import { computed, ref } from 'vue'

import type { GateView } from '@/api/types'

interface Props {
  gate: GateView | null
  /** 请求进行中 —— 用于禁用按钮，避免连点造成重复入队。 */
  busy: boolean
  error: string | null
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (event: 'approve', reviewer?: string): void
  (event: 'reject', comment?: string, reviewer?: string): void
}>()

const rejecting = ref(false)
const reviewer = ref('')
const reason = ref('')

const visible = computed(() => props.gate !== null)

function startReject(): void {
  rejecting.value = true
  reason.value = ''
}

function cancelReject(): void {
  rejecting.value = false
  reason.value = ''
}

function confirmReject(): void {
  emit('reject', reason.value.trim() || undefined, reviewer.value.trim() || undefined)
  rejecting.value = false
}

function approve(): void {
  emit('approve', reviewer.value.trim() || undefined)
}

function shortPath(filePath: string): string {
  return filePath.split(/[\\/]/).slice(-2).join('/')
}
</script>

<template>
  <div v-if="visible && gate" class="card gate-card">
    <div class="gate-head">
      <div class="section-title" style="margin: 0">人工门禁</div>
      <el-tag type="warning" size="small">待审批</el-tag>
    </div>

    <p class="gate-desc">{{ gate.comment || '改写已完成，等待人工确认后继续验证。' }}</p>

    <div v-if="gate.filePath" class="gate-file">
      <span class="gate-file-label">待确认文件</span>
      <span class="gate-file-path" :title="gate.filePath">{{ shortPath(gate.filePath) }}</span>
    </div>

    <div class="gate-hint">
      补丁见下方「代码补丁」卡片。确认无误后批准，任务将继续进行沙箱验证；驳回则任务就此失败。
    </div>

    <div class="gate-actions">
      <template v-if="!rejecting">
        <el-input
          v-model="reviewer"
          size="small"
          placeholder="审批人（可选）"
          class="gate-input"
          @keyup.enter="approve"
        />
        <el-button type="primary" :disabled="busy" @click="approve">
          {{ busy ? '处理中…' : '批准并继续' }}
        </el-button>
        <el-button :disabled="busy" @click="startReject">驳回</el-button>
      </template>

      <template v-else>
        <el-input
          v-model="reason"
          size="small"
          placeholder="驳回理由（可选，会记进任务的失败原因）"
          class="gate-input reason"
          @keyup.enter="confirmReject"
        />
        <el-button type="danger" size="small" :disabled="busy" @click="confirmReject">
          确认驳回
        </el-button>
        <el-button size="small" :disabled="busy" @click="cancelReject">取消</el-button>
      </template>
    </div>

    <div v-if="error" class="gate-error">{{ error }}</div>
  </div>
</template>

<style scoped>
.gate-card {
  border-left: 3px solid var(--color-warning);
}

.gate-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.gate-desc {
  margin: 10px 0 12px;
  font-size: 13px;
  line-height: 1.6;
}

.gate-file {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--border-soft);
  border-radius: 4px;
}

.gate-file-label {
  flex: 0 0 auto;
  font-size: 11px;
  color: var(--text-muted);
}

.gate-file-path {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 12px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.gate-hint {
  margin-top: 10px;
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
}

.gate-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px dashed var(--border-soft);
}

.gate-input {
  max-width: 200px;
}

.gate-input.reason {
  max-width: 320px;
}

.gate-error {
  margin-top: 10px;
  font-size: 12px;
  color: var(--color-danger);
}
</style>
