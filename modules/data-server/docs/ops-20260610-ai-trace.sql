-- 全链路调用日志 Trace：在已有 ai_model_call_log（仅记录模型类调用）之外，
-- 新增「一条 trace = 有序异构步骤时间线」的结构，覆盖 LLM 推理 / 知识库检索 / Re-rank /
-- 工具调用 / 插件触发等步骤，供前端「调用日志 · Trace」控制台检索与展开。
--
-- 设计：
--   ai_trace      —— 头表，一条 trace 一行，列表与概览统计的数据源；由 TraceRecorder 增量累计
--                    （累加 step_count / 耗时 / token，推进 end_time，状态升级 SUCCESS->WARN->ERROR）。
--   ai_trace_step —— 步骤明细表，一步一行；模型步骤通过 ref_log_id 反查 ai_model_call_log /
--                    ai_model_call_content，不重复存大 body。
--
-- 两张表均加入多租户拦截器白名单（JimengTenantLineHandler.TENANT_AWARE_TABLES）：
--   - 含 tenant_id 列，租户侧（jm-agent-front）查询自动注入 WHERE tenant_id=?；
--   - 运营侧（jm-operator）用 TenantContext.runAsSystem(...) 绕过，跨租户查询。
--
-- 注意：本项目无 Flyway 自动执行，需手动在 data-server 库执行本脚本后再部署后端。

CREATE TABLE IF NOT EXISTS `ai_trace` (
    `id`                  BIGINT NOT NULL COMMENT '主键，MyBatis-Plus 雪花算法生成',
    `trace_id`            VARCHAR(128) NOT NULL COMMENT '链路追踪 ID（与 ai_model_call_log.trace_id 同源）',
    `tenant_id`           VARCHAR(64) DEFAULT NULL COMMENT '租户 ID',
    `user_id`             VARCHAR(64) DEFAULT NULL COMMENT '用户 ID',
    `agent_id`            BIGINT DEFAULT NULL COMMENT 'Agent ID',
    `agent_name`          VARCHAR(255) DEFAULT NULL COMMENT 'Agent 名称（落库冗余，列表直接展示）',
    `biz_type`            VARCHAR(64) DEFAULT NULL COMMENT '业务类型',
    `scene_code`          VARCHAR(64) DEFAULT NULL COMMENT '场景编码',
    `status`              VARCHAR(16) NOT NULL DEFAULT 'SUCCESS' COMMENT 'Trace 状态：SUCCESS / WARN / ERROR',
    `step_count`          INT NOT NULL DEFAULT 0 COMMENT '步骤数',
    `total_latency_ms`    BIGINT NOT NULL DEFAULT 0 COMMENT '各步骤耗时累加，毫秒',
    `total_input_tokens`  BIGINT NOT NULL DEFAULT 0 COMMENT '输入 token 累加',
    `total_output_tokens` BIGINT NOT NULL DEFAULT 0 COMMENT '输出 token 累加',
    `total_tokens`        BIGINT NOT NULL DEFAULT 0 COMMENT '总 token 累加',
    `total_cost_usd`      DECIMAL(18,8) NOT NULL DEFAULT 0 COMMENT '成本累加，美元',
    `start_time`          DATETIME(3) DEFAULT NULL COMMENT '首个步骤开始时间',
    `end_time`            DATETIME(3) DEFAULT NULL COMMENT '末个步骤结束时间',
    `error_msg`           TEXT DEFAULT NULL COMMENT '最后一条错误信息',
    `deleted`             TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `create_time`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_user`         VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `update_time`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `update_user`         VARCHAR(64) DEFAULT NULL COMMENT '修改人',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ai_trace_trace_id` (`trace_id`),
    KEY `idx_ai_trace_tenant_time` (`tenant_id`, `create_time`),
    KEY `idx_ai_trace_agent` (`agent_id`),
    KEY `idx_ai_trace_status` (`status`),
    KEY `idx_ai_trace_create_time` (`create_time`),
    KEY `idx_ai_trace_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='调用链路 Trace 头表';

CREATE TABLE IF NOT EXISTS `ai_trace_step` (
    `id`            BIGINT NOT NULL COMMENT '主键，MyBatis-Plus 雪花算法生成',
    `trace_id`      VARCHAR(128) NOT NULL COMMENT '所属 trace',
    `tenant_id`     VARCHAR(64) DEFAULT NULL COMMENT '租户 ID',
    `user_id`       VARCHAR(64) DEFAULT NULL COMMENT '用户 ID',
    `step_index`    INT NOT NULL DEFAULT 0 COMMENT '在 trace 内的有序序号，从 0 开始',
    `step_type`     VARCHAR(32) NOT NULL COMMENT '步骤类型：LLM / KB_SEARCH / RERANK / TOOL_CALL / PLUGIN_TRIGGER',
    `title`         VARCHAR(255) DEFAULT NULL COMMENT '步骤主标题，如「推理·决定调用工具」',
    `sub_title`     VARCHAR(512) DEFAULT NULL COMMENT '步骤副标题，如模型名 / top_k=5 命中4个分片',
    `model`         VARCHAR(128) DEFAULT NULL COMMENT '模型名（LLM/Re-rank 步骤）',
    `duration_ms`   INT DEFAULT NULL COMMENT '步骤耗时，毫秒',
    `input_tokens`  INT DEFAULT NULL COMMENT '输入 token',
    `output_tokens` INT DEFAULT NULL COMMENT '输出 token',
    `total_tokens`  INT DEFAULT NULL COMMENT '总 token',
    `cost_usd`      DECIMAL(18,8) DEFAULT NULL COMMENT '成本，美元',
    `status`        VARCHAR(16) NOT NULL DEFAULT 'SUCCESS' COMMENT '步骤状态：SUCCESS / WARN / ERROR',
    `error_msg`     TEXT DEFAULT NULL COMMENT '错误信息',
    `ref_log_id`    BIGINT DEFAULT NULL COMMENT '关联 ai_model_call_log.id（模型步骤可回查请求/响应 body）',
    `metadata`      JSON DEFAULT NULL COMMENT '扩展字段：topK / hits / toolName / target / rows / channel 等',
    `step_time`     DATETIME(3) DEFAULT NULL COMMENT '步骤开始时间',
    `deleted`       TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_user`   VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `update_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `update_user`   VARCHAR(64) DEFAULT NULL COMMENT '修改人',
    PRIMARY KEY (`id`),
    KEY `idx_ai_trace_step_trace` (`trace_id`, `step_index`),
    KEY `idx_ai_trace_step_type` (`step_type`),
    KEY `idx_ai_trace_step_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='调用链路 Trace 步骤明细表';
