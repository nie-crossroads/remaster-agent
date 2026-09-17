-- 任务名：用户在建单页手动输入，便于在列表/详情中区分任务。
-- 允许 NULL（评测 harness 等旧调用方不传名时存空）。
ALTER TABLE migration_task ADD COLUMN name TEXT;

COMMENT ON COLUMN migration_task.name IS '任务名（建单页手动输入，便于在列表/详情区分任务；评测任务默认取用例 id）';
