# multimodule-legacy —— 整仓 JDK 升级靶子

这个工程在评测集里是**唯一一个编译级别真的停在 JDK 8** 的样本。

其他样本工程的 `pom.xml` 都刻意钉死 `release=21`（理由写在各自的 pom 注释里：避免「模型产出 text block 被 javac 拒绝」这种环境自造的陷阱），考的是**源码写法现代化**。本工程反过来 —— 它的现代化**必须先改构建描述**，对应编排层的 `POM_REWRITE` 节点。

## 为什么非要有这条用例

模型按提示词把代码现代化到 JDK 21 时，产出的 `text block` / `List.of(...)` / `var` 都是 JDK 9+ 的语法与 API。在 `--release 8` 下 javac 会直接拒绝，报错长这样：

```
error: 文本块是预览特性，默认情况下未启用。
error: 找不到符号  符号: 方法 of(java.lang.String,java.lang.String,java.lang.String)
```

这两条看起来都像**模型写错了**，实际是「pom 没升」。失败反馈会把模型引向错误的修复方向（去改语法而不是改构建），于是回退重写三轮回合全烧在同一件事上。

所以「整仓升级」不是锦上添花：它是让一批用例从**确定性失败**变成可成功的前提。而这条用例就是它的证据 —— 没有它，`POM_REWRITE` 只是代码里的一段逻辑，无从证明它真的生效。

## 模块结构与刻意的写法差异

```
multimodule-legacy/
├── pom.xml              # 老式三元组：java.version=1.8 属性 + 四处 ${java.version} 引用
├── legacy-core/         # 什么都不声明，完全继承根 pom
│   └── .../core/PriceTally.java        ← 用例 multimodule-core-tally
└── legacy-report/       # maven.compiler.release=8（新式单属性）
    └── .../report/StatementWriter.java ← 用例 multimodule-report-writer
```

**三种声明的三种命运**（改写器必须全部覆盖，只认一种就会留下没升干净的模块）：

| 位置 | 声明方式 | 改写动作 |
|---|---|---|
| 根 pom | `<java.version>1.8</java.version>` + `<maven.compiler.source>${java.version}</maven.compiler.source>` | 只改属性值 `1.8 → 21`，**四处 `${java.version}` 引用原样保留**（破坏间接层会让「改一处」变成「以后得改四处」） |
| 根 pom | compiler-plugin `<configuration><source>${java.version}</source>` | 同上，引用不动 |
| legacy-core | 什么都不写 | 往 `<properties>` 里**插入** `maven.compiler.release` |
| legacy-report | `<maven.compiler.release>8</maven.compiler.release>` | 改属性值 `8 → 21` |

## 两个目标文件

- **`PriceTally`**（legacy-core）：`Vector` / `Hashtable`、显式装箱、手写 `Iterator` 循环、
  以及 `Collections.unmodifiableList(Arrays.asList(...))` —— 最后这处的自然现代化结果是
  `List.of(...)`（JDK 9+），也就是逼出「级别必须升」的那根刺。
- **`StatementWriter`**（legacy-report）：手工 while 补空格对齐 + 逐行 `append("\n")`。
  最自然的现代化结果是一整段 **text block**（JDK 15+），在 `--release 8` 下是语法错误。

行为契约只断言**可观测结果**：返回值、渲染文本的列宽形状、集合的只读语义。
不关心内部还剩没剩 `Vector`、用的是 `StringBuilder` 还是 `String.repeat` ——
把实现钉死会把「现代化的自由」变成「必须照抄旧写法」。

`StatementWriterTest` 的期望行用 `String.format("%-12s%8s", ...)` 现算而不是手写空格：
① 空白数量不会被编辑器/换行符设置悄悄改掉；② 它表达的是「列宽 12 与 8」这个契约本身。

## 怎么跑

```bash
# 基线（迁移前必须就是绿的）
mvn -f examples/eval/multimodule-legacy -o test

# 单条用例走完整链路（需要 API + Worker 双进程在跑）
# catalog 里的 id：multimodule-core-tally / multimodule-report-writer
```

基线已验证：JDK 21 上以 `--release 8` 编译通过，两个模块共 7 个单测全绿
（`PriceTallyTest` 4 + `StatementWriterTest` 3）。

## 不在本工程范围内

依赖坐标升级（Spring Boot 2 → 3、`javax` → `jakarta`）不在其中。那是**跨文件的批改写** ——
一批文件必须一起改才编得过，而当前 DAG 是按文件独立插 `rewrite`/`verify` 的，
A 改完 B 没改时 A 的 verify 会报 B 的错，判定成 A 失败且反馈指向 B，不收敛。
扩多文件之前必须先给编排层加批语义。
