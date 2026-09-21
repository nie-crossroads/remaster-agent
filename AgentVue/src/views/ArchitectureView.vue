<script setup lang="ts">
/**
 * 架构页：把一条迁移任务的执行流水线画出来。
 *
 * 五个阶段（GATE 是可选的人在回路）：ANALYZE → PLAN → REWRITE → VERIFY → (GATE)
 * - ANALYZE：用 JavaParser 解析 AST，识别待迁移点（JDK 已移除 API、javax→jakarta…）
 * - PLAN：列出要改哪些文件、为什么，支持人工评审/驳回
 * - REWRITE：逐文件改写（可多轮），改写只在**源工程副本**里发生
 * - VERIFY：在沙箱副本里跑 mvn test，以编译通过 + 单测通过为唯一事实来源
 * - GATE：可选的人工门禁（开启 require-rewrite-approval 时），改完先等人确认再验证
 *
 * 强调「客观可验证」这个点：整个流程的成败不靠模型自评，而靠 mvn test 的红绿。
 */
import { useRouter } from 'vue-router'

const router = useRouter()

interface Stage {
  key: string
  title: string
  who: string
  desc: string
  optional?: boolean
}

const stages: Stage[] = [
  {
    key: 'ANALYZE',
    title: 'ANALYZE',
    who: 'AST 解析',
    desc: '用 JavaParser 真正解析抽象语法树，识别 JDK 已移除的 API、javax→jakarta 命名空间等迁移点，而非正则盲扫。',
  },
  {
    key: 'PLAN',
    title: 'PLAN',
    who: 'LLM 规划',
    desc: '列出「要改哪些文件、为什么改」，产出可评审的迁移计划；支持人工批准或驳回，迁移范围一目了然。',
  },
  {
    key: 'REWRITE',
    title: 'REWRITE',
    who: 'LLM 改写',
    desc: '按文件逐条改写。改动只发生在**源工程的副本**里；一轮不过会带着定向反馈进入下一轮（最多 3 轮）。',
  },
  {
    key: 'VERIFY',
    title: 'VERIFY',
    who: '构建沙箱',
    desc: '在隔离沙箱副本里跑 mvn test。编译通过 + 单测通过是唯一的成功判据 —— 模型自评不算数。',
  },
  {
    key: 'GATE',
    title: 'GATE',
    who: '人在回路',
    desc: '可选的人工门禁：开启 require-rewrite-approval 时，改完先等人确认补丁，再继续验证。不想开就直接跳过。',
    optional: true,
  },
]
</script>

<template>
  <div class="arch">
    <div class="arch-head">
      <h1>架构原理</h1>
      <p>一条迁移任务的执行流水线：用 Java 工程能力兜住 LLM 的不确定性，成败由构建与测试客观判定。</p>
      <el-button @click="router.push('/')">← 返回首页</el-button>
    </div>

    <!-- 流水线：阶段卡片 + 箭头 -->
    <div class="pipeline">
      <template v-for="(stage, i) in stages" :key="stage.key">
        <div class="stage" :class="{ optional: stage.optional }">
          <div class="stage-key">{{ stage.key }}</div>
          <div class="stage-who">{{ stage.who }}</div>
        </div>
        <div v-if="i < stages.length - 1" class="arrow" :class="{ gate: stages[i + 1].optional }">
          {{ stages[i + 1].optional ? '↳' : '→' }}
        </div>
      </template>
    </div>

    <!-- 阶段详解 -->
    <div class="details">
      <div v-for="stage in stages" :key="stage.key" class="detail" :class="{ optional: stage.optional }">
        <div class="detail-title">
          <span class="detail-key">{{ stage.key }}</span>
          <el-tag v-if="stage.optional" size="small" type="info">可选</el-tag>
          <span class="detail-who">{{ stage.who }}</span>
        </div>
        <p>{{ stage.desc }}</p>
      </div>
    </div>

    <div class="principle">
      <h2>为什么这样设计</h2>
      <ul>
        <li><b>副本隔离</b>：源工程全程只读，所有改动落在沙箱副本；验证通过才由人决定回写，绝不在迁移途中污染你的代码。</li>
        <li><b>客观验收</b>：成功的定义是 <code>mvn test</code> 全绿，不是模型「我觉得改好了」。这把「迁移质量」变成可量化、可重复验证的指标。</li>
        <li><b>可回退</b>：编译/测试不过即带着精确反馈重试，最多 3 轮自动回退，而不是把一句含糊的错误甩给用户。</li>
        <li><b>人在回路可选</b>：需要把关时开 GATE 门禁；追求全自动时关掉，流水线直接 VERIFY，不增加任何摩擦。</li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.arch {
  max-width: 960px;
  margin: 0 auto;
  padding: 32px 24px 64px;
}

.arch-head {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 32px;
}

.arch-head h1 {
  font-size: 28px;
  margin: 0;
}

.arch-head p {
  flex: 1;
  margin: 0;
  color: var(--text-muted);
  font-size: 14px;
}

.pipeline {
  display: flex;
  align-items: stretch;
  gap: 0;
  flex-wrap: wrap;
  margin-bottom: 32px;
}

.stage {
  flex: 1 1 0;
  min-width: 120px;
  background: var(--bg-card);
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  padding: 16px 12px;
  text-align: center;
  box-shadow: var(--shadow-card);
}

.stage.optional {
  border-style: dashed;
  background: var(--bg-page);
}

.stage-key {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-weight: 700;
  font-size: 16px;
  color: var(--el-color-primary);
}

.stage-who {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-muted);
}

.arrow {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  font-size: 20px;
  color: var(--text-muted);
}

.arrow.gate {
  color: var(--color-info);
}

.details {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 16px;
  margin-bottom: 32px;
}

.detail {
  background: var(--bg-card);
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  padding: 16px;
}

.detail.optional {
  border-style: dashed;
}

.detail-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.detail-key {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-weight: 700;
  color: var(--el-color-primary);
}

.detail-who {
  font-size: 12px;
  color: var(--text-muted);
}

.detail p {
  margin: 0;
  font-size: 13px;
  color: var(--text-muted);
  line-height: 1.6;
}

.principle {
  background: var(--bg-card);
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  padding: 20px 24px;
}

.principle h2 {
  font-size: 16px;
  margin: 0 0 12px;
}

.principle ul {
  margin: 0;
  padding-left: 20px;
}

.principle li {
  margin-bottom: 8px;
  font-size: 13px;
  line-height: 1.6;
}

.principle code {
  background: var(--bg-page);
  padding: 1px 6px;
  border-radius: 4px;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 12px;
}
</style>
