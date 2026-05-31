-- 代码执行 / 文件处理 Agent（沙箱边车）：运行记录 / 输入文件 / 产物文件。
-- 三表均为租户隔离表（已加入 JimengTenantLineHandler.TENANT_AWARE_TABLES）。

CREATE TABLE IF NOT EXISTS `agent_exec_run` (
    `id`              BIGINT        NOT NULL                COMMENT '主键，雪花算法（即 runId）',
    `tenant_id`       VARCHAR(64)   NOT NULL                COMMENT '租户 ID',
    `conversation_id` BIGINT        DEFAULT NULL            COMMENT '所属会话 ID',
    `agent_id`        VARCHAR(64)   DEFAULT NULL            COMMENT '所属 Agent ID',
    `user_id`         VARCHAR(64)   DEFAULT NULL            COMMENT '发起用户 ID',
    `status`          VARCHAR(16)   NOT NULL DEFAULT 'RUNNING' COMMENT '状态 RUNNING/SUCCESS/FAILED',
    `input_tokens`    BIGINT        DEFAULT NULL            COMMENT '输入 token',
    `output_tokens`   BIGINT        DEFAULT NULL            COMMENT '输出 token',
    `total_tokens`    BIGINT        DEFAULT NULL            COMMENT '总 token',
    `cost_usd`        DECIMAL(12,6) DEFAULT NULL            COMMENT '估算成本 USD',
    `tool_rounds`     INT           DEFAULT NULL            COMMENT '工具调用轮数',
    `artifact_count`  INT           DEFAULT NULL            COMMENT '产物数量',
    `elapsed_ms`      BIGINT        DEFAULT NULL            COMMENT '总耗时（毫秒）',
    `error_msg`       VARCHAR(1024) DEFAULT NULL            COMMENT '失败原因',
    `deleted`         TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_user`     VARCHAR(64)   DEFAULT NULL            COMMENT '创建人',
    `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `update_user`     VARCHAR(64)   DEFAULT NULL            COMMENT '修改人',
    PRIMARY KEY (`id`),
    KEY `idx_agent_run_tenant` (`tenant_id`, `create_time`),
    KEY `idx_agent_run_conv` (`tenant_id`, `conversation_id`),
    KEY `idx_agent_run_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 代码执行运行记录';

CREATE TABLE IF NOT EXISTS `agent_input_file` (
    `id`              BIGINT        NOT NULL                COMMENT '主键，雪花算法',
    `tenant_id`       VARCHAR(64)   NOT NULL                COMMENT '租户 ID',
    `conversation_id` BIGINT        DEFAULT NULL            COMMENT '所属会话 ID',
    `bucket`          VARCHAR(128)  NOT NULL                COMMENT 'MinIO bucket',
    `object_name`     VARCHAR(512)  NOT NULL                COMMENT 'MinIO object name',
    `filename`        VARCHAR(255)  DEFAULT NULL            COMMENT '原始文件名',
    `content_type`    VARCHAR(128)  DEFAULT NULL            COMMENT 'Content-Type',
    `size_bytes`      BIGINT        DEFAULT NULL            COMMENT '文件大小（字节）',
    `deleted`         TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_user`     VARCHAR(64)   DEFAULT NULL            COMMENT '创建人',
    `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `update_user`     VARCHAR(64)   DEFAULT NULL            COMMENT '修改人',
    PRIMARY KEY (`id`),
    KEY `idx_agent_input_tenant` (`tenant_id`, `create_time`),
    KEY `idx_agent_input_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 输入文件';

CREATE TABLE IF NOT EXISTS `agent_artifact` (
    `id`              BIGINT        NOT NULL                COMMENT '主键，雪花算法',
    `tenant_id`       VARCHAR(64)   NOT NULL                COMMENT '租户 ID',
    `run_id`          BIGINT        NOT NULL                COMMENT '所属运行 ID',
    `message_id`      BIGINT        DEFAULT NULL            COMMENT '所属消息 ID',
    `bucket`          VARCHAR(128)  NOT NULL                COMMENT 'MinIO bucket',
    `object_name`     VARCHAR(512)  NOT NULL                COMMENT 'MinIO object name',
    `filename`        VARCHAR(255)  DEFAULT NULL            COMMENT '文件名',
    `content_type`    VARCHAR(128)  DEFAULT NULL            COMMENT 'Content-Type',
    `size_bytes`      BIGINT        DEFAULT NULL            COMMENT '文件大小（字节）',
    `deleted`         TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_user`     VARCHAR(64)   DEFAULT NULL            COMMENT '创建人',
    `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `update_user`     VARCHAR(64)   DEFAULT NULL            COMMENT '修改人',
    PRIMARY KEY (`id`),
    KEY `idx_agent_artifact_run` (`tenant_id`, `run_id`),
    KEY `idx_agent_artifact_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 产物文件';
