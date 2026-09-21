import type { AxiosHeaders, InternalAxiosRequestConfig } from 'axios'

import { http } from './client'
import { useAuthStore } from '@/stores/auth'

/**
 * 需要登录的端点前缀 —— 必须和 `SecurityConfig` 里 `authenticated()` 的那几条一一对应。
 *
 * 其余业务端点（建任务、查详情、SSE）本来就不要求登录，硬塞一个头反而可能在未来某天
 * 被人误当成「全局鉴权」。所以这里是**白名单**，不是「除了公开的都挂上」。
 */
const AUTHENTICATED_PREFIXES = ['/demo/', '/auth/']

/**
 * 给需要登录的端点自动挂 Basic 鉴权头。
 *
 * `useAuthStore()` 必须在这里（运行期、pinia 已挂载后）调用，而不是模块顶层：
 * 顶层调用时 pinia 还没装好会直接抛错。拦截器注册发生在 `main.ts` 的 `app.mount` 之前，
 * 但回调执行一定在请求发出时 —— 那时 pinia 早已就位。
 *
 * 没登录（authHeader 返回 null）就不挂头：后端会把端点拦成 401，由调用方
 * （`login` / `runDemo`）负责把它翻译成人话，而不是在这里伪造凭据。
 */
export function setupAuthInterceptor(): void {
  http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
    const url = config.url ?? ''
    // baseURL 是 /api，所以这里的 url 是 /demo/run、/auth/me 这种相对路径
    if (AUTHENTICATED_PREFIXES.some((prefix) => url.startsWith(prefix))) {
      const auth = useAuthStore()
      const header = auth.authHeader()
      if (header) {
        ;(config.headers as AxiosHeaders).set('Authorization', header)
      }
    }
    return config
  })
}
