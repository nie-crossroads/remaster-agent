<script setup lang="ts">
/**
 * DAG 可视化 —— 把后端的 `nodes + dependsOn` 真的画成一张图。
 *
 * ## 为什么这里不能用「三个阶段」的线性画法
 * 阶段 2 起，DAG 是**运行期长出来的**：PLAN 节点执行时才按迁移计划往图里插入
 * `rewrite:<文件>` / `verify:<文件>` 节点，而回退会在失败处再派生出 attempt+1 的分支。
 * 也就是说，一张图里会有多个并列的文件分支和多轮重试 —— 任何「ANALYZE→REWRITE→VERIFY
 * 三步走」的固定布局都会画错，必须老老实实按 `dependsOn` 分层。
 *
 * ## 分层布局为什么自己算，而不是引入 dagre
 * `dependsOn` 给的是上游节点 id，而本系统里节点的 id 天然递增（自增主键）且
 * **依赖一定指向更早插入的节点**。所以按 id 升序遍历一次，就能在 O(V+E) 内定出每一层的
 * 层级，无需通用图布局库。少一个依赖，也少一处「布局库版本升级后图变了样」的风险。
 *
 * ## 连线为什么要显式声明 Handle（这是踩出来的）
 * 不给节点放 `<Handle>` 时，Vue Flow 会自己挑一对默认锚点 —— 实际落在**源节点底部中心**与
 * **目标节点顶部中心**。于是每条边都变成「从下方绕出去、从上方插进来」，路径中间会先在
 * 行外兜一圈再横穿回来。
 *
 * 只有一个节点时看不出问题；**一旦同一个文件出现第 2 轮重写**，两条 `rewrite` 节点会在同一列
 * 上下相邻，而「plan → 第 2 轮 rewrite」的横向段恰好落在第 1 轮 rewrite 卡片的高度上 ——
 * 箭头直接穿过上一轮的文本框。这正是「多轮重写时下一轮箭头压住上一轮节点」的成因。
 *
 * 显式把 target 放左边、source 放右边之后，每条边都是标准的「右出左进」，
 * 折线只在两列之间的留白里拐弯，不再穿过任何卡片（留白 = COLUMN_WIDTH - 节点宽度）。
 *
 * ## 状态直接画在节点上
 * 节点的颜色/边框/箭头含义完全对齐节点状态（等待/运行/成功/失败/跳过）——
 * 这张图是**给人看进度**的，不是装饰。
 *
 * ## 「当前挡路的那道门」为什么要单独高亮
 * GATE 挂起时，图上那个门禁节点的状态是 `PENDING` —— 和其它**还没轮到**的节点长得一模一样。
 * 但这两者的含义完全相反：前者是「现在就要你去点一下」，后者是「等着就行」。
 * 不区分的话，用户看着一图灰节点，根本不知道任务为什么不动了。
 * 所以由调用方把 `gateNodeId`（来自详情接口的 `gate.nodeId`）传进来，图上把它点出来。
 */
import { computed } from 'vue'
import { Handle, MarkerType, Position, VueFlow, type Edge, type Node } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'

import type { DagNode } from '@/api/types'
import { NODE_TYPE_EMOJI, NODE_TYPE_LABEL, formatRound } from '@/utils/status'

interface Props {
  nodes: DagNode[]
  /**
   * 当前正挡路的那道人工门禁的节点 id（后端 `TaskDetailView.gate.nodeId`）。
   *
   * 为 null / 不传表示此刻没有门禁挡路 —— 图上不做任何高亮。
   * 用「节点 id」而不是「有没有门禁」这个布尔量：同一任务先后可能经过多道门，
   * 只有最旧那道已经被批准、新一道又挂上时，布尔量会指错地方。
   */
  gateNodeId?: number | null
}

/** 节点卡片自定义类型名，与模板里的 `#node-remaster` 对应。 */
const NODE_TYPE = 'remaster'

/** 节点卡片宽度，必须与样式里的 `.dag-node { width }` 一致 —— 布局算的是左上角坐标。 */
const NODE_WIDTH = 200

/**
 * 列宽 = 节点宽 + 60 的留白。
 *
 * 这 60px 是**连线的拐弯空间**：显式 Handle 之后，边上那条竖线正好落在这段留白里。
 * 留白小于 40 时折线的圆角会挤在一起，看起来像粘连；再宽则整图横向浪费。
 */
const COLUMN_WIDTH = NODE_WIDTH + 60

/**
 * 行高 = 节点最大高度（约 76px）+ 44px 间距。
 *
 * 之前是 96，比带副标题+错误行的卡片高不了多少，两轮重写的节点几乎贴着 ——
 * 即便连线走对了，视觉上也像一坨。这里给到 120。
 */
const ROW_HEIGHT = 120

/** 折线圆角。太小显得生硬，太大在 60px 留白里会画出半个圆。 */
const EDGE_RADIUS = 12

const props = defineProps<Props>()

/**
 * 把节点排成「层级 × 层内序号」的网格。
 *
 * 层级 = 上游里最大层级的下一层（无上游则为第 0 层）。找不到的上游（例如节点列表被裁剪过）
 * 直接忽略，否则整个图会因为一个坏引用彻底画不出来 —— 宁可少一条线，不可整图空白。
 *
 * 同一层内按 id 升序占行，所以「同一个文件的 attempt 0 / 1 / 2」天然在同一列上从上到下排开，
 * 一眼就能看出是同一个文件在反复重写。
 */
const laidOut = computed(() => {
  const ordered = [...props.nodes].sort((a, b) => a.id - b.id)
  const byId = new Map(ordered.map((node) => [node.id, node]))

  const levelOf = new Map<number, number>()
  for (const node of ordered) {
    const upstream = (node.dependsOn ?? []).filter((dep) => byId.has(dep))
    const level = upstream.length === 0
      ? 0
      : Math.max(...upstream.map((dep) => levelOf.get(dep) ?? 0)) + 1
    levelOf.set(node.id, level)
  }

  const usedRows = new Map<number, number>()
  return ordered.map((node) => {
    const level = levelOf.get(node.id) ?? 0
    const row = usedRows.get(level) ?? 0
    usedRows.set(level, row + 1)
    return { node, level, row }
  })
})

/** 节点卡片上的副标题：带文件路径的节点显示文件名，否则不显示。 */
function subtitleOf(node: DagNode): string | null {
  const colon = node.nodeKey?.indexOf(':') ?? -1
  if (colon < 0) return null
  const filePath = node.nodeKey.slice(colon + 1)
  return filePath.split(/[\\/]/).pop() ?? filePath
}

const flowNodes = computed<Node[]>(() =>
  laidOut.value.map(({ node, level, row }) => ({
    id: String(node.id),
    type: NODE_TYPE,
    position: { x: level * COLUMN_WIDTH, y: row * ROW_HEIGHT },
    draggable: false,
    // 父节点不参与选中/拖动交互：这张图是只读的进度视图，
    // 允许拖动只会让人误以为「挪一下能改执行顺序」
    selectable: false,
    data: {
      nodeType: node.nodeType,
      status: node.status,
      attempt: node.attempt,
      title: NODE_TYPE_LABEL[node.nodeType] ?? node.nodeType,
      emoji: NODE_TYPE_EMOJI[node.nodeType] ?? '•',
      subtitle: subtitleOf(node),
      error: node.error,
      // 是不是「此刻挡路的那道门」。放在 data 里而不是改 status ——
      // 门禁节点的状态确实是 PENDING（它没跑完，只是动不了），把状态改写成别的
      // 就等于让界面开始说谎。高亮是一个纯视觉的叠加层。
      blocking: props.gateNodeId != null && node.id === props.gateNodeId,
    },
  })),
)

const flowEdges = computed<Edge[]>(() => {
  const known = new Set(props.nodes.map((node) => node.id))
  const statusById = new Map(props.nodes.map((node) => [node.id, node.status]))
  const edges: Edge[] = []
  for (const node of props.nodes) {
    for (const dep of node.dependsOn ?? []) {
      if (!known.has(dep)) continue
      edges.push({
        id: `${dep}->${node.id}`,
        source: String(dep),
        target: String(node.id),
        type: 'smoothstep',
        // 折线只在列间留白里拐弯，所以圆角可以给得比默认大一点
        pathOptions: { borderRadius: EDGE_RADIUS },
        // 正在跑的节点，它入边做流动动画 —— 一眼能看出「卡在哪一段」
        animated: statusById.get(node.id) === 'RUNNING',
        style: { stroke: '#c8ccd4', strokeWidth: 1.5 },
        markerEnd: MarkerType.ArrowClosed,
      })
    }
  }
  return edges
})

const isEmpty = computed(() => props.nodes.length === 0)
</script>

<template>
  <div>
    <div class="section-title">执行图（DAG）</div>

    <div v-if="isEmpty" class="empty-state">
      <p>暂无节点</p>
      <p class="hint">任务开始后，这里会按依赖关系实时长出节点与连线</p>
    </div>

    <div v-else class="dag-canvas">
      <VueFlow
        :nodes="flowNodes"
        :edges="flowEdges"
        :min-zoom="0.3"
        :max-zoom="1.6"
        :nodes-draggable="false"
        :nodes-connectable="false"
        :elements-selectable="false"
        fit-view-on-init
      >
        <template #node-remaster="nodeProps">
          <!--
            显式锚点：目标在左、源在右。这样每条边都是「右出左进」，
            折线在列间留白里拐弯，不会横穿相邻卡片（详见脚本头部注释）。
            锚点本身不可见、也不需要交互 —— 这是一张只读的进度视图。
          -->
          <Handle type="target" :position="Position.Left" :connectable="false" />
          <div
            class="dag-node"
            :class="[
              `dag-${String(nodeProps.data.status).toLowerCase()}`,
              { 'dag-blocking': nodeProps.data.blocking },
            ]"
          >
            <div class="dag-node-head">
              <span class="dag-emoji">{{ nodeProps.data.emoji }}</span>
              <span class="dag-title">{{ nodeProps.data.title }}</span>
              <!-- 轮次一直显示：只在回退时才出现徽标会让人以为「第一轮没有轮次」 -->
              <span class="dag-attempt">{{ formatRound(nodeProps.data.attempt) }}</span>
              <!-- 挡路的门单独标出来：它的 status 也是 PENDING，与「还没轮到」视觉同形 -->
              <span v-if="nodeProps.data.blocking" class="dag-blocking-badge">待审批</span>
            </div>
            <div v-if="nodeProps.data.subtitle" class="dag-subtitle">
              {{ nodeProps.data.subtitle }}
            </div>
            <div v-if="nodeProps.data.error" class="dag-error">
              {{ nodeProps.data.error.slice(0, 40) }}
            </div>
          </div>
          <Handle type="source" :position="Position.Right" :connectable="false" />
        </template>
      </VueFlow>
    </div>
  </div>
</template>

<style scoped>
.dag-canvas {
  height: 380px;
  border: 1px solid var(--border-soft);
  border-radius: 6px;
  overflow: hidden;
  background: #fbfcfe;
}

.dag-node {
  width: 200px;
  /**
   * 固定高度上限而不是自适应：行高是按「最坏情况（副标题 + 错误行）」算的，
   * 如果卡片高度随内容浮动，「同一行有卡片带错误行、另一行没有」就会让折线看起来忽高忽低。
   */
  min-height: 54px;
  padding: 8px 10px;
  border-radius: 6px;
  border: 1px solid var(--border-soft);
  border-left-width: 3px;
  background: var(--bg-card);
  box-shadow: var(--shadow-card);
  font-size: 12px;
}

/**
 * 锚点不可见：连线的接入位置由它决定，但视觉上不该出现小圆点。
 * 用透明度而不是 display:none —— Vue Flow 要靠 DOM 元素量出锚点坐标，
 * 隐藏掉元素会让它退回默认锚点，等于白做。
 */
.dag-node ~ :deep(.vue-flow__handle),
:deep(.vue-flow__handle) {
  opacity: 0;
  width: 6px;
  height: 6px;
  min-width: 0;
  min-height: 0;
  border: none;
  background: transparent;
}

/* 锚点贴齐卡片左右边缘，这样连线不留缝、也不会缩进卡片内部 */
:deep(.vue-flow__handle-left) {
  left: 0;
}

:deep(.vue-flow__handle-right) {
  right: 0;
}

.dag-running {
  border-left-color: var(--el-color-primary);
  background: rgba(64, 158, 255, 0.06);
}

.dag-succeeded {
  border-left-color: var(--color-success);
}

.dag-failed {
  border-left-color: var(--color-danger);
  background: rgba(245, 108, 108, 0.06);
}

.dag-skipped {
  border-left-color: var(--color-warning);
}

.dag-pending {
  border-left-color: var(--color-info);
  opacity: 0.75;
}

/**
 * 当前挡路的门禁 —— 用告警色 + 呼吸动效把它从一片灰的 PENDING 里拎出来。
 *
 * 为什么值得一个动效：门禁挂起时整个任务就停在这儿，而它在图上和其它「还没轮到」的节点
 * 视觉同形（都是 PENDING 灰）。用户扫一眼图，需要立刻知道「要动手的是这一个」。
 * `prefers-reduced-motion` 下会关掉动画，此时靠边框与徽标依然能认出来。
 */
.dag-blocking {
  border-color: var(--color-warning);
  border-left-width: 3px;
  border-left-color: var(--color-warning);
  opacity: 1;
  background: rgba(230, 162, 60, 0.08);
  box-shadow: 0 0 0 3px rgba(230, 162, 60, 0.15);
  animation: dag-blocking-pulse 1.8s ease-in-out infinite;
}

@keyframes dag-blocking-pulse {
  0%,
  100% {
    box-shadow: 0 0 0 3px rgba(230, 162, 60, 0.15);
  }
  50% {
    box-shadow: 0 0 0 6px rgba(230, 162, 60, 0.05);
  }
}

@media (prefers-reduced-motion: reduce) {
  .dag-blocking {
    animation: none;
  }
}

.dag-blocking-badge {
  margin-left: auto;
  font-size: 10px;
  color: #fff;
  background: var(--color-warning);
  border-radius: 3px;
  padding: 0 4px;
}

.dag-node-head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.dag-emoji {
  font-size: 13px;
}

.dag-title {
  font-weight: 600;
}

.dag-attempt {
  font-size: 10px;
  color: var(--text-muted);
  border: 1px solid var(--border-soft);
  border-radius: 3px;
  padding: 0 3px;
}

.dag-subtitle {
  margin-top: 3px;
  color: var(--text-muted);
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 11px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dag-error {
  margin-top: 3px;
  color: var(--color-danger);
  font-size: 11px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
