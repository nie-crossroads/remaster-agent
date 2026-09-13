import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
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
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
