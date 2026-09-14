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
/**
 * 正在进行的「加载 monaco + 创建编辑器」任务。用来防重入：onMounted 与
 * props.patches 的 watch 可能几乎同时触发 ensureEditor，各自都会 `await import`
 * （异步），在 diffEditor 真正被赋值前都绕过了 `if (diffEditor) return` 的闸门，
 * 于是创建出两个 editor、继而 selectPatch 对同一 URI 重复 createModel 报
 * "Cannot add model because it already exists!"。复用同一个 in-flight promise，
 * 并发调用只会真正执行一次。
 */
let editorLoadingPromise: Promise<void> | null = null

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

/**
 * 补丁标签文案。
 *
 * 回退重写会让**同一个文件**产生多份补丁（每轮一份），只按文件名会出现两个一模一样的
 * 标签，分不清哪份是哪轮。所以同一文件出现多份时补上轮次 —— 这也正好让
 * 「模型第二次改了什么」这件事在界面上看得见，是回退机制的价值所在。
 * 只有一份时不加，免得给绝大多数正常任务平添噪音。
 */
function tabLabel(patch: Patch, index: number): string {
  const name = patch.filePath.split('/').pop() ?? patch.filePath
  const duplicated = props.patches.some((p, i) => i !== index && p.filePath === patch.filePath)
  return duplicated ? `${name} · 第 ${patch.attempt + 1} 轮` : name
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
      selectPatch(selectedPatchIdx.value)
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
  (next, prev) => {
    // 详情会在节点到达终态时被重拉（补丁随之更新），所以这个 watch 现在会被频繁触发。
    // 不能无条件把选中项重置回第 0 份 —— 用户正看第 2 轮补丁时，一次自动刷新就把它弹走，
    // 等于边看边被人翻页。按 nodeId 找回原来那一份；真没了（换了任务）才退回第一份。
    const previousNodeId = prev?.[selectedPatchIdx.value]?.nodeId
    const keptIndex =
      previousNodeId === undefined ? -1 : next.findIndex((p) => p.nodeId === previousNodeId)
    selectedPatchIdx.value = keptIndex >= 0 ? keptIndex : 0
    if (props.patches.length > 0) {
      void ensureEditor().then(() => selectPatch(selectedPatchIdx.value))
    }
  },
)

onBeforeUnmount(() => {
  disposed = true
  disposeModels()
  diffEditor?.dispose()
  diffEditor = null
  monacoRef = null
  editorLoadingPromise = null
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
          :key="p.nodeId"
          :label="i"
          @click="selectPatch(i)"
        >
          {{ tabLabel(p, i) }}
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
