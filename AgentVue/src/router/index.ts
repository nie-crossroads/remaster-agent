import { createRouter, createWebHistory } from 'vue-router'

import HomeView from '@/views/HomeView.vue'
import WorkbenchView from '@/views/WorkbenchView.vue'
import ArchitectureView from '@/views/ArchitectureView.vue'
import LoginView from '@/views/LoginView.vue'
import { useAuthStore } from '@/stores/auth'

/**
 * 路由表。
 *
 * <h2>为什么用 history 模式而不是 hash</h2>
 * hash 模式（`/#/tasks`）不需要服务端配合，但地址栏里那个 `#` 很难看，
 * 分享出去的链接也显得业余。改用 history 模式后地址就是干净的 `/tasks`。
 * 代价是**服务端必须把未知路径回退到 index.html**：
 *   - 开发期：Vite dev server 默认 `appType: 'spa'`，已做回退，无需配置；
 *   - 生产：部署时要给前端加一条 `try_files $uri /index.html`（Nginx）之类的回退规则，
 *     否则直接刷新 `/tasks` 会 404。
 *
 * <h2>为什么工作台不按任务 id 切独立路由页面</h2>
 * 工作台是单页内的左右分栏，切任务只是「换当前看哪个」，左侧列表状态要保留；
 * 真把 `/tasks/:id` 做成整页路由会丢掉那份状态、且每次切任务都整页重渲染。
 * 所以 `/tasks` 与 `/tasks/:id` 都渲染同一个 WorkbenchView，后者只是「打开时自动选中某个任务」——
 * 详情数据仍由 store 的 SSE 订阅驱动，路由只负责「初始选哪个」。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    { path: '/architecture', name: 'architecture', component: ArchitectureView },
    // 工作台要登录（演示共享账号）：导航栏会隐藏入口，这里再挡一层直接输地址的情况
    { path: '/tasks', name: 'workbench', component: WorkbenchView, meta: { requiresAuth: true } },
    {
      path: '/tasks/:id',
      name: 'task-detail',
      component: WorkbenchView,
      props: true,
      meta: { requiresAuth: true },
    },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

/**
 * 登录守卫。
 *
 * <p>未登录去工作台 → 踢回登录页，并把原目标塞进 `redirect` 查询参数，登录后按原意跳回去
 * （否则用户点了「工作台」、登完却被丢回首页，会以为没生效）。
 *
 * <p>这个守卫与导航栏的 `v-if` 是**两层**而不是重复：`v-if` 管「看不见入口」，
 * 守卫管「直接输地址也进不去」—— 只做前者，手输 `/tasks` 就能绕过。
 */
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  // 已登录还去登录页 → 直接送回首页，免得重复登录
  if (to.name === 'login' && auth.isAuthenticated) {
    return { name: 'home' }
  }
  return true
})

export default router
