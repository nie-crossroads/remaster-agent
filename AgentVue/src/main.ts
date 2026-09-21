import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import { setupAuthInterceptor } from './api/interceptors'
import './styles/main.css'
import { setupMonaco } from './monaco/setup'

/**
 * Monaco 的语言 worker 需要在第一个编辑器实例化之前装好。
 *
 * 若放在 App.onMounted 里：在网络慢的时候，编辑器先弹出来再等 worker，loading 会闪一下。
 * 提前到 main.ts 同步段，能保证页面打开时 worker 已经就绪。
 */
setupMonaco()

const app = createApp(App)
app.use(createPinia())
// ElementPlus 必须全局注册：el-input / el-button / el-tag / el-form 等都要靠它渲染。
// 少了这一行，el-input 会渲染成一个空的未知标签 —— 表现为「登录页没有输入框」，
// 按钮与 tag 也会因为拿不到 .el-button / .el-tag 类而丢掉全部样式。
app.use(ElementPlus, { locale: zhCn })
app.use(router)
// 给演示端点自动挂 Basic 鉴权头（仅 /api/demo/**，详见 interceptors.ts）。
// 必须在 pinia 挂载之后注册：拦截器回调里要 useAuthStore()，而那时 pinia 已就位。
setupAuthInterceptor()
app.mount('#app')
