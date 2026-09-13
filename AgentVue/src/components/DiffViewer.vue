<script setup lang="ts">
/**
 * Monaco Diff 编辑器 —— 展示补丁（patches）。
 *
 * ## 为什么 monaco 是动态 import
 *
 * monaco-editor 主包约 5 MB / 300+ 模块。若在组件顶层静态 import，它会成为首屏同步
 * 依赖的一部分 —— 用户还没看任何补丁，就要先等这个巨包加载完，页面白屏十几秒。
 *
 * 改成动态 `import('monaco-editor')` 后：主包被切成独立 chunk，只有当用户真的切到
 * 含补丁的任务时才加载。同时这段异步逻辑天然兼容「组件在加载途中被卸载」——
 * 用 disposed 标志位兜住，避免往已经销毁的 DOM 上挂编辑器。
 *
 * ## 渲染策略
 *
 * 后端 Patch.diff 是 unified diff 字符串（java-diff-utils 生成），不是完整文件。
 * 这里把 diff 按行首符号（'-' / '+' / ' '）拆成「删前 / 增后」两个文本喂给
 * DiffEditor 的 original / modified 两侧。不追求完美重建完整文件（那要处理 hunk
 * 上下文合并，代码量翻倍）——用户核心诉求是「看清哪些行被改了」，这个拆分已满足。
 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

import type { Patch } from '@/api/types'

interface Props {
  patches: Patch[]
}

const props = defineProps<Props>()

const containerRef = ref<HTMLDivElement | null>(null)
const selectedPatchIdx = ref(0)
const loading = ref(false)
const loadError = ref<string | null>(null)

/** monaco 命名空间的类型只用于本地变量标注，延迟到运行时才有值。 */
type Monaco = typeof import('monaco-editor')

let monacoRef: Monaco | null = null
let diffEditor: import('monaco-editor').editor.IStandaloneDiffEditor | null = null
/** 组件是否已被卸载 —— 异步加载完成后据此决定还要不要建编辑器。 */
let disposed = false

function splitDiff(diff: string): { before: string; after: string } {
  const lines = diff.split(/\r?\n/)
  const before: string[] = []
  const after: string[] = []
  for (const raw of lines) {
    if (raw.startsWith('---') || raw.startsWith('+++') || raw.startsWith('@@')) {
      // hunk 头/文件头两侧都留，保住「这是哪个文件、哪一段」的上下文
      before.push(raw)
      after.push(raw)
      continue
    }
    if (raw.startsWith('-')) {
      before.push(raw.slice(1))
    } else if (raw.startsWith('+')) {
      after.push(raw.slice(1))
    } else if (raw.startsWith(' ')) {
      before.push(raw.slice(1))
      after.push(raw.slice(1))
    }
  }
  return { before: before.join('\n'), after: after.join('\n') }
}

function disposeModels(): void {
  if (!diffEditor) return
  const m = diffEditor.getModel()
  if (m) {
    m.original.dispose()
    m.modified.dispose()
  }
}

function selectPatch(idx: number): void {
  const monaco = monacoRef
  if (!diffEditor || !monaco) return
  disposeModels()
  const patch = props.patches[idx]
  if (!patch) return
  const { before, after } = splitDiff(patch.diff)
  const base = monaco.Uri.parse('inmemory://patches')
  const left = monaco.editor.createModel(
    before,
    'java',
    base.with({ path: `${base.path}/${patch.filePath}.before` }),
  )
  const right = monaco.editor.createModel(
    after,
    'java',
    base.with({ path: `${base.path}/${patch.filePath}` }),
  )
  diffEditor.setModel({ original: left, modified: right })
}

async function ensureEditor(): Promise<void> {
  if (diffEditor || disposed) return
  if (!containerRef.value) return
  loading.value = true
  loadError.value = null
  try {
    const monaco = await import('monaco-editor')
    if (disposed || !containerRef.value) return
    monacoRef = monaco
    diffEditor = monaco.editor.createDiffEditor(containerRef.value, {
      automaticLayout: true,
      readOnly: true,
      renderSideBySide: true,
      originalEditable: false,
      enableSplitViewResizing: false,
      fontSize: 13,
      minimap: { enabled: false },
      scrollBeyondLastLine: false,
      ignoreTrimWhitespace: false,
    })
    if (props.patches.length > 0) {
      selectPatch(0)
    }
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (props.patches.length > 0) {
    void ensureEditor()
  }
})

watch(
  () => props.patches,
  () => {
    selectedPatchIdx.value = 0
    if (props.patches.length > 0) {
      void ensureEditor().then(() => selectPatch(0))
    }
  },
)

onBeforeUnmount(() => {
  disposed = true
  disposeModels()
  diffEditor?.dispose()
  diffEditor = null
  monacoRef = null
})
</script>

<template>
  <div>
    <div class="section-title">代码补丁</div>
    <div v-if="patches.length === 0" class="empty-state">
      <p>该任务没有生成补丁（可能仍在分析阶段，或任务失败）</p>
    </div>
    <div v-else>
      <el-radio-group v-model="selectedPatchIdx" class="patch-tabs" size="small">
        <el-radio-button
          v-for="(p, i) in patches"
          :key="i"
          :label="i"
          @click="selectPatch(i)"
        >
          {{ p.filePath.split('/').pop() }}
        </el-radio-button>
      </el-radio-group>
      <div v-if="loadError" class="error-banner">
        编辑器加载失败：{{ loadError }}
      </div>
      <div
        v-loading="loading"
        ref="containerRef"
        class="monaco-diff-container"
        style="height: 480px; margin-top: 12px"
      ></div>
    </div>
  </div>
</template>

<style scoped>
.patch-tabs {
  margin-bottom: 8px;
}

.patch-tabs :deep(.el-radio-button__inner) {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.error-banner {
  padding: 8px 12px;
  background: rgba(245, 108, 108, 0.1);
  color: var(--color-danger);
  border-radius: 4px;
  font-size: 12px;
  margin-bottom: 8px;
}
</style>
