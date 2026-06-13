-- ops 手工执行版（与 db/migration/V20260613__ai_trace_cancelled_status.sql 同步）。
-- Trace 新增 CANCELLED 状态：用户主动停止生成时落成 CANCELLED，区分于真实 ERROR、不计入错误率。
-- status 列已是 VARCHAR(16)，无需改类型，仅更新注释。
ALTER TABLE `ai_trace`
    MODIFY COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'SUCCESS'
        COMMENT 'Trace 状态：SUCCESS / WARN / ERROR / CANCELLED(用户主动停止)';

ALTER TABLE `ai_trace_step`
    MODIFY COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'SUCCESS'
        COMMENT '步骤状态：SUCCESS / WARN / ERROR / CANCELLED(用户主动停止)';
