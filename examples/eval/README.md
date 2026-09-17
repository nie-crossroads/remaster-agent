# 遗留代码现代化评测集（examples/eval）

> 阶段 4「交付层」的可量化交付：让 Agent 把一批**真实遗留类**逐个现代化迁移，用客观指标（编译通过率 / 单测通过率 / 成本 / 回退次数）证明「现代化 Agent」不是空话，而是可复现、可度量、可对比的。

---

## 一、目的

`remaster-agent` 的差异化卖点是「用 Java 工程壁垒（AST 理解 + 构建沙箱 + 状态管理）兜住 LLM 不确定性」。这套评测集就是把这个卖点**变成数字**：

- 每个用例是一个 `(遗留工程, 目标文件)` 对；
- Agent 把目标文件现代化（JDK8 / Spring Boot 2 写法 → JDK21 / Spring Boot 3 写法）；
- VERIFY 阶段在**沙箱副本**里跑全工程 `mvn test`，只有测试全绿才算 `SUCCEEDED`；
- harness 汇总全部用例，输出「如期望率 / 编译通过率 / 单测通过率 / 总成本 / 回退重写次数」等硬指标。

这些指标即作品集「可量化交付」的兑现点。

---

## 二、目录结构

```
examples/eval/
├── catalog.yaml                  # 评测目录：用例清单 + ready/planned 标记 + 负样本语义
├── billing-legacy/               # 正样本工程（账单域，4 个目标类）
├── inventory-legacy/             # 正样本工程（库存域，4 个目标类）
├── payroll-legacy/               # 正样本工程（薪酬域，4 个目标类）
├── logparse-legacy/              # 正样本工程（日志解析域，4 个目标类）
├── csvreport-legacy/             # 正样本工程（报表域，4 个目标类）
├── authtoken-legacy/             # 正样本工程（令牌域，4 个目标类）
├── legacy-demo/                  # 正样本工程（阶段 1 起的冒烟样本，1 个目标类）
├── legacy-demo-complex/          # 正样本工程（类内耦合较大单文件，1 个目标类）
├── negative/
│   ├── unfixable-test-compile-fail/   # 负样本：工程永远编不过
│   ├── unfixable-api-leak/            # 负样本：改写必须动公开签名，被护栏拦住
│   └── unsatisfiable-test/            # 负样本：同一调用被断言成矛盾结果
└── (每个正样本工程内部：src/main/java 目标类 + src/test/java 行为契约测试 + pom.xml)
```

> `eval-results/` 由 harness 运行时生成，已在 `.gitignore` 中忽略，不入库。

---

## 三、一个用例 = (工程, 目标文件)

- catalog 中**每条用例对应一个目标文件**（`entryFile`）。Agent 只现代化这一个文件，其余文件保持原样。
- **同工程内各类行为解耦**：各类互相不调用、单测只测自己。这样「改 A」不会被「B 的单测」误判失败——样本设计层面的纪律，不是 Agent 的锅。
- 所有正样本工程的 `pom.xml` 钉死：`release=21`（钉 JDK21，否则 text block 被 javac 拒）、JUnit 5.12.2、`maven-compiler-plugin` 3.14.1、`maven-surefire-plugin` 3.5.6，保证可复现。
- 行为契约测试只断言**可观测结果**（公开签名 / 返回值 / 输出文本），不断言实现细节（是否用了 `BigDecimal` / `Calendar` 等）。例如 `TokenDigest` 用 SHA-256 已知向量钉死输出，Agent 换成 `HexFormat` 或手写 hex 都算通过。

---

## 四、评测集构成（29 就绪 + 2 规划中）

### 正样本（26 条，期望 `SUCCEEDED`）

| 工程 | 用例 id | 迁移要点 |
|---|---|---|
| billing-legacy | `billing-invoice-calculator` | 金额精度（内部 double → BigDecimal）与显式装箱 |
| billing-legacy | `billing-payment-schedule` | 日期时间（Date/Calendar/SimpleDateFormat → java.time），含月末钳位 |
| billing-legacy | `billing-tax-rule-engine` | 匿名内部类 Comparator → lambda；循环拼接 → text block / join |
| billing-legacy | `billing-refund-ledger` | 旧集合（Hashtable/Vector/Stack → HashMap/ArrayList/ArrayDeque） |
| legacy-demo | `demo-sales-report` | 阶段 1 起就在用的冒烟样本（装箱 / 匿名 Comparator / Date / 文本块） |
| legacy-demo-complex | `complex-customer-orders` | 类内多职责耦合的较大单文件（跨方法一致性） |
| inventory-legacy | `inventory-stock-balance` | Vector/Hashtable 结存台账 + Iterator 遍历 → for-each / Stream |
| inventory-legacy | `inventory-sku-index` | 手写二分查找与 Arrays 旧用法 → Arrays.binarySearch / 泛型方法 |
| inventory-legacy | `inventory-movement-log` | 手写扩容数组 → ArrayList / 集合工厂方法 |
| inventory-legacy | `inventory-reorder-rules` | 匿名内部类 Predicate 过滤 → lambda / Stream.filter |
| payroll-legacy | `payroll-salary-run` | 工资核算：Calendar 月份算术 → java.time；Object[] 强转 → 泛型容器 |
| payroll-legacy | `payroll-tax-year` | 手工算年份边界 → Year / YearMonth |
| payroll-legacy | `payroll-payslip-format` | 逐行 StringBuilder 拼工资条 → text block |
| payroll-legacy | `payroll-overtime-rule` | 匿名 Comparator 排序 + 显式装箱 → 方法引用 + 自动装箱 |
| logparse-legacy | `logparse-line-scanner` | try/finally 手写关闭 BufferedReader → try-with-resources |
| logparse-legacy | `logparse-error-tally` | catch(Exception) 吞掉 + printStackTrace → 具体异常与结构化日志 |
| logparse-legacy | `logparse-rotate-policy` | File 路径拼接 → Path/Paths；SimpleDateFormat 文件名 → DateTimeFormatter |
| logparse-legacy | `logparse-report-main` | 业务逻辑全塞 static main → 抽出可测方法 |
| csvreport-legacy | `csvreport-row-joiner` | 循环内字符串 + 拼接 CSV 行 → String.join |
| csvreport-legacy | `csvreport-column-sorter` | 匿名 Comparator 多字段排序 → Comparator.comparing/thenComparing |
| csvreport-legacy | `csvreport-header-map` | Hashtable 表头索引 + 手工拆行 → Map / String.split 与 Stream |
| csvreport-legacy | `csvreport-fixed-width` | 手工补空格对齐 → String.repeat / formatted 与 text block |
| authtoken-legacy | `authtoken-token-digest` | 手拼 hex 摘要 → HexFormat |
| authtoken-legacy | `authtoken-base64-codec` | sun.misc.BASE64Encoder / 手写查表 → java.util.Base64 |
| authtoken-legacy | `authtoken-expiry-check` | Date 比较有效期 → Instant / Duration |
| authtoken-legacy | `authtoken-secret-store` | 吞异常的「找不到返回 null」→ Optional |

### 负样本（3 条，期望 `FAILED`）

负样本给「成功率」一个有意义的分母——它们的机制是**单文件改写（entryFile）救不回来**：

| 工程 | 用例 id | 机制 |
|---|---|---|
| negative | `negative-test-compile-fail` | 入口文件可改，但工程测试引用了**已删除的类**，永远编不过 |
| negative | `negative-api-leak` | 正确改写必须动**公开签名**，被 API 护栏拦住，单文件改写够不到 |
| negative | `negative-unsatisfiable-test` | 同一调用被断言成**两个矛盾结果**，任何实现都不可能同时通过 |

### 规划中（2 条，`ready: false`，暂未搭建，保留为路线图）

| 工程 | 用例 id | 计划要点 |
|---|---|---|
| （规划中） | `jdbc-statement-modernize` | DriverManager/Statement/sql.Date → DataSource/PreparedStatement/LocalDate |
| （规划中） | `servlet-package-rename` | javax.servlet → jakarta.servlet 包名迁移 |

---

## 五、harness 子命令（remaster-agent-eval 模块）

入口类 `com.remasteragent.eval.EvalMain`，通过 `mvn -pl remaster-agent-eval exec:java` 运行。

| 子命令 | 作用 |
|---|---|
| `catalog` | 列出用例清单：总数 / 就绪 / 计划中 / 负样本各多少，并做入口文件自检 |
| `baseline` | 对每个就绪工程跑一次 `mvn test`，确认**迁移前就是绿的**（正样本必须绿、负样本必须红，否则中止） |
| `run` | 提交就绪用例到 API，轮询到终态，把原始结果增量落到 `eval-results/<runId>/runs.json` |
| `report` | 读已落盘的 `runs.json`，渲染 **Markdown + 自包含 HTML** 量化报告 |
| `collect` | 尚未实现（B3 后续批次占位） |

常用参数：

- `run` / `report`：`--api=<API 地址>`、`--run=<runs.json 路径>`、`--case=<用例 id>`（单条）、`--timeout=<分钟>`（单任务上限，默认 15）、`--poll=<秒>`（轮询间隔，默认 3）。
- `baseline`：`--mvn=<maven 绝对路径>`（必须显式指定，避免 PATH 歧义）、`--baseline-timeout=<分钟>`（默认 10）。

---

## 六、复现步骤

> 前置：本地需先在**双进程**模式下起好 API（`--remaster.worker.embedded=false`，否则 embedded=true 的双消费者会把长 LLM 调用误判 FAILED 又改回 SUCCEEDED）。harness 默认打 `http://localhost:8080`。

```bash
# 0. 确认 API 双进程已起、8080 可通
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/actuator/health

# 1. 校验基线（正样本必须绿、负样本必须红）
mvn -o -pl remaster-agent-eval exec:java \
  "-Dexec.args=baseline --mvn=E:/buildTools/apache-maven-3.9.16/bin/mvn.cmd"

# 2. 跑全量（串行提交全部就绪用例，真烧 token；可加 --case=<id> 只跑单条冒烟）
mvn -o -q -pl remaster-agent-eval exec:java \
  "-Dexec.args=run --api=http://localhost:8080 --timeout=15 --poll=3"

# 3. 渲染报告（读上一步落盘的 runs.json）
mvn -o -q -pl remaster-agent-eval exec:java \
  "-Dexec.args=report --run=eval-results/<runId>/runs.json"
#   → 产出 eval-results/<runId>/report.md 与 report.html
```

---

## 七、口径纪律（报告不许撒谎）

这些纪律是评测「可量化交付」可信的前提，render 时强制遵守：

1. **分母必写**：每个比率都带分母（如 `26/26 (100.0%)`），绝不只给百分比。
2. **无样本显示「无样本」而非 `0%`**：分母为 0 时不假装通过率为 0，避免误导。
3. **verdict 语义**：正样本期望 `SUCCEEDED`，负样本期望 `FAILED`；「如期望」= verdict 命中期望方向。
4. **负样本有真实分母**：负样本机制必须是单文件改写救不回，给成功率一个有意义的可对比项。
5. **成本按元**：`totalCost` 单位为人民币元（qwen / 阿里云网关按元计费）。
6. **每条结果可溯源**：报告里每个数字都能回到 `runs.json` 的 `taskId`，进而追到 `trace_span` / `llm_call` / `patch`。

---

## 八、量化结果（B4 全量评测，2026-09-17）

> 运行 id：`20260917-111557`（全量 29 条）+ `20260917-123219`（authtoken-token-digest 单条重试验证）。
> 报告口径见 `eval-results/20260917-111557/report.{md,html}`。

| 指标 | 值 | 口径说明 |
|---|---|---|
| 总用例 | 29（26 正 + 3 负） | catalog 就绪集 |
| 如期望（报告口径） | 27/28 (96.4%) | 1 条 harness 故障不计聚合 |
| **正样本成功率（能力指标）** | **26/26 (100.0%)** | 剔除环境故障后全部 SUCCEEDED |
| **负样本如期失败率（护栏指标）** | **2/3 (66.7%)** | 1 条负样本失效，见下 |
| 编译通过率（按文件数） | 65/67 (97.0%) | 正样本部分 100%，2 个失败均在负样本（设计性失败） |
| 单测通过率（按用例数） | 491/491 (100.0%) | 所有可用任务测试全绿 |
| LLM 调用次数 | 100 | 全量 |
| 总成本 | 0.7228 元 | 单条中位 0.0212 元（网关有 prompt 缓存） |
| 回退重写次数 | 3 条（均负样本） | 一次通过率 25/28 (89.3%) |
| 单条耗时（中位） | ~140s | 端到端任务创建到终态 |

### 两个异常（诚实记录，均非 Agent 能力问题）

1. **authtoken-token-digest 环境故障**：全量第 24 条时 PostgreSQL 连接瞬断
   （`DataAccessResourceFailureException ... I/O error sending to the backend`），harness 标记
   `HARNESS_ERROR`、不计入任何比率。已单条重试验证（`20260917-123219`，task #49）→
   `SUCCEEDED`，证明属环境故障，正样本真实成功率 **26/26**。
2. **negative-api-leak 负样本失效**：期望 `FAILED`、实际 `SUCCEEDED`（task #47）。根因：测试
   `ApiLeakTest` 引用本工程不存在的辅助类 `LeakFreeIndex`，设计靠「Agent 只改 entryFile 单文件、
   无法新建类」让工程恒红；实际 Agent **越界改了测试文件（或新建了类）** 去掉该依赖，使工程变绿。
   这是**单文件改写护栏未严格生效**的真实缺陷（见待修复项），导致该负样本无法验证护栏。

### 待修复项

- 负样本 `negative-api-leak` 失效：需让 rewrite 节点的护栏严格限制「只改 entryFile，禁止改测试 /
  新建类」，或在 harness 加断言「负样本若 Agent 输出动了非 entryFile，则判护栏失效而非 Agent 成功」。
  否则该负样本失去「验证护栏」的意义，`负样本如期失败率` 2/3 含水分。

<!-- B4_RESULTS_PLACEHOLDER -->
