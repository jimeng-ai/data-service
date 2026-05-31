-- 给模型调用日志增加 token 细分列，用于运营侧按租户统计花费 / token / 耗时。
-- 背景：网关聚合 OpenAI / Anthropic 两种 usage 结构，需把缓存读写、推理 token 都记全，
--       并把供应商返回的原始 usage 原样留存（usage_raw），便于将来按新字段 / 模态回溯重算人民币。
-- 字段语义（统一落库 <- OpenAI / Anthropic 来源）：
--   cache_read_tokens   <- OpenAI prompt_tokens_details.cached_tokens / Anthropic cache_read_input_tokens
--   cache_write_tokens  <- Anthropic cache_creation_input_tokens（OpenAI 无）
--   reasoning_tokens    <- OpenAI completion_tokens_details.reasoning_tokens（Anthropic 无, 思考已并入 output_tokens, 仅作信息展示, 不重复计费）
--   usage_raw           <- 供应商返回的整个 usage 对象 JSON
--   has_video           <- 请求是否含视频（为未来图片/视频分析预留）
-- 注意：本项目无 Flyway 自动执行，需手动在 data-server 库执行本脚本后再部署后端，
--       否则新版实体引用了不存在的列会导致相关接口 500。
ALTER TABLE ai_model_call_log
    ADD COLUMN cache_read_tokens  INT NULL COMMENT '缓存读取 token（OpenAI cached_tokens / Anthropic cache_read_input_tokens）'       AFTER total_tokens,
    ADD COLUMN cache_write_tokens INT NULL COMMENT '缓存写入 token（Anthropic cache_creation_input_tokens）'                          AFTER cache_read_tokens,
    ADD COLUMN reasoning_tokens   INT NULL COMMENT '推理/思考 token（OpenAI reasoning_tokens；Anthropic 无, 已并入 output, 仅展示）'   AFTER cache_write_tokens,
    ADD COLUMN usage_raw          JSON NULL COMMENT '供应商返回的原始 usage 对象, 便于将来按新字段/模态回溯重算'                       AFTER reasoning_tokens,
    ADD COLUMN has_video          TINYINT(1) NOT NULL DEFAULT 0 COMMENT '请求是否含视频' AFTER has_document;
