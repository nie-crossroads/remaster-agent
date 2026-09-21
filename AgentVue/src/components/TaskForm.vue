<script setup lang="ts">
/**
 * 任务表单 —— 同一组件三种形态：
 *
 * - `create` + 演示账号：只能从服务端给的示例工程里挑一个。「任务名」是**下拉框**
 *   （选项来自 `GET /api/demo/samples`），选中后项目根目录 / 入口文件 / 目标 JDK 自动填充
 *   且**全部只读**。提交走 `POST /api/demo/run`（只发样本 key），任务带 `demo` 标记。
 * - `create` + root 账号：**四个字段全部可自由输入**，提交走 `POST /api/tasks`。
 * - `view`  （迁移任务详情）：回填当前任务信息、全部只读、隐藏提交按钮。
 *
 * <h2>为什么两个账号的新建表单不一样</h2>
 * 演示账号跑的是**服务器本地可信样本**，这正是它敢不开 Docker 进程隔离的前提。
 * 允许客户端提交任意 `projectRoot`，等于把「跑哪个工程」交给调用方 —— 那恰恰是演示端点
 * 从一开始就拒绝的事（见后端 DemoController 的类注释）。所以演示账号的路径只展示、不提交。
 *
 * root 是**全权限账号**，本身就持有服务器凭据，不存在「向它隐藏本机路径」的意义；
 * 它要跑的是自己指定的任意工程，所以四个字段全开。二者共用同一个组件、两套渲染，
 * 差别只在「谁在用」，不在「表单能力」。
 *
 * <h2>为什么 root 也保留「快速填入示例」</h2>
 * 全开字段之后，最常见的操作反而是「跑一下那三个示例」—— 手敲绝对路径很难受。
 * 所以留一个可选的填充器：选中即填入三个字段，但**填完仍可继续编辑**，
 * 它只是输入助手，不是新的限制。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import type { CreateTaskRequest } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useDemoStore } from '@/stores/demo'
import { useTasksStore } from '@/stores/tasks'

const tasks = useTasksStore()
const demo = useDemoStore()
const auth = useAuthStore()

/** 当前模式：create = 新建页，view = 详情页。 */
const mode = computed<'create' | 'view'>(() => tasks.formMode)

/** 本次新建是否使用「自由输入」形态（root 专用）。 */
const freeForm = computed(() => auth.isRoot)

// ─────────────────────────── 演示账号：样本下拉 ───────────────────────────

/** 选中的样本 key —— 演示账号下它承担「任务名」的角色。 */
const selectedKey = ref<string>('')

const selectedSample = computed(
  () => demo.samples.find((s) => s.key === selectedKey.value) ?? null,
)

/** 只读展示值：全部来自选中的样本，用户改不了。 */
const filledProjectRoot = computed(() => selectedSample.value?.projectRoot ?? '')
/**
 * 入口文件展示值。
 *
 * 留空不是「没填」，而是一种任务形态：整仓升级（只抬全仓 pom 的编译级别，不改代码）。
 * 直接渲染成空白会让人以为数据缺失，所以这里换成一句明确的说明。
 */
const filledEntryFile = computed(
  () => selectedSample.value?.entryFile || '（整仓升级：只升编译级别，不改代码）',
)
const filledTargetJdk = computed(() => selectedSample.value?.targetJdk ?? 21)

// ─────────────────────────── root：自由输入 ───────────────────────────

/** root 表单的可编辑字段。 */
const freeName = ref<string>('')
const freeProjectRoot = ref<string>('')
const freeEntryFile = ref<string>('')
const freeTargetJdk = ref<number>(21)

/** 可选的目标 JDK。后端要求 >= 17（见 CreateTaskRequest 的 @Min）。 */
const JDK_CHOICES = [17, 21] as const

/** 供 root 快速填入的示例（与演示账号同一份数据）。 */
const presetKey = ref<string>('')

/** 清空 root 表单，回到「什么都没填」的初始态。 */
function resetFreeForm(): void {
  freeName.value = ''
  freeProjectRoot.value = ''
  freeEntryFile.value = ''
  freeTargetJdk.value = 21
  presetKey.value = ''
}

/** 选中一个示例后，把它的三个字段灌进表单（灌完仍可自由修改）。 */
watch(presetKey, (key) => {
  const sample = demo.samples.find((s) => s.key === key)
  if (!sample) return
  freeProjectRoot.value = sample.projectRoot
  freeEntryFile.value = sample.entryFile || ''
  freeTargetJdk.value = sample.targetJdk ?? 21
  // 任务名留空的话给个可读的默认值，省得列表里显示 #id
  if (!freeName.value.trim()) freeName.value = sample.name
})

/**
 * 进入 create 模式就重置（别把上一次填的带过来）。
 *
 * 顺带按角色准备数据：演示账号要拉样本（那个端点需登录，也只有它用得上）；
 * root 也拉一份 —— 它是「快速填入示例」的下拉数据源，拉不到只是少个便利，不阻断建任务。
 */
watch(
  [mode, freeForm],
  ([m]) => {
    if (m !== 'create') return
    resetFreeForm()
    void demo.loadSamples()
  },
  { immediate: true },
)

// 样本到位后默认选中第一个：别让人面对一个空下拉加一片空路径（仅演示账号用得上）
watch(
  () => demo.samples,
  (list) => {
    if (freeForm.value) return
    if (!selectedKey.value && list.length > 0) {
      selectedKey.value = list[0]!.key
    }
  },
  { immediate: true },
)

/** 查看模式下，直接读当前任务的信息（只读、随详情加载自动填充）。 */
const viewTask = computed(() => tasks.currentDetail?.task ?? null)
const viewName = computed(() => viewTask.value?.name ?? '')
const viewProjectRoot = computed(() => viewTask.value?.projectRoot ?? '')
const viewEntryFileText = computed(
  () => viewTask.value?.entryFile || '（整仓升级：只升编译级别，不改代码）',
)
const viewTargetJdk = computed(() => viewTask.value?.targetJdk ?? 21)

async function onSubmitSample(): Promise<void> {
  if (!selectedSample.value) {
    ElMessage.warning('请先选择一个示例工程')
    return
  }
  const created = await tasks.submitDemo(selectedSample.value.key)
  if (created) {
    ElMessage.success(`已提交任务 #${created.id}`)
  } else if (tasks.submitError) {
    ElMessage.error(tasks.submitError)
  }
}

/**
 * root 提交：把四个字段原样交给 `POST /api/tasks`。
 *
 * 这里**只做「必填与否」的表单级校验**，路径合法性（目录穿越、必须含 pom.xml、
 * 入口必须是 .java）一律交给后端 `ProjectPathValidator` —— 那是安全边界，
 * 前端不该、也无法替代它；在这里先判一遍只会造成两套规则各说各话。
 */
async function onSubmitFree(): Promise<void> {
  const projectRoot = freeProjectRoot.value.trim()
  if (!projectRoot) {
    ElMessage.warning('请填写项目根目录')
    return
  }
  const request: CreateTaskRequest = {
    projectRoot,
    // 留空 = 整仓升级：后端把它存成 null，编排层据此铺一条不含 REWRITE 的拓扑
    entryFile: freeEntryFile.value.trim() || null,
    targetJdk: freeTargetJdk.value,
    name: freeName.value.trim() || undefined,
  }
  const created = await tasks.submit(request)
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

    <!-- ========== 新建（演示账号）：从示例工程里挑一个（其余字段只读） ========== -->
    <el-form
      v-if="mode === 'create' && !freeForm"
      label-position="top"
      @submit.prevent="onSubmitSample"
    >
      <el-form-item label="任务名（选择示例工程）">
        <el-select
          v-model="selectedKey"
          placeholder="请选择一个示例工程"
          :loading="demo.samplesLoading"
          style="width: 100%"
        >
          <el-option
            v-for="s in demo.samples"
            :key="s.key"
            :label="s.name"
            :value="s.key"
          />
        </el-select>
        <div v-if="demo.samplesLoading" class="form-hint">正在读取可选示例…</div>
        <div v-else-if="demo.samplesError" class="form-hint is-error">
          读取示例失败：{{ demo.samplesError }}
        </div>
        <div v-else-if="demo.samples.length === 0" class="form-hint is-error">
          暂无可选示例 —— 演示未开启，或后端未配置样本（<code>remaster.demo.samples</code>）。
        </div>
        <div v-else class="form-hint">
          三个示例对应三种结局：顺利迁移成功 / 写法更复杂 / 构建必然失败（演示回退上限）。
        </div>
      </el-form-item>

      <el-form-item label="项目根目录（由示例决定，只读）">
        <el-input :model-value="filledProjectRoot" placeholder="选择示例后自动填充" disabled />
      </el-form-item>

      <el-form-item label="入口文件（由示例决定，只读）">
        <el-input :model-value="filledEntryFile" placeholder="选择示例后自动填充" disabled />
      </el-form-item>

      <el-form-item label="目标 JDK（固定）">
        <el-radio-group :model-value="filledTargetJdk" disabled>
          <el-radio :value="filledTargetJdk">{{ filledTargetJdk }}</el-radio>
        </el-radio-group>
      </el-form-item>

      <!--
        禁用条件同时看 selectedSample 与 submitting：没有选中样本时提交没有意义，
        点下去只会弹一句「请先选择」，不如直接置灰。
      -->
      <el-button
        type="primary"
        :loading="tasks.submitting"
        :disabled="!selectedSample || tasks.submitting"
        @click="onSubmitSample"
      >
        {{ tasks.submitting ? '正在提交…' : '提交任务' }}
      </el-button>
    </el-form>

    <!-- ========== 新建（root）：四个字段全部可自由输入 ========== -->
    <el-form
      v-else-if="mode === 'create'"
      label-position="top"
      @submit.prevent="onSubmitFree"
    >
      <!-- 输入助手：只是省去打字的力气，填进去之后照样能改 -->
      <el-form-item label="快速填入示例（可选）">
        <el-select
          v-model="presetKey"
          placeholder="不选也行 —— 直接在下面手填"
          clearable
          :loading="demo.samplesLoading"
          style="width: 100%"
        >
          <el-option
            v-for="s in demo.samples"
            :key="s.key"
            :label="s.name"
            :value="s.key"
          />
        </el-select>
        <div class="form-hint">
          选中即把下面三个字段填好，<b>之后仍可继续修改</b>；这是一个输入助手，不是限制。
        </div>
      </el-form-item>

      <el-form-item label="任务名（可选）">
        <el-input v-model="freeName" placeholder="留空则列表里显示 #id" clearable />
      </el-form-item>

      <el-form-item label="项目根目录（绝对路径，必填）">
        <el-input
          v-model="freeProjectRoot"
          placeholder="例如 E:/path/to/your-project（须含 pom.xml）"
          clearable
        />
        <div class="form-hint">
          必须是 <b>Worker 所在机器</b>上的真实目录，且根下要有 <code>pom.xml</code>。
          当前没有任何上传通道，填一个别处的路径不会把工程传过来。
        </div>
      </el-form-item>

      <el-form-item label="入口文件（相对项目根，可留空）">
        <el-input
          v-model="freeEntryFile"
          placeholder="例如 src/main/java/com/acme/Foo.java"
          clearable
        />
        <div class="form-hint">
          必须以 <code>.java</code> 结尾。<b>留空 = 整仓升级</b>：只把全仓
          <code>pom.xml</code> 的编译级别抬到目标 JDK，不改任何代码。
        </div>
      </el-form-item>

      <el-form-item label="目标 JDK">
        <el-radio-group v-model="freeTargetJdk">
          <el-radio v-for="jdk in JDK_CHOICES" :key="jdk" :value="jdk">{{ jdk }}</el-radio>
        </el-radio-group>
      </el-form-item>

      <el-button
        type="primary"
        :loading="tasks.submitting"
        :disabled="!freeProjectRoot.trim() || tasks.submitting"
        @click="onSubmitFree"
      >
        {{ tasks.submitting ? '正在提交…' : '提交任务' }}
      </el-button>
    </el-form>

    <!-- ========== 查看模式：回填当前任务，全部只读 ========== -->
    <el-form v-else label-position="top">
      <el-form-item label="任务名">
        <!-- 无名字时明确写「未命名」而不是留空 -->
        <el-input
          :model-value="viewName"
          :placeholder="viewTask && !viewName ? '（未命名）' : ''"
          disabled
        />
      </el-form-item>

      <el-form-item label="项目根目录（绝对路径）">
        <el-input :model-value="viewProjectRoot" disabled />
      </el-form-item>

      <el-form-item label="入口文件（相对项目根）">
        <el-input :model-value="viewEntryFileText" disabled />
      </el-form-item>

      <el-form-item label="目标 JDK">
        <el-radio-group :model-value="viewTargetJdk" disabled>
          <el-radio :value="viewTargetJdk">{{ viewTargetJdk }}</el-radio>
        </el-radio-group>
      </el-form-item>
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

/* 拉不到样本要让用户看得出来是「坏了」而不是「设计如此」 */
.form-hint.is-error {
  color: var(--color-danger);
}

.form-hint code {
  background: var(--bg-page);
  padding: 1px 5px;
  border-radius: 4px;
  font-family: 'SFMono-Regular', Consolas, monospace;
}
</style>
