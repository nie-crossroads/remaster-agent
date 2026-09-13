/**
 * 装配 Monaco 所需的 Web Worker 环境。
 *
 * ## 为什么这里【不】import monaco-editor 主包
 *
 * `MonacoEnvironment` 只是挂在 `self` 上的一个普通对象，Monaco 在运行时自己去读它。
 * 装配它**不需要**引用 monaco 的任何导出。
 *
 * 早期版本在这里写了 `import * as monaco from 'monaco-editor'` 只为「防止被 tree-shake」，
 * 代价是把整个 monaco 主包（约 5 MB，300+ 模块）拉进首屏同步依赖 —— dev 模式下 Vite
 * 要现场转译这几百个模块，页面会长时间白屏。而这个装配步骤本身完全是零成本的。
 *
 * 结论：谁用 Monaco，谁在组件里 import。这个文件只负责挂环境对象。
 *
 * ## 为什么用「空白 worker 占位」而不是 monaco 自带的 worker 文件
 *
 * 用 Vite 打包 monaco 自带的 worker 会连环撞两个障碍：
 *  1. monaco-editor 的 package.json exports 只暴露 `*.js` 路径，而 worker 入口
 *     惯例写作 `editor.worker`（不带后缀）；
 *  2. Vite 的 worker-import-meta-url 插件会把它当相对当前文件的 URL 去解析，
 *     于是去找 `src/monaco/monaco-editor/...` 这种不存在的路径。
 *
 * 最实用的规避是「内联一个什么都不做的 worker」：monaco 拿不到有效 worker 时会
 * 自动降级到主线程做基础高亮。本项目用 DiffEditor 看代码差异，**语法高亮不是硬需求**，
 * 所以这条退路对产品效果无损。
 *
 * 若以后要做语言级智能（错误标尺、跳转定义），再研究 worker 的打包方案。
 */
export function setupMonaco(): void {
  // 内联 worker 源码 → Blob URL。比让打包器搬运 monaco 的 worker 文件可控得多。
  const stubWorkerCode = 'self.onmessage = () => {};'
  const stubUrl = URL.createObjectURL(
    new Blob([stubWorkerCode], { type: 'application/javascript' }),
  )

  const globalAny = self as unknown as {
    MonacoEnvironment?: { getWorker: (workerId: string, label: string) => Worker }
  }

  globalAny.MonacoEnvironment = {
    getWorker(_workerId: string, _label: string): Worker {
      return new Worker(stubUrl)
    },
  }
}
