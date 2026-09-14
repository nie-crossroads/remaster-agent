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
 * ## 为什么必须是「真的」worker，不能拿空壳占位（踩过，代价很大）
 *
 * Monaco 的 DiffEditor **不在主线程算差异**：它把 original / modified 两个 model
 * 交给 `editorWorkerService` worker，**等 worker 回包之后**才画变更标注
 * （`.line-insert` / `.line-delete` / 行号旁的红绿条 / 内联字符高亮）。
 *
 * 所以「内联一个什么都不做的 worker」看起来一切正常 —— 两个 model 都渲染出来了、
 * 控制台一条错都没有 —— 但 worker 永不回包，diff 结果永远到不了，
 * 页面上就**只剩两栏文本、一处改动标注都没有**。这种静默失败比报错难查得多：
 * 没有任何错误信息指向 worker，看起来像「Monaco 根本不支持 diff 标注」。
 *
 * ## 为什么 import 说明符是 `monaco-editor/editor/editor.worker.js`
 *
 * monaco-editor 0.56 的 package.json exports 是：
 *
 * ```json
 * { "./*.js": "./esm/vs/*.js", "./*": "./esm/vs/*.js" }
 * ```
 *
 * 即 `*` 只匹配 **`esm/vs/` 之后**的那一段。所以同一个文件有两种写法、只有一种能解析：
 *
 * - ✅ `monaco-editor/editor/editor.worker.js` → `esm/vs/editor/editor.worker.js`
 * - ❌ `monaco-editor/esm/vs/editor/editor.worker.js` → 前缀被拼重，`MODULE_NOT_FOUND`
 *
 * **别照着 node_modules 里的物理路径写 import 说明符。**
 *
 * `?worker` 后缀让 Vite 把它单独打成 worker chunk：主包只拿到一个瘦加载器，
 * 真正的 worker 代码在 Monaco 第一次要 worker 时才下载 —— 首屏体积不受影响。
 */
import EditorWorker from 'monaco-editor/editor/editor.worker.js?worker'

export function setupMonaco(): void {
  const globalAny = self as unknown as {
    MonacoEnvironment?: { getWorker: (workerId: string, label: string) => Worker }
  }

  globalAny.MonacoEnvironment = {
    // 本项目只用 Java 做差异对比，只需要基础编辑器 worker
    // （typescript/javascript 的语言 worker 与 diff 无关，不必装配）。
    getWorker(_workerId: string, _label: string): Worker {
      return new EditorWorker()
    },
  }
}
