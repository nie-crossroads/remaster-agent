<script setup lang="ts">
/**
 * 任务列表 —— 单击切到详情。
 *
 * 列表只显示 task 概要（id/状态/projectRoot/当前节点/量化指标），
 * 真实细节由右侧 TaskDetail 区域承担。这是显式分工，避免列表渲染整图 Diff 等大数据。
 */
import { computed, onMounted } from 'vue'

import type { TaskStatus } from '@/api/types'
import { TASK_STATUS_LABEL, TASK_STATUS_TAG, formatCost, formatDurationPair } from '@/utils/status'

import { useTasksStore } from '@/stores/tasks'

const tasks = useTasksStore()

const statusFilter = computed<Set<TaskStatus>>(() => new Set())

onMounted(() => {
  if (tasks.list.length === 0) {
    void tasks.refreshList()
  }
})

function isSelected(id: number): boolean {
  return tasks.currentDetail?.task.id === id
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

    <div v-else-if="tasks.sortedList.length === 0 && !tasks.listLoading" class="empty">
      <p>暂无任务</p>
      <p class="hint">在右侧填表提交，列表会自动刷新</p>
    </div>

    <ul v-else>
      <li
        v-for="task in tasks.sortedList"
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
          <el-tag :type="TASK_STATUS_TAG[task.status]" size="small">
            {{ TASK_STATUS_LABEL[task.status] }}
          </el-tag>
        </div>
        <div class="path" :title="task.projectRoot + '/' + task.entryFile">
          {{ task.projectRoot.split(/[\\/]/).slice(-2).join('/') }}/{{ task.entryFile.split('/').pop() }}
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
