import { defineStore } from 'pinia'
import { ref } from 'vue'

import { getDemoSamples, getDemoStatus } from '@/api/client'
import type { DemoSample, DemoStatus } from '@/api/types'

/**
 * 演示模式的「开关可见性」与「可选样本」。
 *
 * 与 {@link ./auth}（登录态）分开：一个讲的是「演示开没开、要不要登录、能跑哪些样本」，
 * 另一个讲的是「我已经登录了没」。落地页需要先知道前者才决定怎么渲染 CTA，
 * 两者各自独立加载、互不等对方。
 *
 * `getDemoStatus()` 命中 `/api/demo/status`，该端点在 Security 链里 `permitAll`，
 * 所以即使未登录也能探到 —— 这正是当初把它设计成免登录的原因。
 * 而样本列表（`/api/demo/samples`）在鉴权后面：它含服务器本地路径，不该给未登录的人看。
 */
export const useDemoStore = defineStore('demo', () => {
  /** 演示端点是否开启。false 时隐藏所有演示入口。 */
  const enabled = ref(false)
  /** 运行示例是否需要登录（本项目恒为 true）。 */
  const requiresLogin = ref(true)
  /** 是否已探过一次（用于避免重复请求 / 显示加载态）。 */
  const loaded = ref(false)
  const loading = ref(false)
  const error = ref<string | null>(null)

  /** 可选演示样本（工作台「新建任务」下拉的选项）。 */
  const samples = ref<DemoSample[]>([])
  /** 样本列表是否已成功拉取过（未登录时为 false）。 */
  const samplesLoaded = ref(false)
  const samplesLoading = ref(false)
  const samplesError = ref<string | null>(null)

  async function loadDemoStatus(): Promise<void> {
    if (loading.value) return
    loading.value = true
    error.value = null
    try {
      const status: DemoStatus = await getDemoStatus()
      enabled.value = status.enabled
      requiresLogin.value = status.requiresLogin
      loaded.value = true
    } catch (e) {
      // 探不到就当作「没开」：宁可少显示一个按钮，也不展示一个点了会 404 的入口
      enabled.value = false
      error.value = e instanceof Error ? e.message : String(e)
    } finally {
      loading.value = false
    }
  }

  /**
   * 拉取样本列表。**需要登录**（端点在鉴权后面），未登录时不要调用。
   *
   * @param force 忽略已拉取标记，强制重拉（例如登录后换了账号 / 之前失败过要重试）
   */
  async function loadSamples(force = false): Promise<void> {
    if (samplesLoading.value) return
    if (samplesLoaded.value && !force) return
    samplesLoading.value = true
    samplesError.value = null
    try {
      samples.value = await getDemoSamples()
      samplesLoaded.value = true
    } catch (e) {
      // 拉不到就保持空列表：表单会显示「暂无可选样本」而不是渲染一个空下拉
      samples.value = []
      samplesError.value = e instanceof Error ? e.message : String(e)
    } finally {
      samplesLoading.value = false
    }
  }

  /**
   * 退出登录时清掉样本 —— 它是**按登录态才有权限看**的数据，
   * 留着会让下一个（未登录的）人在表单里看到上一个人的服务器路径。
   */
  function clearSamples(): void {
    samples.value = []
    samplesLoaded.value = false
    samplesError.value = null
  }

  return {
    enabled,
    requiresLogin,
    loaded,
    loading,
    error,
    samples,
    samplesLoaded,
    samplesLoading,
    samplesError,
    loadDemoStatus,
    loadSamples,
    clearSamples,
  }
})
