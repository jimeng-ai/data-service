-- 给模型调用日志增加 agent_id，用于仪表盘「最近使用」按 Agent 维度展示。
-- 写入侧：AiModelCallRecordService.recordRequest 从请求体 agent_id 落库
--         （RagAnswerService.buildClaudeBody 已在请求体注入 agent_id）。
-- 历史数据该列为 NULL，前端回退显示模型名。
-- 注意：本项目无 Flyway 自动执行，需手动在 data-server 库执行本脚本后再部署后端，
--       否则新版实体引用了不存在的列会导致相关接口 500。
ALTER TABLE ai_model_call_log
    ADD COLUMN agent_id BIGINT NULL COMMENT '发起调用的 Agent ID（可空）' AFTER user_id;

CREATE INDEX idx_amcl_tenant_agent ON ai_model_call_log (tenant_id, agent_id);
