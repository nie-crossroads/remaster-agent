<script setup lang="ts">
/**
 * 变更回写面板 —— 「把沙箱里验证过的产出落回源工程」。
 *
 * ## 这块卡片为什么必须存在，而不是把按钮挂在标题栏上
 *
 * 回写是本项目里**唯一会改动用户原有文件**的动作。整条链路里源工程一直是只读的
 * （索引、检索、复制源三处读路径之外没有任何写入口），这不是疏漏，而是
 * 「模型改坏代码」与「人还需要原始版本」之间那道安全边界的全部。
 *
 * 所以这个动作不能是一个和其他按钮长得一样的按钮：
 * ① 它按危险动作配色（`type="danger"`）；
 * ② 点之前必须先看见**清单**（写哪几个文件、写到哪个目录、工作区干不干净）；
 * ③ 写完要留下可复查的痕迹（文件 + 备份目录 + 指纹）。
 * 三条都需要地方放，所以它是一张卡片而不是一个按钮。
 *
 * ## 两个状态，两张脸
 *
 * - `report` 非空 → 显示「将要做什么」以及拦路项（这是点击后的预检结果）；
 * - `applied`（详情里带回来的留痕）非空 → 显示「已经做了什么」，含备份目录。
 *
 * 二者可以同时存在（刚写完就还开着预检报告），此时以「已写回」为主、清单退到下方。
 */
import { computed } from 'vue'

import type { WriteBackReport, WriteBackView } from '@/api/types'
import { formatBytes, isWriteBackApplied, isWriteBackReady, shortHash } from '@/utils/writeback'

const props = defineProps<{
  /** 预检 / 执行结果；null = 还没查过。 */
  report: WriteBackReport | null
  /** 详情里带回来的「已回写」留痕；null = 从没回写过。 */
  applied: WriteBackView | null
  /** 任务是否处于可回写状态（SUCCEEDED）—— 与后端预检第一条同源。 */
  canWriteBack: boolean
  /** 预检请求在途。 */
  loading: boolean
  /** 回写动作在途。 */
  applying: boolean
  error: string | null
}>()

const emit = defineEmits<{
  (e: 'preflight'): void
  (e: 'apply'): void
  (e: 'dismiss'): void
}>()

const ready = computed(() => isWriteBackReady(props.report))
const appliedNow = computed(() => isWriteBackApplied(props.report))

/** 时间戳渲染成本地时间；非法值原样显示，不编造一个「刚刚」。 */
function formatTime(value: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}
</script>

<template>
  <div class="card">
    <div class="wb-head">
      <div class="section-title">变更回写</div>
      <div class="wb-actions">
        <el-button
          v-if="canWriteBack"
          type="danger"
          :loading="loading"
          :disabled="applying"
          @click="emit('preflight')"
        >
          应用到源工程
        </el-button>
      </div>
    </div>

    <p class="wb-intro">
      把沙箱里<b>已经通过编译与单测</b>的产出写回你的源工程。
      只写本地文件 —— <b>不会</b>替你 commit，更不会 push；建议的提交信息会列在下面，由你决定用不用。
      写入前会把原件备份一份，出事可以整棵拷回去。
    </p>

    <!-- 任务还没成功：先把原因说清楚，别让人以为按钮坏了 -->
    <div v-if="!canWriteBack" class="wb-muted">
      只有<b>已完成</b>（SUCCEEDED）的任务才能回写 —— 没有通过编译与单测的产出不该进源工程。
    </div>

    <!-- 从没回写过、也还没点过按钮：给一句安心的说明 -->
    <div v-else-if="!report && !applied" class="wb-muted">
      点上面的按钮会先做一次<b>预检</b>：列出将要写入的文件、以及有没有拦路的事
      （比如源工程工作区不干净）。预检本身不写任何东西，确认之后才会真正落盘。
    </div>

    <div v-if="error" class="wb-error">{{ error }}</div>

    <!-- ============ 已写回留痕（详情带回来的事实） ============ -->
    <div v-if="applied && !appliedNow" class="wb-applied">
      <div class="wb-applied-head">
        ✅ 已于 {{ formatTime(applied.appliedAt) }} 回写 {{ applied.fileCount }} 个文件
      </div>
      <div class="wb-kv">
        <span class="wb-key">源工程</span>
        <span class="wb-val mono">{{ applied.projectRoot ?? '—' }}</span>
      </div>
      <div class="wb-kv">
        <span class="wb-key">备份目录</span>
        <span class="wb-val mono">{{ applied.backupDir ?? '—' }}</span>
      </div>
      <div v-if="applied.files.length" class="wb-files">
        <div v-for="file in applied.files" :key="file.filePath" class="wb-file">
          <span class="mono wb-path">{{ file.filePath }}</span>
          <span class="wb-size">{{ formatBytes(file.bytes) }}</span>
          <span class="mono wb-hash" title="写回后内容的 sha256 —— 可核对磁盘上现在这份是不是当时写的那份">
            {{ shortHash(file.sha256) }}
          </span>
        </div>
      </div>
    </div>

    <!-- ============ 预检 / 执行结果 ============ -->
    <template v-if="report">
      <div v-if="appliedNow" class="wb-applied">
        <div class="wb-applied-head">
          ✅ 已回写 {{ report.files.length }} 个文件到源工程
        </div>
        <div class="wb-kv">
          <span class="wb-key">备份目录</span>
          <span class="wb-val mono">{{ report.backupDir ?? '—' }}</span>
        </div>
      </div>

      <!-- 拦路项：这些必须先解决，确认按钮不会亮 -->
      <div v-if="!ready" class="wb-blocked">
        <div class="wb-blocked-head">⛔ 预检未通过，不会写入任何文件</div>
        <ul class="wb-blocked-list">
          <li v-for="item in report.blocked" :key="item.code">
            <code class="wb-code">{{ item.code }}</code>
            <span>{{ item.message }}</span>
          </li>
        </ul>
      </div>

      <template v-else>
        <div class="wb-checklist-head">
          预检通过：将写入 <b>{{ report.files.length }}</b> 个文件到
          <span class="mono">{{ report.projectRoot ?? '—' }}</span>
        </div>

        <div class="wb-files">
          <div v-for="file in report.files" :key="file.filePath" class="wb-file">
            <span class="mono wb-path">{{ file.filePath }}</span>
            <span class="wb-size">{{ formatBytes(file.bytes) }}</span>
            <el-tag v-if="!file.baseOk" type="danger" size="small">基线已变</el-tag>
          </div>
        </div>

        <div v-if="report.blocked.length === 0 && !appliedNow" class="wb-confirm-row">
          <el-button type="danger" :loading="applying" :disabled="loading" @click="emit('apply')">
            确认写入这 {{ report.files.length }} 个文件
          </el-button>
          <el-button plain :disabled="applying" @click="emit('dismiss')">取消</el-button>
        </div>
      </template>

      <!-- 警告：放行但不放心 -->
      <ul v-if="report.warnings.length" class="wb-warnings">
        <li v-for="(warning, index) in report.warnings" :key="index">⚠️ {{ warning }}</li>
      </ul>

      <!-- 建议提交信息：给一段现成的，但不替人提交 -->
      <div v-if="report.suggestedCommitMessage" class="wb-commit">
        <div class="wb-commit-head">
          建议的提交信息（<b>不会</b>自动提交，需要你自己 git commit）
        </div>
        <pre class="wb-commit-body">{{ report.suggestedCommitMessage }}</pre>
      </div>
    </template>
  </div>
</template>

<style scoped>
.wb-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.wb-actions {
  display: flex;
  gap: 6px;
  flex: 0 0 auto;
}

.wb-intro {
  margin: 8px 0 10px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-muted);
}

.wb-muted {
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-muted);
}

.wb-error {
  margin-top: 8px;
  font-size: 12px;
  color: var(--color-danger);
}

/* 已写回：绿框而不是红框 —— 它是结果，不是警告 */
.wb-applied {
  margin-top: 10px;
  padding: 10px 12px;
  border-left: 3px solid var(--color-success);
  background: rgba(103, 194, 58, 0.06);
  border-radius: 3px;
  font-size: 12px;
}

.wb-applied-head {
  font-weight: 600;
  margin-bottom: 6px;
  color: var(--color-success);
}

/* 拦路项：红框，且明确说「不会写入任何文件」 */
.wb-blocked {
  margin-top: 10px;
  padding: 10px 12px;
  border-left: 3px solid var(--color-danger);
  background: rgba(245, 108, 108, 0.06);
  border-radius: 3px;
  font-size: 12px;
}

.wb-blocked-head {
  font-weight: 600;
  color: var(--color-danger);
  margin-bottom: 6px;
}

.wb-blocked-list {
  margin: 0;
  padding-left: 18px;
  line-height: 1.8;
}

.wb-code {
  margin-right: 6px;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 11px;
  color: var(--text-muted);
}

.wb-checklist-head {
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.7;
}

.wb-kv {
  display: flex;
  gap: 8px;
  line-height: 1.8;
}

.wb-key {
  flex: 0 0 60px;
  color: var(--text-muted);
}

.wb-val {
  word-break: break-all;
}

.wb-files {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.wb-file {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  padding: 3px 0;
}

.wb-path {
  flex: 1 1 auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wb-size {
  flex: 0 0 auto;
  color: var(--text-muted);
}

.wb-hash {
  flex: 0 0 auto;
  font-size: 11px;
  color: var(--text-muted);
}

.wb-confirm-row {
  margin-top: 12px;
  display: flex;
  gap: 8px;
}

/* 间距只由 gap 决定：Element Plus 会给相邻 el-button 再补一层 margin-left */
.wb-confirm-row :deep(.el-button + .el-button),
.wb-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.wb-warnings {
  margin: 10px 0 0;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.8;
  color: var(--color-warning);
}

.wb-commit {
  margin-top: 12px;
}

.wb-commit-head {
  font-size: 12px;
  color: var(--text-muted);
  margin-bottom: 6px;
}

.wb-commit-body {
  margin: 0;
  padding: 10px 12px;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 11px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--bg-soft, rgba(127, 127, 127, 0.08));
  border-radius: 3px;
}

.mono {
  font-family: 'SFMono-Regular', Consolas, monospace;
}
</style>
