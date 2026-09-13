-- ============================================================================
-- RemasterAgent 向量 / 代码 RAG 相关表
-- 目标库: 与业务库同一个 PostgreSQL 实例、同一个 database（remaster）。
-- 说明: 阶段 1 不做检索，表先建好占位；阶段 2 代码 RAG 落地时直接写同一库，
--       因此 repo_id 可以建真正的外键，无需跨库 outbox/补偿那套复杂度。
--       扩展 vector / pg_trgm 装在业务库里 —— pgvector 与普通表可共存，无副作用。
--       若日后向量负载需要独立实例，再把向量表拆到独立库并恢复应用层一致性即可
--       （那是部署期选择，不影响「单库 + 外键」这个基线设计）。
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------------
-- 代码仓库表（阶段 2 代码 RAG 使用）
-- ---------------------------------------------------------------------------
CREATE TABLE repo (
    id        BIGSERIAL   PRIMARY KEY,
    name      TEXT        NOT NULL,
    root_path TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE  repo IS '被索引的源代码仓库：代码 RAG 的顶层索引单元';
COMMENT ON COLUMN repo.id IS '主键，自增';
COMMENT ON COLUMN repo.name IS '仓库名（用于展示）';
COMMENT ON COLUMN repo.root_path IS '工程根目录，对应 migration_task.project_root';
COMMENT ON COLUMN repo.created_at IS '首次索引时间';

-- ---------------------------------------------------------------------------
-- 代码分块表（阶段 2 代码 RAG 使用）
-- ---------------------------------------------------------------------------
CREATE TABLE code_chunk (
    id         BIGSERIAL PRIMARY KEY,
    repo_id    BIGINT      NOT NULL REFERENCES repo (id) ON DELETE CASCADE,
    file_path  TEXT        NOT NULL,
    symbol     TEXT,
    kind       TEXT,
    start_line INT,
    end_line   INT,
    content    TEXT        NOT NULL,
    embedding  vector(1024),
    tsv        tsvector GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE  code_chunk IS '代码分块：AST 感知切分的产物，混合检索（向量 + 全文/符号）的检索单元';
COMMENT ON COLUMN code_chunk.id IS '主键，自增';
COMMENT ON COLUMN code_chunk.repo_id IS '所属仓库，外键到 repo(id)；删仓库级联删其全部分块';
COMMENT ON COLUMN code_chunk.file_path IS '文件路径，相对工程根';
COMMENT ON COLUMN code_chunk.symbol IS '符号全限定名，如 com.foo.OrderService#getStatus';
COMMENT ON COLUMN code_chunk.kind IS '符号类型：CLASS/METHOD/FIELD';
COMMENT ON COLUMN code_chunk.start_line IS '代码块起始行';
COMMENT ON COLUMN code_chunk.end_line IS '代码块结束行';
COMMENT ON COLUMN code_chunk.content IS '代码块原文，用于拼进 prompt';
COMMENT ON COLUMN code_chunk.embedding IS '代码 embedding，维度 1024；阶段 1 用占位实现，阶段 2 换真实模型';
COMMENT ON COLUMN code_chunk.tsv IS '全文检索列。必须用 simple 配置：english 会对标识符做词干化，把 getOrderStatus 这类符号改坏，反而降低召回';
COMMENT ON COLUMN code_chunk.created_at IS '写入时间';

-- 向量路：HNSW + 余弦距离
CREATE INDEX idx_chunk_vec ON code_chunk USING hnsw (embedding vector_cosine_ops);
-- 关键词路：全文索引
CREATE INDEX idx_chunk_tsv ON code_chunk USING gin (tsv);
-- 符号路：标识符模糊匹配（getOrderSt* 这类前缀/近似搜索）
CREATE INDEX idx_chunk_symbol ON code_chunk USING gin (symbol gin_trgm_ops);

CREATE INDEX idx_chunk_repo_file ON code_chunk (repo_id, file_path);
