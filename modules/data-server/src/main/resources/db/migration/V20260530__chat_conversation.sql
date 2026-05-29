-- 「对话」会话与消息落库。
-- chat_conversation：一段会话（归属某个 Agent，租户隔离）。
-- chat_message：会话内的消息（user / assistant）。
-- 两张表均已加入多租户拦截器白名单（JimengTenantLineHandler），tenant_id 由拦截器自动注入。

CREATE TABLE IF NOT EXISTS `chat_conversation` (
    `id`              BIGINT       NOT NULL                COMMENT '主键，雪花算法',
    `tenant_id`       VARCHAR(64)  NOT NULL                COMMENT '租户 ID',
    `agent_id`        VARCHAR(64)  NOT NULL                COMMENT '所属 Agent ID',
    `agent_name`      VARCHAR(128) DEFAULT NULL            COMMENT 'Agent 名称快照',
    `title`           VARCHAR(255) NOT NULL DEFAULT '新对话' COMMENT '会话标题',
    `last_message_at` DATETIME     DEFAULT NULL            COMMENT '最近一条消息时间',
    `deleted`         TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_user`     VARCHAR(64)  DEFAULT NULL            COMMENT '创建人',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `update_user`     VARCHAR(64)  DEFAULT NULL            COMMENT '修改人',
    PRIMARY KEY (`id`),
    KEY `idx_chat_conv_tenant_active` (`tenant_id`, `last_message_at`),
    KEY `idx_chat_conv_agent` (`tenant_id`, `agent_id`),
    KEY `idx_chat_conv_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话会话表';

CREATE TABLE IF NOT EXISTS `chat_message` (
    `id`              BIGINT       NOT NULL                COMMENT '主键，雪花算法',
    `tenant_id`       VARCHAR(64)  NOT NULL                COMMENT '租户 ID',
    `conversation_id` BIGINT       NOT NULL                COMMENT '所属会话 ID',
    `role`            VARCHAR(16)  NOT NULL                COMMENT '角色：user / assistant',
    `content`         LONGTEXT                             COMMENT '消息正文',
    `citations`       JSON         DEFAULT NULL            COMMENT '引用列表（JSON，可空）',
    `deleted`         TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_user`     VARCHAR(64)  DEFAULT NULL            COMMENT '创建人',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `update_user`     VARCHAR(64)  DEFAULT NULL            COMMENT '修改人',
    PRIMARY KEY (`id`),
    KEY `idx_chat_msg_conv` (`conversation_id`, `create_time`),
    KEY `idx_chat_msg_tenant` (`tenant_id`),
    KEY `idx_chat_msg_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话消息表';
