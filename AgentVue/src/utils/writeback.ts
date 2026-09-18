import type { WriteBackReport } from '@/api/types'

/**
 * 回写判定的唯一来源。
 *
 * ## 为什么不直接用后端对象上的字段
 *
 * 后端 `WriteBackReportView` 是个 record，它的 `ready()` / `applied()` 是**非组件方法**，
 * Jackson 序列化 record 时只认组件 —— 这两个键**根本不在线上载荷里**（后端有
 * `WriteBackReportViewShapeTest` 把这点钉死）。所以 `report.ready` 永远是 `undefined`，
 * 而且是**静默**的：一个 `undefined` 在 `v-if` 里就是假，界面会表现得像「预检没通过」，
 * 但没有任何报错。本项目在 `TaskMetrics` 的派生方法上已经吃过一次完全一样的亏
 * （指标面板静默清零，任务却完全正常）。
 *
 * 因此：**能从源数据得到的结论，就不要依赖第二份拷贝。**
 * 计数与时间戳永远在，布尔结论现算 —— 这就是下面两个函数的全部理由。
 *
 * 顺带一个好处：后端若哪天真的把 `ready` 也序列化出来，它也不可能与 `blocked` 不一致，
 * 因为前端根本不看它。
 */

/** 预检是否通过 —— 判据就是「没有拦路项」。 */
export function isWriteBackReady(report: WriteBackReport | null | undefined): boolean {
  return report != null && report.blocked.length === 0
}

/**
 * 是否已经真的写回了。
 *
 * 判据是 `appliedAt` 非空，而**不是** `files` 非空：写回一个文件都没写成功时
 * 也可能带着一张清单（那正是被拦时的样子），拿清单当「写过了」会把失败说成成功。
 */
export function isWriteBackApplied(report: WriteBackReport | null | undefined): boolean {
  return report != null && report.appliedAt != null
}

/**
 * 文件指纹只显示前 12 位。
 *
 * 完整 sha256 是 64 个字符，摆在表格里会把路径挤没。留 12 位足以让人肉眼比对
 * 「磁盘上这份是不是当时写的那份」，需要完整值时去看后端留痕或备份目录。
 */
export function shortHash(sha256: string | null | undefined): string {
  if (!sha256) return '—'
  return sha256.slice(0, 12)
}

/** 字节数转成人能读的大小（回写清单里几个源码文件，KB 级足够）。 */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null || bytes < 0) return '—'
  if (bytes < 1024) return `${bytes} B`
  return `${(bytes / 1024).toFixed(1)} KB`
}
