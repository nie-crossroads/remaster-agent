<script setup lang="ts">
/**
 * 提单表单：项目根 / 入口文件 / 目标 JDK。
 *
 * 重要约束：
 * - `projectRoot` 必须是**绝对路径**（后端 ProjectPathValidator 强制要求）：
 *   既防穿越，又让 API 进程 / Worker 进程在不同 cwd 下都能解析到同一份仓库。
 * - 通过"预设"下拉，把 examples/legacy-demo 等样例工程的路径预填进来——
 *   这是手填最容易出错的部分，长 Windows 路径来回复制极容易把反斜杠写成正斜杠。
 */
import { ElMessage } from 'element-plus'
import { reactive, ref, watch } from 'vue'

import type { CreateTaskRequest } from '@/api/types'
import { PROJECT_PRESETS, type ProjectPreset } from '@/config/presets'

import { useTasksStore } from '@/stores/tasks'

const tasks = useTasksStore()

interface FormState {
  preset: string
  projectRoot: string
  entryFile: string
  targetJdk: number
}

const form = reactive<FormState>({
  preset: PROJECT_PRESETS[0]?.label ?? '',
  projectRoot: PROJECT_PRESETS[0]?.root ?? '',
  entryFile: PROJECT_PRESETS[0]?.entryFile ?? '',
  targetJdk: 21,
})

/** 表单自检 —— 给用户看的，不替后端做决定。 */
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
  if (!form.entryFile.trim()) {
    entryFileError.value = '请填写入口文件相对路径'
    ok = false
  } else if (form.entryFile.includes('\\')) {
    entryFileError.value = '请用正斜杠分隔路径，例如 src/main/java/...'
    ok = false
  }
  return ok
}

watch(
  () => form.preset,
  (label) => {
    if (!label) return
    const hit = PROJECT_PRESETS.find((p) => p.label === label)
    if (hit) {
      form.projectRoot = hit.root
      form.entryFile = hit.entryFile
    }
  },
)

async function onSubmit(): Promise<void> {
  if (!validate()) return
  const req: CreateTaskRequest = {
    projectRoot: form.projectRoot.trim(),
    entryFile: form.entryFile.trim(),
    targetJdk: form.targetJdk,
  }
  const created = await tasks.submit(req)
  if (created) {
    ElMessage.success(`已提交任务 #${created.id}`)
  } else if (tasks.submitError) {
    ElMessage.error(tasks.submitError)
  }
}

function onPickPreset(preset: ProjectPreset): void {
  form.preset = preset.label
  form.projectRoot = preset.root
  form.entryFile = preset.entryFile
}
</script>

<template>
  <div class="card">
    <div class="section-title">新建迁移任务</div>
    <el-form label-position="top" @submit.prevent="onSubmit">
      <el-form-item label="预设样例工程">
        <el-select v-model="form.preset" placeholder="选一个常见示例" clearable>
          <el-option
            v-for="p in PROJECT_PRESETS"
            :key="p.label"
            :label="p.label"
            :value="p.label"
            @click="onPickPreset(p)"
          />
        </el-select>
        <div v-if="form.preset" class="preset-desc">
          {{ PROJECT_PRESETS.find((x) => x.label === form.preset)?.description }}
        </div>
      </el-form-item>

      <el-form-item label="项目根目录（绝对路径）" :error="projectRootError ?? undefined">
        <el-input v-model="form.projectRoot" placeholder="E:/.../your-project" />
      </el-form-item>

      <el-form-item label="入口文件（相对项目根）" :error="entryFileError ?? undefined">
        <el-input v-model="form.entryFile" placeholder="src/main/java/com/example/Foo.java" />
      </el-form-item>

      <el-form-item label="目标 JDK">
        <el-radio-group v-model="form.targetJdk">
          <el-radio :value="17">17</el-radio>
          <el-radio :value="21">21</el-radio>
        </el-radio-group>
      </el-form-item>

      <el-button
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
.preset-desc {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
}
</style>
