-- 变更回写审计：把「某次任务的产出被写回源工程」这件事落成一行可查的记录。
--
-- 为什么单独一张表而不是给 patch 加一列：
-- 回写是一个**显式动作**（人点了「应用到源工程」），它有自己的属性 —— 备份目录、文件清单、
-- 发生时间。patch 则是「改写产出了什么」的事实，两者是不同维度：一个 patch 可能从未被回写，
-- 一次回写也可能覆盖多个 patch。把动作属性塞进 patch 会让「谁在什么时候把什么写回了哪里」
-- 需要跨行拼凑才能回答。
--
-- 为什么带 ON DELETE CASCADE：删任务时这条审计记录随之消失，与 dag_node / patch 的级联方向一致。
-- （注意 llm_call 是刻意的无外键账本，不在此列 —— 见 docs/DATA_MODEL.md。）
CREATE TABLE source_write_back
(
    id           BIGSERIAL   PRIMARY KEY,
    task_id      BIGINT      NOT NULL REFERENCES migration_task (id) ON DELETE CASCADE,
    project_root TEXT        NOT NULL,
    backup_dir   TEXT,
    files        JSONB       NOT NULL DEFAULT '[]'::jsonb,
    file_count   INT         NOT NULL DEFAULT 0,
    applied_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE source_write_back IS '变更回写审计：任务产出被写回源工程的一次动作（备份目录 + 文件清单 + 时间）';
COMMENT ON COLUMN source_write_back.id IS '主键，自增';
COMMENT ON COLUMN source_write_back.task_id IS '所属任务，任务删除时级联删除';
COMMENT ON COLUMN source_write_back.project_root IS '被写回的源工程根目录（绝对路径，写回时的值）';
COMMENT ON COLUMN source_write_back.backup_dir IS '写回前的原文件备份目录；回滚就是把这里的文件拷回去。为空表示这次回写没做备份（不该发生，留作排查线索）';
COMMENT ON COLUMN source_write_back.files IS '被写回的文件清单 JSON 数组：[{filePath, bytes, sha256}]（sha256 是写回后内容的哈希，事后可核对落盘结果）';
COMMENT ON COLUMN source_write_back.file_count IS '写回的文件数，便于列表页直接显示而不必解析 files';
COMMENT ON COLUMN source_write_back.applied_at IS '写回时间';

CREATE INDEX idx_source_write_back_task ON source_write_back (task_id);
