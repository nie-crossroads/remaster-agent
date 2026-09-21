<script setup lang="ts">
/**
 * 落地页（首页）。
 *
 * 面向「第一次打开这个项目的人」：一句话讲清它是什么，给两个动作入口——
 * 「运行示例工程」（演示模式，游客跑本地可信样本）与「查看架构」（流水线原理）。
 *
 * 演示 CTA 的状态机：
 *   - 演示未开启 → 按钮禁用，提示「演示模式未开启」
 *   - 开启但未登录 → 跳 /login（游客横幅同时提示），登录后回到工作台
 *   - 已登录 → 跳到工作台，由用户在「新建任务」里挑样本、自己点提交
 *
 * **刻意不在这里直接起任务**：落地页一点就跑，访客还没看清这是什么就已经烧掉一次 LLM 调用、
 * 还白占一个演示并发位。让「选哪个样本、什么时候跑」回到工作台里由用户决定。
 */
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import { useDemoStore } from '@/stores/demo'

const auth = useAuthStore()
const demo = useDemoStore()
const router = useRouter()

onMounted(() => {
  if (!demo.loaded) void demo.loadDemoStatus()
})

/** 游客横幅：演示开启、需要登录、且当前未登录时显示。 */
const showGuestBanner = computed(
  () => demo.enabled && demo.requiresLogin && !auth.isAuthenticated,
)

/** CTA 是否可点：只取决于演示有没有开 —— 登不登录都能点，未登录会被带去登录页。 */
const canRun = computed(() => demo.enabled)

function onRunDemo(): void {
  // 只把人带到工作台：「跑哪个样本、什么时候跑」留给用户在「新建任务」里自己决定。
  // 带 redirect 去登录页，登完直接落到工作台，而不是被丢回首页。
  if (!auth.isAuthenticated) {
    router.push({ name: 'login', query: { redirect: '/tasks' } })
    return
  }
  router.push('/tasks')
}
</script>

<template>
  <div class="home">
    <!-- 游客横幅：未登录时提示登录后可运行示例 -->
    <div v-if="showGuestBanner" class="guest-banner">
      <span>演示模式已开启，登录后可进入工作台选择示例工程运行。</span>
      <el-button type="primary" size="small" @click="router.push('/login')">
        登录
      </el-button>
    </div>

    <section class="hero">
      <h1>RemasterAgent</h1>
      <p class="tagline">把 JDK 8 / Spring Boot 2 的遗留工程，自动迁移到 JDK 21 / Spring Boot 3</p>
      <p class="sub">
        一个由 LLM 驱动的遗留代码现代化 Agent：用 Java 工程能力（AST 解析、构建沙箱、状态管理）
        兜住大模型的不确定性，迁移结果以「编译通过 + 单测通过」客观可验证。
      </p>

      <div class="cta">
        <el-button
          type="primary"
          size="large"
          :disabled="!canRun"
          @click="onRunDemo"
        >
          运行示例工程
        </el-button>
        <el-button size="large" @click="router.push('/architecture')">
          查看架构原理
        </el-button>
      </div>
      <p v-if="!demo.enabled && demo.loaded" class="hint">
        演示模式未开启（后端 <code>remaster.demo.enabled=false</code>），「运行示例工程」暂不可用。
      </p>
      <p v-if="!demo.loaded && !demo.error" class="hint">正在读取演示状态…</p>
    </section>

    <section class="features">
      <div class="feature">
        <div class="feature-icon">🔍</div>
        <h3>精读代码再动手</h3>
        <p>ANALYZE 阶段用 JavaParser 真正解析 AST，识别已移除的 JDK API 与 javax→jakarta 命名空间，而不是靠正则盲改。</p>
      </div>
      <div class="feature">
        <div class="feature-icon">🧭</div>
        <h3>规划可评审</h3>
        <p>PLAN 阶段列出「要改哪些文件、为什么改」，支持人工评审与驳回，迁移范围一目了然。</p>
      </div>
      <div class="feature">
        <div class="feature-icon">📦</div>
        <h3>隔离构建沙箱</h3>
        <p>每个任务在源工程副本里改写并跑 <code>mvn test</code>，源文件全程只读；验证通过才回写。</p>
      </div>
      <div class="feature">
        <div class="feature-icon">🔁</div>
        <h3>失败可回退</h3>
        <p>编译/测试不过就带着定向反馈重试，最多 3 轮自动回退，而非把错误甩给用户。</p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.home {
  max-width: 1080px;
  margin: 0 auto;
  padding: 32px 24px 64px;
}

.guest-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-7);
  color: var(--el-color-primary);
  padding: 10px 16px;
  border-radius: 8px;
  margin-bottom: 24px;
  font-size: 13px;
}

.hero {
  text-align: center;
  padding: 48px 0 24px;
}

.hero h1 {
  font-size: 44px;
  margin: 0 0 12px;
  font-weight: 800;
  letter-spacing: -0.02em;
}

.tagline {
  font-size: 18px;
  color: var(--text-primary);
  margin: 0 0 12px;
  font-weight: 600;
}

.sub {
  max-width: 720px;
  margin: 0 auto 28px;
  color: var(--text-muted);
  line-height: 1.7;
}

.cta {
  display: flex;
  gap: 12px;
  justify-content: center;
  flex-wrap: wrap;
}

.hint {
  margin-top: 16px;
  font-size: 12px;
  color: var(--text-muted);
}

.hint code {
  background: var(--bg-page);
  padding: 1px 6px;
  border-radius: 4px;
  font-family: 'SFMono-Regular', Consolas, monospace;
}

.features {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 16px;
  margin-top: 48px;
}

.feature {
  background: var(--bg-card);
  border: 1px solid var(--border-soft);
  border-radius: 10px;
  padding: 20px;
  box-shadow: var(--shadow-card);
}

.feature-icon {
  font-size: 28px;
  margin-bottom: 10px;
}

.feature h3 {
  margin: 0 0 8px;
  font-size: 15px;
}

.feature p {
  margin: 0;
  font-size: 13px;
  color: var(--text-muted);
  line-height: 1.6;
}

.feature code {
  background: var(--bg-page);
  padding: 1px 5px;
  border-radius: 4px;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 12px;
}
</style>
