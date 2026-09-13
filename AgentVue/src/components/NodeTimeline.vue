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
 */
import { computed } from 'vue'

import type { DagNode, VerifyResult } from '@/api/types'
import {
  NODE_STATUS_LABEL,
  NODE_STATUS_TAG,
  NODE_TYPE_EMOJI,
  NODE_TYPE_LABEL,
  formatCost,
  formatCoverage,
  formatDuration,
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
          <span class="attempt">attempt {{ node.attempt }}</span>
          <el-tag :type="NODE_STATUS_TAG[node.status]" size="small">
            {{ NODE_STATUS_LABEL[node.status] }}
          </el-tag>
        </div>

        <div class="node-meta">
          <span>{{ formatDuration(node.finishedAt && node.startedAt ? new Date(node.finishedAt).getTime() - new Date(node.startedAt).getTime() : null) }}</span>
          <span v-if="node.error" class="error">{{ node.error }}</span>
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
}

.node-meta {
  margin-top: 6px;
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: var(--text-muted);
}

.error {
  color: var(--color-danger);
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
