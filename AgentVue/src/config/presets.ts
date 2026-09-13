/**
 * 一键演示用预设值 —— 让 examples/legacy-demo 之类的样例工程通过下拉就能填好表单，
 * 避免每次输入一长串 Windows 路径。
 *
 * 注意：`root` 是**绝对路径**，因为后端 `ProjectPathValidator` 强制只接受绝对路径
 * （防穿越 + 让 Worker 进程能在不同 cwd 下都找得到）。
 *
 * 如果以后加更多 presets，记得让 path 与 entryFile 都跟 examples/ 仓库里的实际结构
 * 对齐；写错只是会拿到 422，但跑不通的预设就成了噪音。
 */

export interface ProjectPreset {
  label: string
  root: string
  entryFile: string
  description: string
}

/**
 * 路径以仓库根为基准。后端 `ProjectPathValidator` 强制 root 为绝对路径
 * （防穿越 + 让 Worker 进程能在不同 cwd 下都找得到），所以这里不能用相对路径。
 *
 * DEMO_ROOT 通过 Vite 环境变量注入，避免把本机绝对路径写死进仓库：
 *   在 AgentVue/.env（不入库）里设置  VITE_DEMO_ROOT=E:/path/to/remaster-agent
 * 未设置时留空，预设 root 会成为 `/examples/...`，需在表单里手填真实绝对路径。
 */
const DEMO_ROOT: string =
  (import.meta.env as Record<string, string | undefined>).VITE_DEMO_ROOT ?? ''

export const PROJECT_PRESETS: ProjectPreset[] = [
  {
    label: 'legacy-demo · 销售报表（正常迁移）',
    root: `${DEMO_ROOT}/examples/legacy-demo`,
    entryFile: 'src/main/java/com/example/legacy/LegacySalesReport.java',
    description: 'JD8 写法集中靶子——Calendar/SDF/StringBuilder/匿名内部类。预期 1 轮成功。',
  },
  {
    label: 'legacy-unfixable · 库存台账（构建必败）',
    root: `${DEMO_ROOT}/examples/legacy-unfixable`,
    entryFile: 'src/main/java/com/example/unfixable/LegacyStockLedger.java',
    description: '测试引用了不存在的 com.example.oracle 包，预计 3 轮后停手、判 FAILED。',
  },
]
