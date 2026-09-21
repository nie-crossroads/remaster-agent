import { isAxiosError } from 'axios'
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { getCurrentUser } from '@/api/client'
import type { CurrentUser } from '@/api/types'

/**
 * 登录状态与角色。
 *
 * 演示模式用「共享账号 + HTTP Basic」做最小化鉴权。凭据只存在 sessionStorage ——
 * 关掉标签页即失效，**不**落 localStorage（不跨会话）、**不**落任何持久化用户表
 * （与「演示刻意与多租户解耦」的立论一致）。
 *
 * axios 拦截器（见 {@link ../api/interceptors}）在请求需要登录的端点时自动把这里的凭据
 * 塞进 `Authorization: Basic base64(user:pass)` 头；所以业务代码里从不直接碰这个头。
 *
 * <h2>为什么凭据之外还要存一份 currentUser</h2>
 * 凭据是「我声称我是谁」，角色是「服务端认为我是谁」。后者只有服务端能回答，
 * 所以它<span>不</span>参与持久化、每次登录/刷新都重新问一次（{@link refreshCurrentUser}）。
 * 把它缓存进 sessionStorage 会让「角色」出现第二个来源，而权限判断一旦有两个来源，
 * 迟早会出现界面按 root 渲染、接口却按 demo 拒绝的自相矛盾。
 */

const STORAGE_KEY = 'remaster_demo_credentials'

interface Credentials {
  username: string
  password: string
}

/** 从 sessionStorage 读回凭据（没有就是 null）。 */
function loadCredentials(): Credentials | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Credentials
    if (parsed && typeof parsed.username === 'string' && typeof parsed.password === 'string') {
      return parsed
    }
    return null
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const credentials = ref<Credentials | null>(loadCredentials())
  /**
   * 服务端确认过的身份与角色。
   *
   * 三种「空」要分清：还没问（刷新页面后、请求在途）、问不到（后端不可用/网络断）、
   * 凭据无效（401，此时 {@link login} 会直接把人挡在门外）。
   * 三者都落成 null，界面一律按**最小可见范围**渲染 —— 拿不到角色时不冒充 root。
   */
  const currentUser = ref<CurrentUser | null>(null)

  /** 是否已登录（有凭据即视为已登录；凭据对错由服务端每次校验）。 */
  const isAuthenticated = computed(() => credentials.value !== null)

  /** 当前登录的用户名（仅用于界面显示）。 */
  const username = computed(() => credentials.value?.username ?? null)

  /** 服务端签发的角色；还没拿到时为空数组。 */
  const roles = computed<string[]>(() => currentUser.value?.roles ?? [])

  /**
   * 是否 root（全权限）。
   *
   * 判据只有一条：服务端给的角色列表里有 `ROOT`。不要退化成「用户名等于 root」——
   * 用户名是可配置的，而且那样等于把权限判据复制到前端，与安全链各说各话。
   */
  const isRoot = computed(() => roles.value.includes('ROOT'))

  /**
   * 计算 Basic 头的值（`Basic base64(user:pass)`），未登录返回 null。
   *
   * 拦截器拿不到就说明这一次的请求不该带鉴权（例如公开的状态端点）。
   */
  function authHeader(): string | null {
    if (!credentials.value) return null
    const token = btoa(`${credentials.value.username}:${credentials.value.password}`)
    return `Basic ${token}`
  }

  function persist(next: Credentials | null): void {
    credentials.value = next
    try {
      if (next) {
        sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next))
      } else {
        sessionStorage.removeItem(STORAGE_KEY)
      }
    } catch {
      // sessionStorage 不可用时（隐私模式等）至少内存里有效，不阻断本次会话
    }
  }

  /**
   * 登录：先落凭据，再向服务端确认一次。
   *
   * <p>顺序不能反 —— 确认请求要走拦截器，而拦截器读的正是刚存下的凭据。
   * <p>确认失败就立刻清掉：绝不把一个「看着已登录、其实处处 401」的状态留给界面，
   * 否则用户会以为登录成功了，直到点了「运行示例」才莫名其妙地失败。
   *
   * @throws 凭据错误（401）或后端不可用时抛出的原始异常，由调用方翻成人话
   */
  async function login(username: string, password: string): Promise<CurrentUser> {
    persist({ username, password })
    try {
      const user = await getCurrentUser()
      currentUser.value = user
      return user
    } catch (e) {
      logout()
      throw e
    }
  }

  /**
   * 用已存的凭据向服务端换一次身份（刷新页面后调用）。
   *
   * <p>两种失败要分开处理：
   * <ul>
   *   <li><b>401 = 凭据本身无效</b>（改过密码、换过账号）。这种「登录状态」只会让人处处碰壁 ——
   *       尤其登录页的守卫会把已登录的人送回首页，用户连改都改不了。所以直接清掉。</li>
   *   <li><b>问不到（后端没起 / 网络断）= 暂时性</b>。凭据不动，只把角色清空让界面回落到最小可见范围，
   *       等后端恢复下次刷新即可 —— 网络抖动不该把人的登录态抹掉。</li>
   * </ul>
   */
  async function refreshCurrentUser(): Promise<void> {
    if (!credentials.value) {
      currentUser.value = null
      return
    }
    try {
      currentUser.value = await getCurrentUser()
    } catch (e) {
      currentUser.value = null
      if (isAxiosError(e) && e.response?.status === 401) {
        logout()
      }
    }
  }

  /** 退出：清凭据 + 清角色 + 清 sessionStorage。 */
  function logout(): void {
    currentUser.value = null
    persist(null)
  }

  return {
    credentials,
    currentUser,
    isAuthenticated,
    username,
    roles,
    isRoot,
    authHeader,
    login,
    refreshCurrentUser,
    logout,
  }
})
