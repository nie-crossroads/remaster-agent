-- ============================================================================
-- RemasterAgent 业务库初始化
-- 目标实例: PostgreSQL 目标端口 5432, 数据库 remaster
-- 说明: 状态树 / checkpoint / 补丁 / 成本明细 都在这里。
--       向量相关表（repo / code_chunk）在同一个 database（remaster）的 V2 迁移里，
--       见 V2__init_vector_schema.sql。单库设计下 repo_id 可建真正外键。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 迁移任务主表
-- ---------------------------------------------------------------------------
CREATE TABLE migration_task (
    id          BIGSERIAL   PRIMARY KEY,
    project_root TEXT       NOT NULL,
    entry_file  TEXT        NOT NULL,
    target_jdk  INT         NOT NULL DEFAULT 21,
    status      TEXT        NOT NULL,
    metrics     JSONB,
    fail_reason TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE  migration_task IS '迁移任务主表：一次「重制」的顶层记录';
COMMENT ON COLUMN migration_task.id IS '主键，自增';
COMMENT ON COLUMN migration_task.project_root IS '被测工程根目录（含 pom.xml）。沙箱执行时复制出去，绝不原地修改原仓库';
COMMENT ON COLUMN migration_task.entry_file IS '本轮要改写的目标文件，相对工程根的路径';
COMMENT ON COLUMN migration_task.target_jdk IS '目标 JDK 版本，阶段 1 固定 21';
COMMENT ON COLUMN migration_task.status IS '任务状态：PENDING/RUNNING/WAITING_HUMAN/SUCCEEDED/FAILED';
COMMENT ON COLUMN migration_task.metrics IS '完成后的指标汇总（编译通过率/单测通过率/覆盖率/成本等），JSON 结构';
COMMENT ON COLUMN migration_task.fail_reason IS '失败原因摘要，成功时为空';
COMMENT ON COLUMN migration_task.created_at IS '创建时间';
COMMENT ON COLUMN migration_task.updated_at IS '最后更新时间';

CREATE INDEX idx_migration_task_status ON migration_task (status, created_at DESC);

-- ---------------------------------------------------------------------------
-- DAG 节点表（同时是 checkpoint 载体）
-- ---------------------------------------------------------------------------
CREATE TABLE dag_node (
    id          BIGSERIAL   PRIMARY KEY,
    task_id     BIGINT      NOT NULL REFERENCES migration_task (id) ON DELETE CASCADE,
    node_key    TEXT        NOT NULL,
    node_type   TEXT        NOT NULL,
    depends_on  BIGINT[]    NOT NULL DEFAULT '{}',
    status      TEXT        NOT NULL,
    attempt     INT         NOT NULL DEFAULT 0,
    result      JSONB,
    error       TEXT,
    started_at  TIMESTAMPTZ,
    finished_at TIMESTAMPTZ
);

-- 唯一约束落在三元组上，不是 (task_id, node_key)。
-- 因为回退重写会为同一个 node_key 派生出 attempt+1 的新行，两者必须能共存。
ALTER TABLE dag_node
    ADD CONSTRAINT uk_dag_node_task_key_attempt UNIQUE (task_id, node_key, attempt);

COMMENT ON TABLE  dag_node IS 'DAG 节点表，同时是 checkpoint 载体：Worker 崩溃重启后靠 result 恢复现场';
COMMENT ON COLUMN dag_node.id IS '主键，自增；depends_on 数组里存的就是它';
COMMENT ON COLUMN dag_node.task_id IS '所属迁移任务';
COMMENT ON COLUMN dag_node.node_key IS '节点在任务内的业务键，如 analyze:com.foo.OrderService';
COMMENT ON COLUMN dag_node.node_type IS '节点类型：ANALYZE/REWRITE/VERIFY/GATE';
COMMENT ON COLUMN dag_node.depends_on IS '上游节点 id 列表，调度器据此做拓扑排序';
COMMENT ON COLUMN dag_node.status IS '节点状态：PENDING/RUNNING/SUCCEEDED/FAILED/SKIPPED';
COMMENT ON COLUMN dag_node.attempt IS '第几次尝试，从 0 开始；VERIFY 失败回退时 +1';
COMMENT ON COLUMN dag_node.result IS '执行结果（编译日志/单测统计/覆盖率/耗时），任意 JSON 结构';
COMMENT ON COLUMN dag_node.error IS '失败原因摘要，成功时为空';
COMMENT ON COLUMN dag_node.started_at IS '开始执行时间';
COMMENT ON COLUMN dag_node.finished_at IS '结束时间';

CREATE INDEX idx_dag_node_task_status ON dag_node (task_id, status);
-- 部分索引：Worker 启动时要扫出所有 RUNNING 节点并把它们重置为 PENDING（上次被杀的残留）
CREATE INDEX idx_dag_node_running ON dag_node (task_id) WHERE status = 'RUNNING';

-- ---------------------------------------------------------------------------
-- 补丁表
-- ---------------------------------------------------------------------------
CREATE TABLE patch (
    id            BIGSERIAL   PRIMARY KEY,
    node_id       BIGINT      NOT NULL REFERENCES dag_node (id) ON DELETE CASCADE,
    file_path     TEXT        NOT NULL,
    diff          TEXT        NOT NULL,
    original_hash TEXT,
    applied       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE  patch IS '改写产出的补丁：人工 diff 审查与回滚的依据';
COMMENT ON COLUMN patch.id IS '主键，自增';
COMMENT ON COLUMN patch.node_id IS '产出它的 REWRITE 节点';
COMMENT ON COLUMN patch.file_path IS '被改写的文件，相对工程根';
COMMENT ON COLUMN patch.diff IS 'unified diff 文本，由服务端本地生成（不是模型给的），前端 Monaco 直接渲染';
COMMENT ON COLUMN patch.original_hash IS '改写前文件内容的 SHA-256，用于校验基线有没有被人动过';
COMMENT ON COLUMN patch.applied IS '是否已应用到沙箱工作目录';
COMMENT ON COLUMN patch.created_at IS '生成时间';

CREATE INDEX idx_patch_node ON patch (node_id);

-- ---------------------------------------------------------------------------
-- LLM 调用明细表（成本治理）
-- ---------------------------------------------------------------------------
CREATE TABLE llm_call (
    id                BIGSERIAL     PRIMARY KEY,
    task_id           BIGINT,
    node_id           BIGINT,
    model             TEXT          NOT NULL,
    purpose           TEXT,
    prompt_tokens     INT           NOT NULL DEFAULT 0,
    completion_tokens INT           NOT NULL DEFAULT 0,
    cost              NUMERIC(12, 6) NOT NULL DEFAULT 0,
    latency_ms        BIGINT,
    trace_id          TEXT,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

COMMENT ON TABLE  llm_call IS '每次 LLM 调用一行，不做聚合：聚合后就答不出「哪个节点最烧钱」了';
COMMENT ON COLUMN llm_call.id IS '主键，自增';
COMMENT ON COLUMN llm_call.task_id IS '所属任务，探针调用时为空';
COMMENT ON COLUMN llm_call.node_id IS '触发调用的节点';
COMMENT ON COLUMN llm_call.model IS '实际使用的模型名（多模型路由下可能不等于默认模型）';
COMMENT ON COLUMN llm_call.purpose IS '调用用途：ANALYZE/REWRITE/VALIDATE';
COMMENT ON COLUMN llm_call.prompt_tokens IS 'prompt token 数';
COMMENT ON COLUMN llm_call.completion_tokens IS 'completion token 数';
COMMENT ON COLUMN llm_call.cost IS '按单价表估算的成本';
COMMENT ON COLUMN llm_call.latency_ms IS '调用耗时（毫秒）';
COMMENT ON COLUMN llm_call.trace_id IS '链路追踪 id，阶段 3 接 OTel 后用于串联全链路';
COMMENT ON COLUMN llm_call.created_at IS '调用时间';

CREATE INDEX idx_llm_call_task ON llm_call (task_id);
CREATE INDEX idx_llm_call_created ON llm_call (created_at DESC);

-- ---------------------------------------------------------------------------
-- 人工门禁表（阶段 3 启用，本轮先建好）
-- ---------------------------------------------------------------------------
CREATE TABLE human_gate (
    id         BIGSERIAL   PRIMARY KEY,
    node_id    BIGINT      NOT NULL REFERENCES dag_node (id) ON DELETE CASCADE,
    status     TEXT        NOT NULL,
    reviewer   TEXT,
    comment    TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ
);

COMMENT ON TABLE  human_gate IS '人工门禁：GATE 节点挂起后等人审批，审批通过才唤醒 DAG';
COMMENT ON COLUMN human_gate.id IS '主键，自增';
COMMENT ON COLUMN human_gate.node_id IS '对应的 GATE 节点';
COMMENT ON COLUMN human_gate.status IS '审批状态：PENDING/APPROVED/REJECTED';
COMMENT ON COLUMN human_gate.reviewer IS '审批人';
COMMENT ON COLUMN human_gate.comment IS '审批意见，打回时会拼进重试 prompt';
COMMENT ON COLUMN human_gate.created_at IS '挂起时间';
COMMENT ON COLUMN human_gate.decided_at IS '审批时间';

CREATE INDEX idx_human_gate_pending ON human_gate (status) WHERE status = 'PENDING';
