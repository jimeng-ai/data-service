-- Trace 新增 CANCELLED 状态：用户主动停止生成时，头表/步骤落成 CANCELLED 而非 ERROR，
-- 使「用户停止」与真实错误区分开、且不计入错误率（概览统计只数 status='ERROR'）。
--
-- status 列本就是 VARCHAR(16)，'CANCELLED'（9 字符）放得下、无 enum/check 约束，
-- 故无需改列类型，这里仅更新列注释把新枚举值写进文档，保持 schema 注释与代码一致。
ALTER TABLE `ai_trace`
    MODIFY COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'SUCCESS'
        COMMENT 'Trace 状态：SUCCESS / WARN / ERROR / CANCELLED(用户主动停止)';

ALTER TABLE `ai_trace_step`
    MODIFY COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'SUCCESS'
        COMMENT '步骤状态：SUCCESS / WARN / ERROR / CANCELLED(用户主动停止)';
