-- 阶段 2 增强：跨文件依赖图（名字级调用边）
-- calls 存每个方法块「调用了谁」的名字级候选（如 {"OrderService","OrderService#getStatus"}），
-- 检索时按 neighbor-depth 多跳扩展，靠符号后缀匹配跨文件命中同名类型。
-- 该列对历史已索引的工程为 NULL / '{}'，findCallTargets 已对 NULL 做空处理。

ALTER TABLE code_chunk ADD COLUMN IF NOT EXISTS calls text[] NOT NULL DEFAULT '{}';

CREATE INDEX IF NOT EXISTS ix_code_chunk_calls ON code_chunk USING GIN (calls);
