/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, never>, Record<string, never>, unknown>
  export default component
}

/**
 * 注入到 import.meta.env 的运行时配置。
 *
 * Vite 用 `loadEnv` 把 .env*.local 解析成 import.meta.env 的属性；
 * 这里给 TS 一个明确的类型，避免在任何地方都 cast 成 any。
 *
 * 没有列在这里的变量一律读不到 —— 防止「在配置里改个名，前端静默走默认」。
 */
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
