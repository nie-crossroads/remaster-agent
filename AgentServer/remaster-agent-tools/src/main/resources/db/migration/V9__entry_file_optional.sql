-- 放开 entry_file 的非空约束：「整仓升级」模式下没有入口文件。
--
-- 为什么需要这个模式：编译级别升级本来就是**工程级**动作 —— POM_REWRITE 节点扫的是全仓
-- pom.xml，跟某个 .java 文件毫无关系。此前却必须传一个入口文件才能建单，于是只想升 JDK 的人
-- 被迫挑一个文件当锚点，还得白白接受一轮对它的大模型改写（多烧一轮调用 + 引入没人要的代码变更）。
--
-- NULL 的语义是「只升编译级别，不改任何代码」，不是「没填」。这两种情况要在数据层就能区分开：
-- 前者是合法任务，后者是表单该拦下的输入 —— 但那个校验属于 API 层，不该靠列的 NOT NULL 来兼。
--
-- 不改 V1：Flyway 的 checksum 校验不允许修改已应用的脚本，只能新增版本。
ALTER TABLE migration_task
    ALTER COLUMN entry_file DROP NOT NULL;

COMMENT ON COLUMN migration_task.entry_file IS '本轮要改写的目标文件，相对工程根的路径。为 NULL 表示整仓升级模式：只把全仓 pom.xml 的编译级别抬到 target_jdk，不做任何代码改写';
