import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 开发期把 /api 代理到后端 API 进程（默认 8080）。
 *
 * 为什么不直接写后端绝对地址：跨源要开 CORS，且会把「后端地址」固化进前端代码。
 * 走同源相对路径 + 代理，前端代码里只有 `/api/...`，部署时换个代理目标即可。
 *
 * 注意 SSE（`/api/tasks/{id}/events`）也走这个代理：它是一条长连接响应流，
 * Vite 的 http-proxy 默认就能透传（不需要开 ws —— SSE 是普通 HTTP 流，不是 WebSocket）。
 * 但要确认后端没开响应缓冲，否则事件会被攒着一起发，看起来像「进度不动」。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // SSE 是长连接流，禁用超时，避免长时间没有数据时被代理掐断
        timeout: 0,
        proxyTimeout: 0,
      },
    },
  },
})
