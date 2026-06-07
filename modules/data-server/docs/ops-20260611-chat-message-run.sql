-- dev 手动迁移（dev-mysql Flyway 未启用，与 src/main/resources/db/migration/V20260611__chat_message_run.sql 等价）。
-- 对话「发送后不丢、可离开、可重连」：助手消息生成生命周期 + 服务端自持久化所需列。
-- 应用：docker exec -i dev-mysql mysql -uroot -p123456 data-server < 本文件
-- status：助手消息生成状态。历史行默认 COMPLETED；用户消息恒为 COMPLETED。
-- run_id：生成运行 id（= SSE connectionId），重连续播据此找回 Redis 流与在跑的生成。
-- error：生成失败 / 取消原因（可空）。
ALTER TABLE `chat_message`
    ADD COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'COMPLETED'
        COMMENT '生成状态 GENERATING/COMPLETED/FAILED/CANCELLED；历史行默认 COMPLETED' AFTER `role`,
    ADD COLUMN `run_id` VARCHAR(64) DEFAULT NULL
        COMMENT '生成运行 id(=connectionId)，用于重连续播' AFTER `status`,
    ADD COLUMN `error`  VARCHAR(512) DEFAULT NULL
        COMMENT '失败/取消原因（可空）' AFTER `run_id`;

ALTER TABLE `chat_message` ADD INDEX `idx_conv_status` (`conversation_id`, `status`);
