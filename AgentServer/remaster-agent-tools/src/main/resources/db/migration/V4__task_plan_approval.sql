-- ============================================================================
-- 阶段 2（理解层）：迁移计划的人工评审开关
-- 说明: 规划（PLAN）产出「要改哪些文件」之后，可选地要求人工先评审再执行。
--       审批状态落在任务行上而不是单独的表 —— 一个任务只有一个计划、一次审批，
--       独立建表会引入一张「永远只有一行、且与任务一一对应」的表，得不偿失。
--       复用现有状态机字段：审批前任务为 WAITING_HUMAN，批准后回到 PENDING 重新入队。
-- ============================================================================

ALTER TABLE migration_task ADD COLUMN plan_approved BOOLEAN NOT NULL DEFAULT FALSE;

-- 注意：COMMENT ON 的 IS 后面只接受【一个字符串字面量】，不接受表达式 ——
-- 写成 'a' || 'b' 会直接报「syntax error at or near "||"」。长注释只能连成一行。
COMMENT ON COLUMN migration_task.plan_approved IS
    '迁移计划是否已通过人工评审。仅在 remaster.core.require-plan-approval=true 时有意义；默认 FALSE，表示尚未批准 —— 开启评审时任务会挂在 WAITING_HUMAN 等待置为 TRUE，关闭评审时该列被忽略';
