<script setup lang="ts">
/**
 * 全局外壳：顶部导航 + 路由出口。
 *
 * 工作台（WorkbenchView）自带 sticky 的 SSE 状态顶栏，所以这里只放「跨页面」的导航
 * （首页 / 工作台 / 架构）与演示登录态。导航栏走正常文档流：进入工作台后向下滚动时
 * 它会自然滚出视野，工作台自己的顶栏（sticky）则留在顶部 —— 两段顶栏不会互相覆盖。
 */
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { useAuthStore } from '@/stores/auth'
import { useDemoStore } from '@/stores/demo'

const auth = useAuthStore()
const demo = useDemoStore()
const route = useRoute()
const router = useRouter()

/**
 * 要不要在右上角显示「登录」入口。
 *
 * <p>站在登录页时**必须**藏掉：那个按钮点下去还是这一页（守卫会把已登录的人送回首页），
 * 于是它变成一个「点了什么都不发生」的按钮 —— 页面自己正在请你登录，右上角却又挂一个登录入口，
 * 只会让人怀疑是不是点错了地方。
 */
const showLoginButton = computed(() => demo.enabled && route.name !== 'login')

onMounted(() => {
  // 探一次演示开关：决定导航栏要不要显示「登录」入口
  void demo.loadDemoStatus()
  // 凭据存在 sessionStorage 里能跨刷新存活，但**角色**只有服务端知道 —— 补问一次。
  // 不补的话，刷新页面后 root 会被降级成「只看演示任务」，直到下次重新登录。
  if (auth.isAuthenticated) void auth.refreshCurrentUser()
})

function goLogin(): void {
  router.push('/login')
}

function onLogout(): void {
  auth.logout()
  // 样本列表是「登录才看得到」的数据，随手清掉 —— 别留给下一个未登录的人
  demo.clearSamples()
  ElMessage.success('已退出演示登录')
  // 退出时可能正站在工作台（现在已无权限），主动回首页比留在原地等守卫拦更符合预期
  void router.push('/')
}
</script>

<template>
  <div class="app-topnav">
    <router-link to="/" class="brand">RemasterAgent</router-link>

    <nav class="nav-links">
      <router-link to="/">首页</router-link>
      <router-link to="/architecture">架构</router-link>
      <!--
        工作台要求登录（演示共享账号），未登录时连入口都不出现 —— 一个点下去只会被踢回来的
        菜单项，比没有这个菜单项更让人困惑。真正的防线是路由守卫（见 router/index.ts），
        这里只是「别把进不去的东西摆出来」。
      -->
      <router-link v-if="auth.isAuthenticated" to="/tasks">工作台</router-link>
    </nav>

    <div class="nav-right">
      <template v-if="auth.isAuthenticated">
        <!--
          身份按角色显示：root 是「管理员」（它能看到全部任务，说成「演示账号」会误导），
          演示账号就是「演示账号」。用的是服务端签发的角色，不是用户名。
        -->
        <span class="demo-user">{{ auth.isRoot ? '管理员' : '演示账号' }}：{{ auth.username }}</span>
        <el-button size="small" @click="onLogout">退出</el-button>
      </template>
      <!-- 文案就是「登录」：对访客来说这里没有「演示」与「正式」之分，只有登没登进去 -->
      <el-button v-else-if="showLoginButton" size="small" type="primary" @click="goLogin">
        登录
      </el-button>
    </div>
  </div>

  <router-view />
</template>

<style scoped>
.app-topnav {
  display: flex;
  align-items: center;
  gap: 24px;
  /* 高度走全局变量：全屏视图靠它算剩余高度，两处写死会不一致（详见 main.css 的 --topnav-h） */
  height: var(--topnav-h);
  padding: 0 20px;
  background: var(--bg-card);
  border-bottom: 1px solid var(--border-soft);
}

.brand {
  font-size: 16px;
  font-weight: 700;
  color: var(--el-color-primary);
  text-decoration: none;
  letter-spacing: 0.02em;
}

.nav-links {
  display: flex;
  gap: 4px;
}

.nav-links a {
  padding: 6px 12px;
  border-radius: 6px;
  color: var(--text-primary);
  text-decoration: none;
  font-size: 14px;
  transition: background 0.15s;
}

.nav-links a:hover {
  background: var(--bg-page);
}

/* vue-router 给当前路由的 <router-link> 自动加 .router-link-active */
.nav-links a.router-link-active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
}

.nav-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 10px;
}

.demo-user {
  font-size: 12px;
  color: var(--text-muted);
}
</style>
