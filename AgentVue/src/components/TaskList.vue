<script setup lang="ts">
/**
 * 任务列表 —— 单击切到详情。
 *
 * 列表只显示 task 概要（id/状态/projectRoot/当前节点/量化指标），
 * 真实细节由右侧 TaskDetail 区域承担。这是显式分工，避免列表渲染整图 Diff 等大数据。
 *
 * 另外有一层展示过滤：演示账号只看得到演示任务，管理员（root）看全部，理由见下方 `visibleTasks`。
 */
import { computed, onMounted } from 'vue'

import type { TaskStatus, TaskView } from '@/api/types'
import { TASK_STATUS_LABEL, TASK_STATUS_TAG, formatCost, formatDurationPair } from '@/utils/status'

import { useAuthStore } from '@/stores/auth'
import { useTasksStore } from '@/stores/tasks'

const tasks = useTasksStore()
const auth = useAuthStore()

const statusFilter = computed<Set<TaskStatus>>(() => new Set())

/**
 * 列表实际渲染的任务 —— 由**服务端签发的角色**决定，而不是由「登没登录」决定。
 *
 * <p>三档：
 * <ul>
 *   <li><b>演示账号（DEMO）</b>：只看演示任务。这个工作台对访客开放的入口就是「跑示例工程」，
 *       访客该看到的只有示例跑出来的任务；这台机器上真实跑过的迁移记录是开发者自己的账，
 *       混进访客视线既解释不清、也不该给看。</li>
 *   <li><b>管理员（ROOT）</b>：全部任务。root 就是这台机器的主人，没理由把自己的账藏起来 ——
 *       这正是加这个账号的意义所在。</li>
 *   <li><b>未登录</b>：工作台本来进不来（路由守卫挡着），这里只是兜底，不额外隐藏什么。</li>
 * </ul>
 *
 * <p>判据用 `auth.isRoot`（即 `/api/auth/me` 返回的角色），而不是 `username === 'root'`：
 * 用户名是可配置的，而且把权限判据复制到前端，就成了和安全链各说各话的两套标准。
 *
 * <p>过滤放在视图层而不是 store 或后端：它是「这一屏给谁看」的展示策略，不是数据权限 ——
 * 接口并不因此变窄（`GET /api/tasks` 照旧返回全量）。
 */
const visibleTasks = computed(() => {
  if (!auth.isAuthenticated || auth.isRoot) return tasks.sortedList
  return tasks.sortedList.filter((task) => task.demo === true)
})

onMounted(() => {
  if (tasks.list.length === 0) {
    void tasks.refreshList()
  }
})

function isSelected(id: number): boolean {
  return tasks.currentDetail?.task.id === id
}

/**
 * 列表第二行的目标文本 —— 没有入口文件的任务是**整仓升级**，不是「信息缺失」。
 *
 * 这里必须写明：否则列表里会出现几行只显示工程名的条目，看不出它和别的任务差在哪，
 * 甚至会被当成脏数据。
 */
function targetText(task: TaskView): string {
  const project = task.projectRoot.split(/[\\/]/).slice(-2).join('/')
  return `${project}/${targetName(task)}`
}

function targetTitle(task: TaskView): string {
  return task.entryFile
    ? `${task.projectRoot}/${task.entryFile}`
    : `${task.projectRoot}（整仓升级）`
}

function targetName(task: TaskView): string {
  return task.entryFile ? (task.entryFile.split('/').pop() ?? task.entryFile) : '整仓升级'
}
</script>

<template>
  <div class="task-list-panel">
    <header>
      <h2>任务列表</h2>
      <div class="header-actions">
		<el-button
          plain
          size="small"
          :loading="tasks.listLoading"
          @click="tasks.refreshList()"
        >
          刷新列表
        </el-button>
        <el-button
          type="primary"
          size="small"
          @click="tasks.startCreate()"
        >
          新建任务
        </el-button>
      </div>
    </header>

    <div v-if="tasks.listError" class="error-banner">
      {{ tasks.listError }}
    </div>

    <div v-else-if="visibleTasks.length === 0 && !tasks.listLoading" class="empty">
      <p>暂无任务</p>
      <p class="hint">点右上角「新建任务」挑一个示例工程提交，任务会出现在这里</p>
    </div>

    <ul v-else>
      <li
        v-for="task in visibleTasks"
        :key="task.id"
        :class="{ active: isSelected(task.id) }"
        @click="tasks.selectTask(task.id)"
      >
        <div class="row1">
          <span class="id">#{{ task.id }}</span>
          <!-- 任务名是主标签；老任务/未命名回退为「未命名」，不留空 -->
          <span class="name" :title="task.name ?? undefined">
            {{ task.name || '未命名' }}
          </span>
          <el-tag v-if="task.demo" type="warning" size="small" effect="plain">演示</el-tag>
          <el-tag :type="TASK_STATUS_TAG[task.status]" size="small">
            {{ TASK_STATUS_LABEL[task.status] }}
          </el-tag>
        </div>
        <div class="path" :title="targetTitle(task)">
          {{ targetText(task) }}
        </div>
        <div class="row3">
          <span>JDK {{ task.targetJdk }}</span>
          <span v-if="task.metrics">{{ formatDurationPair(task.runDurationMs, task.metrics.durationMs) }}</span>
          <span v-if="task.metrics">{{ formatCost(task.metrics.totalCost) }}</span>
        </div>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.task-list-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
}

header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 20px 12px;
  border-bottom: 1px solid var(--border-soft);
  position: sticky;
  top: 0;
  background: var(--bg-card);
  z-index: 5;
}

header h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

ul {
  list-style: none;
  padding: 0;
  margin: 0;
}

li {
  padding: 12px 20px;
  cursor: pointer;
  border-bottom: 1px solid var(--border-soft);
  transition: background 0.12s;
}

li:hover {
  background: rgba(64, 158, 255, 0.04);
}

li.active {
  background: rgba(64, 158, 255, 0.12);
  border-left: 3px solid var(--el-color-primary);
  padding-left: 17px;
}

.row1 {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}

.id {
  flex: 0 0 auto;
  color: var(--text-muted);
  font-family: 'SFMono-Regular', Consolas, monospace;
}

/* 任务名占满剩余宽度，过长省略而不是把状态标签挤下去 */
.name {
  flex: 1 1 auto;
  min-width: 0;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row1 :deep(.el-tag) {
  flex: 0 0 auto;
}

.path {
  font-size: 12px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 4px;
}

.row3 {
  display: flex;
  gap: 10px;
  font-size: 11px;
  color: var(--text-muted);
}

.error-banner {
  margin: 12px;
  padding: 8px 12px;
  background: rgba(245, 108, 108, 0.1);
  color: var(--color-danger);
  border-radius: 4px;
  font-size: 12px;
}

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  flex: 1;
  color: var(--text-muted);
  font-size: 13px;
}

.empty .hint {
  font-size: 11px;
  margin-top: 4px;
}
</style>
