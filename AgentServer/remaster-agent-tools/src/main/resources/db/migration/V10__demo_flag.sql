-- 演示模式：标记由游客触发的演示任务（跑服务器本地可信样本）。
--
-- 用途（详见 docs/DEMO_LANDING_ARCH_PLAN.md）：
--   1. 从真实用户任务列表过滤掉演示任务（见 JdbcTaskStore#findRecentTasks 的 `WHERE demo = FALSE`）；
--   2. 清理调度对 demo=true 的任务用更短保留期（演示数据无需长期留存，约束磁盘）。
--
-- 为什么用列而不是 name 前缀：字符串匹配易漏、语义弱；一列是明确的布尔标记，过滤与统计都干净。
-- 不改 V1：Flyway 的 checksum 校验不允许修改已应用的脚本，只能新增版本。
ALTER TABLE migration_task
    ADD COLUMN demo boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN migration_task.demo IS '是否为演示任务（游客触发、跑服务器本地可信样本）。true 时从真实用户任务列表过滤，并用更短保留期清理沙箱';
