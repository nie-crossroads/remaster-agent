-- ============================================================================
-- 阶段 2（理解层）代码 RAG：索引写入所需的约束与补充索引
-- 目标库: 与业务库同一实例、同一 database（remaster），见 V2 的说明。
-- 说明: V2 已把表建好占位，V3 只补「真正写入时才会用上」的约束。
-- ============================================================================

-- repo.root_path 唯一：同一工程只对应一行 repo。
-- 代码索引的写入策略是「按 repo 删除旧块 → 重新插入」，需要一个稳定的 repo 行来做
-- 幂等 upsert（ON CONFLICT (root_path)）。没有这个约束，重复索引同一工程会不断
-- 产生新的 repo 行，code_chunk 随之膨胀，检索还会命中陈旧副本。
ALTER TABLE repo ADD CONSTRAINT uq_repo_root_path UNIQUE (root_path);

-- 按 (repo_id, symbol) 取块：符号精确检索、依赖图邻居扩展都要走这条路径。
-- tsvector（全文）与 symbol 上的 trgm（模糊）索引已在 V2 建好，这里只补复合索引。
CREATE INDEX idx_chunk_repo_symbol ON code_chunk (repo_id, symbol);
