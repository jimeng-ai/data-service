-- 助手消息生成生命周期：服务端自持久化 + 可重连续播所需。
-- status：助手消息生成状态。历史行默认 COMPLETED（立即合法、不转圈）；用户消息恒为 COMPLETED。
-- run_id：生成运行 id（= SSE connectionId），重连续播据此找回 Redis 流与在跑的生成。
-- error：生成失败 / 取消时的原因（可空）。
ALTER TABLE `chat_message`
    ADD COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'COMPLETED'
        COMMENT '生成状态 GENERATING/COMPLETED/FAILED/CANCELLED；历史行默认 COMPLETED' AFTER `role`,
    ADD COLUMN `run_id` VARCHAR(64) DEFAULT NULL
        COMMENT '生成运行 id(=connectionId)，用于重连续播' AFTER `status`,
    ADD COLUMN `error`  VARCHAR(512) DEFAULT NULL
        COMMENT '失败/取消原因（可空）' AFTER `run_id`;

-- 列表「正在生成」标志与孤儿清理都按 (会话, 状态) 过滤。
ALTER TABLE `chat_message` ADD INDEX `idx_conv_status` (`conversation_id`, `status`);
