<script setup lang="ts">
/**
 * 任务表单 —— 同一组件两种模式，由 store 的 `formMode` 切换：
 *
 * - `create`（新建迁移任务页面）：任务名 + 工程根 / 入口文件 + 目标 JDK，字段可编辑，带提交按钮。
 * - `view`  （迁移任务详情页面）：回填当前任务信息、全部只读、隐藏提交按钮，
 *           目标 JDK 显示任务实际值（不再给多选）。由左侧列表点任务进入。
 *
 * 「任务名」在建单页是**手动输入**的文本框（便于在列表/详情里区分任务），
 * 不再是从评测集 catalog 派生的「预设样例工程」下拉 —— 下拉只反映少量样例，
 * 而任务是用户自己起的名字。任务名会随建单请求一起落库。
 *
 * 重要约束：
 * - `projectRoot` 必须是**绝对路径**（后端 ProjectPathValidator 强制要求）：
 *   既防穿越，又让 API 进程 / Worker 进程在不同 cwd 下都能解析到同一份仓库。
 */
import { computed, reactive, ref } from 'vue'

import type { CreateTaskRequest } from '@/api/types'
import { useTasksStore } from '@/stores/tasks'
import { ElMessage } from 'element-plus'

const tasks = useTasksStore()

/** 当前模式：create = 新建页，view = 详情页。 */
const mode = computed<'create' | 'view'>(() => tasks.formMode)

interface FormState {
  name: string
  projectRoot: string
  entryFile: string
  targetJdk: number
}

const form = reactive<FormState>({
  name: '',
  projectRoot: '',
  entryFile: '',
  targetJdk: 21,
})

/** 查看模式下，直接读当前任务的信息（只读、随详情加载自动填充）。 */
const viewTask = computed(() => tasks.currentDetail?.task ?? null)
const viewName = computed(() => viewTask.value?.name ?? '')
const viewProjectRoot = computed(() => viewTask.value?.projectRoot ?? '')
/**
 * 查看模式下的入口文件展示值。
 *
 * 留空不是「没填」，而是一种任务形态：整仓升级（只抬全仓 pom 的编译级别，不改代码）。
 * 直接渲染成空白会让人以为数据缺失，所以这里换成一句明确的说明。
 */
const viewEntryFileText = computed(
  () => viewTask.value?.entryFile || '（整仓升级：只升编译级别，不改代码）',
)
const viewTargetJdk = computed(() => viewTask.value?.targetJdk ?? 21)

/** 表单自检 —— 给用户看的，不替后端做决定。仅在 create 模式使用。 */
const projectRootError = ref<string | null>(null)
const entryFileError = ref<string | null>(null)

function validate(): boolean {
  projectRootError.value = null
  entryFileError.value = null
  let ok = true
  if (!form.projectRoot.trim()) {
    projectRootError.value = '请填写项目根路径'
    ok = false
  } else if (!/^[a-zA-Z]:[\\/]/.test(form.projectRoot) && !form.projectRoot.startsWith('/')) {
    projectRootError.value = '看起来不是绝对路径——Windows 用 "E:/..."，Linux/Mac 用 "/..."'
    ok = false
  }
  // 入口文件可以留空 —— 那是「整仓升级」模式，所以不再要求必填，
  // 只校验「填了的话格式对不对」。留空与填错是两件事，不能合成一条错误提示
  if (form.entryFile.trim() && form.entryFile.includes('\\')) {
    entryFileError.value = '请用正斜杠分隔路径，例如 src/main/java/...'
    ok = false
  }
  return ok
}

async function onSubmit(): Promise<void> {
  if (!validate()) return
  const name = form.name.trim()
  const entryFile = form.entryFile.trim()
  const req: CreateTaskRequest = {
    projectRoot: form.projectRoot.trim(),
    targetJdk: form.targetJdk,
    // 留空就不下发这个字段：后端存 null，编排层据此铺「POM_REWRITE → VERIFY」，
    // 一步代码改写都不做。下发空串则会被后端当成「填了个空路径」而报错
    ...(entryFile ? { entryFile } : {}),
    // 留空则不下发该字段（后端存 null，列表回退显示 #id）
    ...(name ? { name } : {}),
  }
  const created = await tasks.submit(req)
  if (created) {
    ElMessage.success(`已提交任务 #${created.id}`)
  } else if (tasks.submitError) {
    ElMessage.error(tasks.submitError)
  }
}
</script>

<template>
  <div class="card">
    <!-- 查看任务时标题改为「迁移任务」，且不再带「新建」语气 -->
    <div class="section-title">
      {{ mode === 'create' ? '新建迁移任务' : '迁移任务' }}
    </div>

    <el-form label-position="top" @submit.prevent="onSubmit">
      <el-form-item label="任务名">
        <el-input
          v-if="mode === 'create'"
          v-model="form.name"
          placeholder="给这次迁移起个名字，例如：billing 模块 JDK21 迁移"
          maxlength="100"
          clearable
        />
        <!-- 查看模式：只读展示任务名；无名字时明确写「未命名」而不是留空 -->
        <el-input
          v-else
          :model-value="viewName"
          :placeholder="viewTask && !viewName ? '（未命名）' : ''"
          disabled
        />
      </el-form-item>

      <el-form-item
        label="项目根目录（绝对路径）"
        :error="mode === 'create' ? (projectRootError ?? undefined) : undefined"
      >
        <el-input
          v-if="mode === 'create'"
          v-model="form.projectRoot"
          placeholder="E:/.../your-project"
        />
        <!-- 查看模式：只读展示任务实际信息 -->
        <el-input v-else :model-value="viewProjectRoot" disabled />
      </el-form-item>

      <el-form-item
        :label="mode === 'create' ? '入口文件（相对项目根，可留空）' : '入口文件（相对项目根）'"
        :error="mode === 'create' ? (entryFileError ?? undefined) : undefined"
      >
        <el-input
          v-if="mode === 'create'"
          v-model="form.entryFile"
          placeholder="src/main/java/com/example/Foo.java"
          clearable
        />
        <el-input v-else :model-value="viewEntryFileText" disabled />
        <!-- 留空的语义必须显式写出来：它不是「这个字段没填」，而是另一种任务形态 -->
        <div v-if="mode === 'create'" class="form-hint">
          留空 = 整仓升级：只把全仓 pom.xml 的编译级别抬到目标 JDK，不改任何代码
        </div>
      </el-form-item>

      <el-form-item label="目标 JDK">
        <template v-if="mode === 'create'">
          <el-radio-group v-model="form.targetJdk">
            <el-radio :value="17">17</el-radio>
            <el-radio :value="21">21</el-radio>
          </el-radio-group>
        </template>
        <!-- 查看模式：显示任务实际目标 JDK，不给多选、不可改 -->
        <template v-else>
          <el-radio-group :model-value="viewTargetJdk" disabled>
            <el-radio :value="viewTargetJdk">{{ viewTargetJdk }}</el-radio>
          </el-radio-group>
        </template>
      </el-form-item>

      <!-- 提交按钮：仅新建模式出现 -->
      <el-button
        v-if="mode === 'create'"
        type="primary"
        :loading="tasks.submitting"
        :disabled="tasks.currentStatus === 'RUNNING' || tasks.submitting"
        @click="onSubmit"
      >
        {{ tasks.currentStatus === 'RUNNING' ? '任务运行中…' : '提交任务' }}
      </el-button>
    </el-form>
  </div>
</template>

<style scoped>
.form-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
</style>
