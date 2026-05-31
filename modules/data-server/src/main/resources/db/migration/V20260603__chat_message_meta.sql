-- 助手消息补充元信息：有序片段（工具调用过程可视化）与生成总耗时。
-- segments：刷新 / 重进会话后仍能还原「叙述 → 工具 → 答案」的交错过程（仅含工具调用的消息才存）。
-- elapsed_ms：本轮助手回答总耗时（毫秒），用于在消息末尾展示。
ALTER TABLE `chat_message`
    ADD COLUMN `segments`   JSON   DEFAULT NULL COMMENT '助手消息有序片段（文本/工具调用交错，JSON，可空）' AFTER `citations`,
    ADD COLUMN `elapsed_ms` BIGINT DEFAULT NULL COMMENT '助手生成总耗时（毫秒，可空）' AFTER `segments`;
