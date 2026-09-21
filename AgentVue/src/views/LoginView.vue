<script setup lang="ts">
/**
 * 登录页。
 *
 * 凭据经 auth store 存进 sessionStorage，后续 axios 拦截器自动在需要登录的端点上带
 * `Authorization: Basic ...` 头。本页**不做**鉴权逻辑，只做一件事：
 * 把「填了账号密码」变成「服务端认可的身份」。
 *
 * <h2>为什么要联网校验（以前不校验）</h2>
 * Basic 鉴权是无状态的，服务端不留 session —— 所以「凭据对不对」客户端自己是答不上来的。
 * 以前把校验完全推给「运行示例」那一刻：输错密码的人会先看到「登录成功」，
 * 点运行才撞 401，排查方向一下就偏了。现在登录当场问一次
 * `GET /api/auth/me`（顺带拿回角色），错了直接说错。
 *
 * <h2>已登录的人进到本页怎么办</h2>
 * 交给路由守卫（见 router/index.ts）送回首页 —— 这里不重复做一遍，
 * 否则「什么时候跳走」会有两个判据。
 */
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import { describeError } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({
  username: 'demo',
  password: 'remaster-demo',
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

/** 登录成功后去哪：守卫塞进来的原目标，没有就回首页。 */
function leave(): void {
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  router.replace(redirect)
}

/**
 * 后端的 401 翻成人话。
 *
 * 界面上不该出现「请求失败（HTTP 401）」这种东西 —— 它的意思就是账号或密码不对，
 * 那就这么写。其余错误（网络不通、后端没起）原样透出，那是另一类问题。
 */
function loginErrorMessage(e: unknown): string {
  const message = describeError(e)
  return message.includes('401') ? '账号或密码错误' : message
}

async function onSubmit(): Promise<void> {
  if (!formRef.value || submitting.value) return

  // validate() 失败会 reject；这里转成 false 走同一条返回路径，避免两套分支
  const valid = await formRef.value.validate().then(() => true).catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    await auth.login(form.username.trim(), form.password)
    // 用 store 判出来的身份提示，而不是在这里再写一遍 `roles.includes('ROOT')` ——
    // 角色判据只该有一处
    ElMessage.success(auth.isRoot ? '已登录 root 账号（全部权限）' : '已登录演示账号')
    leave()
  } catch (e) {
    ElMessage.error(`登录失败：${loginErrorMessage(e)}`)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <h2>登录</h2>
      <p class="desc">登录后进入工作台，从示例工程里挑一个运行（跑的是服务器本地可信样本）。</p>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="onSubmit">
        <el-form-item label="账号" prop="username">
          <el-input v-model="form.username" placeholder="demo" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="remaster-demo"
            autocomplete="current-password"
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <el-button type="primary" style="width: 100%" :loading="submitting" @click="onSubmit">
          登录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
/* 高度减去顶部导航：否则「导航 + 整屏」会超出视口，页面底部多出 52px 的溢出滚动条 */
.login-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: calc(100vh - var(--topnav-h));
  padding: 24px;
}

.login-card {
  width: 380px;
  max-width: 100%;
}

/* 标题与说明居中成一整块；表单本体保持左对齐（标签 + 输入框左对齐更好读） */
.login-card h2 {
  margin: 0 0 4px;
  font-size: 20px;
  text-align: center;
}

.desc {
  margin: 0 0 20px;
  font-size: 13px;
  color: var(--text-muted);
  text-align: center;
}
</style>
