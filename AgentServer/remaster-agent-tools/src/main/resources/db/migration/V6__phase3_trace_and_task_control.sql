-- ============================================================================
-- 阶段 3 收尾：全链路 Trace（span 落库）与任务控制（取消）
-- 说明:
--   1) trace_span —— OTel span 的落地表。span 走 OTel SDK 生成，但【不接外部 collector】，
--      由自定义的 JdbcSpanExporter 写进这张表，演示环境因此不需要额外起 Jaeger/Tempo。
--      表结构刻意「平铺」成可以一条 SQL 查出来的形状（duration_ms 直接落列而不是每次算），
--      因为它的用途是阶段 4 的耗时/成本报表，而不是回放某一条 trace 的完整结构。
--   2) cancel_requested —— 任务取消是【协作式】的：API 只置标志位，真正停下来由 Worker
--      在节点边界完成。原因是一个节点内部（尤其沙箱里的 mvn test）没法安全中断，
--      强行杀子进程会留下半截工作目录。因此不能用 status='CANCELLED' 直接表达「已请求」。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 全链路 Trace：span 落地表
-- ---------------------------------------------------------------------------
CREATE TABLE trace_span (
    id             BIGSERIAL   PRIMARY KEY,
    trace_id       TEXT        NOT NULL,
    span_id        TEXT        NOT NULL,
    parent_span_id TEXT,
    task_id        BIGINT,
    node_id        BIGINT,
    name           TEXT        NOT NULL,
    kind           TEXT,
    status         TEXT,
    start_at       TIMESTAMPTZ NOT NULL,
    end_at         TIMESTAMPTZ,
    duration_ms    BIGINT,
    attributes     JSONB
);

COMMENT ON TABLE  trace_span IS '全链路 Trace：每个 OTel span 一行，由 JdbcSpanExporter 写入，供阶段 4 出耗时/成本报表';
COMMENT ON COLUMN trace_span.trace_id IS '一次任务执行的全部 span 共用的 trace id（32 位十六进制），与 llm_call.trace_id 同值可直接 join';
COMMENT ON COLUMN trace_span.span_id IS '本 span 的 id（16 位十六进制）';
COMMENT ON COLUMN trace_span.parent_span_id IS '父 span id；任务根 span 为空。用它把耗时还原成树，而不是靠时间戳猜';
COMMENT ON COLUMN trace_span.task_id IS '所属迁移任务。刻意【不建外键】：与 llm_call 同理，这张表是观测账本，任务被删时不该被级联清理掉（保留历史曲线才有意义）';
COMMENT ON COLUMN trace_span.node_id IS '对应的 dag_node id；任务级 span 与 sandbox 之外的 span 可能为空';
COMMENT ON COLUMN trace_span.name IS 'span 名称，形如 task / node:rewrite:xxx.java / llm:REWRITE / sandbox:mvn';
COMMENT ON COLUMN trace_span.kind IS 'OTel SpanKind：INTERNAL/SERVER/CLIENT/PRODUCER/CONSUMER';
COMMENT ON COLUMN trace_span.status IS 'OTel StatusCode：UNSET/OK/ERROR（ERROR 表示这段被记了异常）';
COMMENT ON COLUMN trace_span.start_at IS 'span 开始时间（由 OTel 在创建时打点，不是 exporter 落库时间）';
COMMENT ON COLUMN trace_span.end_at IS 'span 结束时间；null 表示这个 span 结束时进程被杀，没导出来';
COMMENT ON COLUMN trace_span.duration_ms IS '耗时毫秒，落库时算好 —— 报表按它排序/聚合，不必每次做时间差';
COMMENT ON COLUMN trace_span.attributes IS 'span 属性（task.id / node.key / llm.model / llm.cost / sandbox.exit_code 等），JSON 结构';

-- 按 trace 还原调用树
CREATE INDEX idx_trace_span_trace ON trace_span (trace_id, start_at);
-- 单任务耗时曲线：详情页与报表的主查询
CREATE INDEX idx_trace_span_task ON trace_span (task_id, start_at);
-- 「哪一跳最慢」：按名字+时间扫最近一批
CREATE INDEX idx_trace_span_name ON trace_span (name, start_at DESC);

-- ---------------------------------------------------------------------------
-- 任务取消：协作式停止的请求标志
-- ---------------------------------------------------------------------------
ALTER TABLE migration_task ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE;

-- 注意：COMMENT ON 的 IS 后面只接受【一个字符串字面量】，写成 'a' || 'b' 会报 syntax error。
COMMENT ON COLUMN migration_task.cancel_requested IS
    '是否已请求取消。API 置 TRUE 后由 Worker 在【节点边界】协作停止并把任务置为 CANCELLED —— 一个节点内部（尤其沙箱里的 mvn test）无法安全中断，强行杀子进程会留下半截工作目录。任务重跑时该标志会被清回 FALSE';
