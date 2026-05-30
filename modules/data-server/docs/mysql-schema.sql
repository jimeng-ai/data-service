-- MySQL DDL for data-service persistence entities.
-- Source entities:
--   common/common-persistence/src/main/java/com/jimeng/persistence/BaseEntity.java
--   common/common-persistence/src/main/java/com/jimeng/persistence/entity/*.java
-- Database name follows nacos_config/default-mysql.yml:
--   jdbc:mysql://localhost:3306/data-service

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS `data-server`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE `data-server`;

CREATE TABLE IF NOT EXISTS `sys_env` (
    `id` BIGINT NOT NULL COMMENT '主键，MyBatis-Plus 雪花算法生成',
    `module_name` VARCHAR(128) DEFAULT NULL COMMENT '模块名称/分组',
    `name` VARCHAR(128) DEFAULT NULL COMMENT '环境变量名称',
    `property_name` VARCHAR(255) DEFAULT NULL COMMENT '属性名称/配置 key',
    `property_value` TEXT DEFAULT NULL COMMENT '属性值/配置 value',
    `remark` VARCHAR(512) DEFAULT NULL COMMENT '备注说明',
    `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_user` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `update_user` VARCHAR(64) DEFAULT NULL COMMENT '修改人',
    PRIMARY KEY (`id`),
    KEY `idx_sys_env_module_name` (`module_name`),
    KEY `idx_sys_env_property_name` (`property_name`),
    KEY `idx_sys_env_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统环境变量表';

CREATE TABLE IF NOT EXISTS `sys_dict` (
    `id` BIGINT NOT NULL COMMENT '主键，MyBatis-Plus 雪花算法生成',
    `group_code` VARCHAR(128) DEFAULT NULL COMMENT '分组编码',
    `dict_key` VARCHAR(128) DEFAULT NULL COMMENT '字典 key',
    `dict_value` VARCHAR(512) DEFAULT NULL COMMENT '字典值',
    `user_id` BIGINT DEFAULT NULL COMMENT '用户 ID',
    `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_user` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `update_user` VARCHAR(64) DEFAULT NULL COMMENT '修改人',
    PRIMARY KEY (`id`),
    KEY `idx_sys_dict_group_code` (`group_code`),
    KEY `idx_sys_dict_dict_key` (`dict_key`),
    KEY `idx_sys_dict_user_id` (`user_id`),
    KEY `idx_sys_dict_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统字典表';

CREATE TABLE IF NOT EXISTS `adcode_citycode_dict` (
    `id` BIGINT NOT NULL COMMENT '主键，MyBatis-Plus 雪花算法生成',
    `sort_no` INT DEFAULT NULL COMMENT '序号',
    `name_cn` VARCHAR(128) DEFAULT NULL COMMENT '中文名',
    `adcode` VARCHAR(32) DEFAULT NULL COMMENT '高德 adcode',
    `citycode` VARCHAR(32) DEFAULT NULL COMMENT '高德 citycode',
    `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_user` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `update_user` VARCHAR(64) DEFAULT NULL COMMENT '修改人',
    PRIMARY KEY (`id`),
    KEY `idx_adcode_citycode_dict_sort_no` (`sort_no`),
    KEY `idx_adcode_citycode_dict_name_cn` (`name_cn`),
    KEY `idx_adcode_citycode_dict_adcode` (`adcode`),
    KEY `idx_adcode_citycode_dict_citycode` (`citycode`),
    KEY `idx_adcode_citycode_dict_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='高德行政区 adcode-citycode 字典表';

CREATE TABLE IF NOT EXISTS `poi_category_dict` (
    `id` BIGINT NOT NULL COMMENT '主键，MyBatis-Plus 雪花算法生成',
    `sort_no` INT DEFAULT NULL COMMENT '序号',
    `new_type` VARCHAR(32) DEFAULT NULL COMMENT '高德 NEW_TYPE 编码',
    `big_category_cn` VARCHAR(128) DEFAULT NULL COMMENT '大类中文名',
    `mid_category_cn` VARCHAR(128) DEFAULT NULL COMMENT '中类中文名',
    `sub_category_cn` VARCHAR(128) DEFAULT NULL COMMENT '小类中文名',
    `big_category_en` VARCHAR(128) DEFAULT NULL COMMENT '大类英文名',
    `mid_category_en` VARCHAR(128) DEFAULT NULL COMMENT '中类英文名',
    `sub_category_en` VARCHAR(128) DEFAULT NULL COMMENT '小类英文名',
    `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_user` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `update_user` VARCHAR(64) DEFAULT NULL COMMENT '修改人',
    PRIMARY KEY (`id`),
    KEY `idx_poi_category_dict_sort_no` (`sort_no`),
    KEY `idx_poi_category_dict_new_type` (`new_type`),
    KEY `idx_poi_category_dict_category_cn` (`big_category_cn`, `mid_category_cn`, `sub_category_cn`),
    KEY `idx_poi_category_dict_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='高德 POI 分类与编码表';

CREATE TABLE IF NOT EXISTS `ai_model_call_log` (
    `id` BIGINT NOT NULL COMMENT '主键，MyBatis-Plus 雪花算法生成',
    `trace_id` VARCHAR(128) DEFAULT NULL COMMENT '链路追踪 ID',
    `request_id` VARCHAR(128) DEFAULT NULL COMMENT '请求 ID',
    `biz_type` VARCHAR(64) DEFAULT NULL COMMENT '业务类型',
    `biz_id` VARCHAR(128) DEFAULT NULL COMMENT '业务 ID',
    `scene_code` VARCHAR(64) DEFAULT NULL COMMENT '场景编码',
    `tenant_id` VARCHAR(64) DEFAULT NULL COMMENT '租户 ID',
    `user_id` VARCHAR(64) DEFAULT NULL COMMENT '用户 ID',
    `provider` VARCHAR(64) DEFAULT NULL COMMENT '模型服务商',
    `model` VARCHAR(128) DEFAULT NULL COMMENT '模型名称',
    `endpoint` VARCHAR(512) DEFAULT NULL COMMENT '调用端点',
    `stream` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否流式调用',
    `has_text` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否包含文本',
    `has_image` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否包含图片',
    `has_document` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否包含文档',
    `has_tool` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否包含工具调用',
    `tool_names` VARCHAR(1024) DEFAULT NULL COMMENT '工具名称列表',
    `max_tokens` INT DEFAULT NULL COMMENT '最大 token 数',
    `temperature` DECIMAL(10,4) DEFAULT NULL COMMENT 'temperature 参数',
    `top_p` DECIMAL(10,4) DEFAULT NULL COMMENT 'top_p 参数',
    `input_tokens` INT DEFAULT NULL COMMENT '输入 token 数',
    `output_tokens` INT DEFAULT NULL COMMENT '输出 token 数',
    `total_tokens` INT DEFAULT NULL COMMENT '总 token 数',
    `latency_ms` INT DEFAULT NULL COMMENT '总耗时，毫秒',
    `first_token_ms` INT DEFAULT NULL COMMENT '首 token 耗时，毫秒',
    `retry_count` INT DEFAULT NULL COMMENT '重试次数',
    `http_status` INT DEFAULT NULL COMMENT 'HTTP 状态码',
    `call_status` INT DEFAULT NULL COMMENT '调用状态',
    `error_code` VARCHAR(128) DEFAULT NULL COMMENT '错误码',
    `error_msg` TEXT DEFAULT NULL COMMENT '错误信息',
    `cost_usd` DECIMAL(18,8) DEFAULT NULL COMMENT '调用成本，美元',
    `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_user` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `update_user` VARCHAR(64) DEFAULT NULL COMMENT '修改人',
    PRIMARY KEY (`id`),
    KEY `idx_ai_model_call_log_trace_id` (`trace_id`),
    KEY `idx_ai_model_call_log_request_id` (`request_id`),
    KEY `idx_ai_model_call_log_biz` (`biz_type`, `biz_id`),
    KEY `idx_ai_model_call_log_tenant_user` (`tenant_id`, `user_id`),
    KEY `idx_ai_model_call_log_provider_model` (`provider`, `model`),
    KEY `idx_ai_model_call_log_status` (`call_status`, `http_status`),
    KEY `idx_ai_model_call_log_create_time` (`create_time`),
    KEY `idx_ai_model_call_log_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型调用日志主表';

CREATE TABLE IF NOT EXISTS `ai_model_call_content` (
    `id` BIGINT NOT NULL COMMENT '主键，MyBatis-Plus 雪花算法生成',
    `log_id` BIGINT NOT NULL COMMENT '模型调用日志 ID',
    `req_headers` TEXT DEFAULT NULL COMMENT '请求头',
    `req_body` LONGTEXT DEFAULT NULL COMMENT '请求体',
    `resp_body` LONGTEXT DEFAULT NULL COMMENT '响应体',
    `stream_events` LONGTEXT DEFAULT NULL COMMENT '流式事件',
    `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_user` VARCHAR(64) DEFAULT NULL COMMENT '创建人',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `update_user` VARCHAR(64) DEFAULT NULL COMMENT '修改人',
    PRIMARY KEY (`id`),
    KEY `idx_ai_model_call_content_log_id` (`log_id`),
    KEY `idx_ai_model_call_content_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型调用日志内容表';

CREATE TABLE IF NOT EXISTS `knowledge_base` (
                                                `id`          BIGINT       NOT NULL                COMMENT '主键（雪花 ID）',
                                                `tenant_id`   VARCHAR(64)  NOT NULL DEFAULT 'default' COMMENT '租户 ID（与 X-Tenant-Id 对齐）',
                                                `name`        VARCHAR(128) NOT NULL                COMMENT '知识库名称',
    `description` VARCHAR(512) NULL                    COMMENT '描述',
    `deleted`     TINYINT(1)   NOT NULL DEFAULT 0     COMMENT '逻辑删除',
    `create_time` DATETIME     NULL                    COMMENT '创建时间',
    `create_user` VARCHAR(64)  NULL                    COMMENT '创建人',
    `update_time` DATETIME     NULL                    COMMENT '更新时间',
    `update_user` VARCHAR(64)  NULL                    COMMENT '更新人',
    PRIMARY KEY (`id`),
    KEY `idx_kb_name` (`name`),
    KEY `idx_kb_tenant` (`tenant_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG 知识库';

CREATE TABLE IF NOT EXISTS `kb_document` (
                                             `id`                 BIGINT       NOT NULL          COMMENT '主键',
                                             `kb_id`              BIGINT       NOT NULL          COMMENT '所属知识库 ID',
                                             `title`              VARCHAR(512) NULL              COMMENT '文档标题（默认取文件名）',
    `source_type`        VARCHAR(32)  NULL              COMMENT 'pdf / docx / md / html / txt',
    `minio_bucket`       VARCHAR(128) NULL              COMMENT 'MinIO bucket',
    `minio_object`       VARCHAR(512) NULL              COMMENT 'MinIO object key',
    `file_hash`          CHAR(64)     NULL              COMMENT '文件内容 sha256（去重幂等）',
    `status`             VARCHAR(32)  NOT NULL          COMMENT '状态：UPLOADED/PARSING/.../DONE/FAILED',
    `failure_reason`     TEXT         NULL              COMMENT '失败原因',
    `total_chunks`       INT          NULL              COMMENT '总切片数',
    `total_tokens`       INT          NULL              COMMENT '总 token 数',
    `ingestion_metadata` JSON         NULL              COMMENT '入库阶段统计数据',
    `deleted`            TINYINT(1)   NOT NULL DEFAULT 0,
    `create_time`        DATETIME     NULL,
    `create_user`        VARCHAR(64)  NULL,
    `update_time`        DATETIME     NULL,
    `update_user`        VARCHAR(64)  NULL,
    PRIMARY KEY (`id`),
    KEY `idx_kb_status` (`kb_id`, `status`),
    UNIQUE KEY `uk_kb_hash` (`kb_id`, `file_hash`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG 文档元数据';

CREATE TABLE IF NOT EXISTS `kb_chunk` (
                                          `id`                     BIGINT       NOT NULL    COMMENT '主键',
                                          `chunk_id`               VARCHAR(64)  NOT NULL    COMMENT 'ES doc id (doc_id_chunkIdx)',
    `doc_id`                 BIGINT       NOT NULL    COMMENT '所属文档 ID',
    `kb_id`                  BIGINT       NOT NULL    COMMENT '所属知识库 ID',
    `chunk_index`            INT          NOT NULL    COMMENT 'chunk 顺序号',
    `chunk_type`             VARCHAR(16)  NOT NULL    COMMENT 'text / table / image / code',
    `heading_path`           VARCHAR(512) NULL        COMMENT '"Ch1 > Sec1.2 > Sub"',
    `page_num`               INT          NULL        COMMENT '所在页码（PDF）',
    `content`                MEDIUMTEXT   NOT NULL    COMMENT '原始 chunk 文本',
    `contextualized_content` MEDIUMTEXT   NULL        COMMENT 'LLM 加上下文后的版本（BM25/embedding 用）',
    `image_url`              VARCHAR(512) NULL        COMMENT 'chunk_type=image 时存图片地址',
    `token_count`            INT          NULL        COMMENT '估算 token 数',
    `deleted`                TINYINT(1)   NOT NULL DEFAULT 0,
    `create_time`            DATETIME     NULL,
    `create_user`            VARCHAR(64)  NULL,
    `update_time`            DATETIME     NULL,
    `update_user`            VARCHAR(64)  NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chunk_id` (`chunk_id`),
    KEY `idx_doc` (`doc_id`),
    KEY `idx_kb` (`kb_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG 文档切片（与 ES 双写）';

-- ============================================================================
-- Agent 平台 / HTTP 插件系统（多租户）
-- 设计文档：~/.claude/plans/agent-agent-indexed-feather.md
-- 租户隔离：所有表都带 tenant_id (VARCHAR(64))，与 ai_model_call_log.tenant_id 对齐；
-- 数据访问层由 MyBatis-Plus TenantLineInnerInterceptor 自动注入过滤。
-- ============================================================================

-- 插件主表：定义一个对外的 HTTP API 插件
CREATE TABLE IF NOT EXISTS `plugin` (
    `id`              BIGINT       NOT NULL                COMMENT '主键，MyBatis-Plus 雪花算法生成',
    `tenant_id`       VARCHAR(64)  NOT NULL                COMMENT '租户 ID（来自 X-Tenant-Id）',
    `code`            VARCHAR(64)  NOT NULL                COMMENT '插件 slug，租户内唯一，如 jira / weather',
    `name`            VARCHAR(128) NOT NULL                COMMENT '插件展示名',
    `description`     VARCHAR(1024) DEFAULT NULL           COMMENT '插件描述（给 LLM 看的简介）',
    `version`         VARCHAR(32)  DEFAULT NULL            COMMENT '插件版本',
    `base_url`        VARCHAR(512) DEFAULT NULL            COMMENT '默认 base URL（可被 url_template 覆盖）',
    `auth_type`       VARCHAR(32)  NOT NULL DEFAULT 'NONE' COMMENT '认证类型：NONE/API_KEY/BEARER/BASIC/HMAC',
    `auth_config`     JSON         DEFAULT NULL            COMMENT '认证非密配置（位置/算法/签名模板等）',
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT/PUBLISHED/DISABLED',
    `owner_id`        VARCHAR(64)  DEFAULT NULL            COMMENT '插件所有者用户 ID',
    `deleted`         TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除，1-已删除',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_user`     VARCHAR(64)  DEFAULT NULL            COMMENT '创建人',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `update_user`     VARCHAR(64)  DEFAULT NULL            COMMENT '修改人',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_plugin_tenant_code` (`tenant_id`, `code`),
    KEY `idx_plugin_tenant_status` (`tenant_id`, `status`),
    KEY `idx_plugin_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='插件主表';

-- 插件下的工具（一对多）
CREATE TABLE IF NOT EXISTS `plugin_tool` (
    `id`              BIGINT       NOT NULL                COMMENT '主键',
    `tenant_id`       VARCHAR(64)  NOT NULL                COMMENT '租户 ID',
    `plugin_id`       BIGINT       NOT NULL                COMMENT '所属插件 ID',
    `name`            VARCHAR(128) NOT NULL                COMMENT '工具名（喂给 LLM），租户内唯一，约定 <plugin_code>.<verb>.<noun>',
    `description`     TEXT         DEFAULT NULL            COMMENT '工具描述（LLM 判断何时调用）',
    `input_schema`    JSON         DEFAULT NULL            COMMENT 'Claude input_schema 格式的 JSON Schema',
    `enabled`         TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否启用：0-禁用，1-启用',
    `deleted`         TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_user`     VARCHAR(64)  DEFAULT NULL            COMMENT '创建人',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `update_user`     VARCHAR(64)  DEFAULT NULL            COMMENT '修改人',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_plugin_tool_tenant_name` (`tenant_id`, `name`),
    KEY `idx_plugin_tool_plugin_id` (`plugin_id`),
    KEY `idx_plugin_tool_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='插件工具表';

-- 工具与 HTTP 调用映射（一对一）
CREATE TABLE IF NOT EXISTS `plugin_http_mapping` (
    `id`                 BIGINT       NOT NULL                COMMENT '主键',
    `tenant_id`          VARCHAR(64)  NOT NULL                COMMENT '租户 ID',
    `plugin_tool_id`     BIGINT       NOT NULL                COMMENT '所属工具 ID',
    `method`             VARCHAR(8)   NOT NULL                COMMENT 'HTTP 方法：GET/POST/PUT/PATCH/DELETE',
    `url_template`       VARCHAR(1024) NOT NULL               COMMENT 'URL 模板，支持 {{namespace.path}} 占位',
    `headers_template`   JSON         DEFAULT NULL            COMMENT 'Header 模板（JSON 对象，值可含占位符）',
    `query_template`     JSON         DEFAULT NULL            COMMENT 'Query 参数模板',
    `body_template`      JSON         DEFAULT NULL            COMMENT 'Body 模板（节点树，叶子可含占位符）',
    `body_content_type`  VARCHAR(64)  DEFAULT 'application/json' COMMENT 'Body Content-Type',
    `response_extract`   TEXT         DEFAULT NULL            COMMENT '响应抽取：多字段映射 JSON 数组 [{name,path,type,desc}]，或旧版单条 JSONPath（如 $.main）；为空则返完整响应',
    `response_max_items` INT          DEFAULT 50              COMMENT '数组截断阈值，避免上下文爆炸',
    `timeout_ms`         INT          DEFAULT NULL            COMMENT 'HTTP 超时（毫秒），为空走全局默认',
    `deleted`            TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_user`        VARCHAR(64)  DEFAULT NULL            COMMENT '创建人',
    `update_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `update_user`        VARCHAR(64)  DEFAULT NULL            COMMENT '修改人',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_plugin_http_mapping_tool` (`plugin_tool_id`),
    KEY `idx_plugin_http_mapping_tenant` (`tenant_id`),
    KEY `idx_plugin_http_mapping_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='插件工具的 HTTP 调用映射';

-- 插件凭证（明文存储，预留 encryption_version 字段以便未来加密）
-- 约束：每个插件在租户内仅有一份凭证。
CREATE TABLE IF NOT EXISTS `plugin_credential` (
    `id`                  BIGINT       NOT NULL                COMMENT '主键',
    `tenant_id`           VARCHAR(64)  NOT NULL                COMMENT '租户 ID',
    `plugin_id`           BIGINT       NOT NULL                COMMENT '所属插件 ID',
    `owner_id`            VARCHAR(64)  DEFAULT NULL            COMMENT '所属用户 ID（NULL=租户内共享）',
    `credential_data`     TEXT         NOT NULL                COMMENT '凭证内容（明文 JSON 字符串）',
    `encryption_version`  INT          NOT NULL DEFAULT 0      COMMENT '加密版本：0=明文；预留未来加密',
    `deleted`             TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_user`         VARCHAR(64)  DEFAULT NULL            COMMENT '创建人',
    `update_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `update_user`         VARCHAR(64)  DEFAULT NULL            COMMENT '修改人',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_plugin_credential_tenant_plugin` (`tenant_id`, `plugin_id`),
    KEY `idx_plugin_credential_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='插件凭证表';

-- Agent 实体：岗位智能体
CREATE TABLE IF NOT EXISTS `agent` (
    `id`              BIGINT       NOT NULL                COMMENT '主键',
    `tenant_id`       VARCHAR(64)  NOT NULL                COMMENT '租户 ID',
    `code`            VARCHAR(64)  NOT NULL                COMMENT 'Agent slug，租户内唯一',
    `name`            VARCHAR(128) NOT NULL                COMMENT 'Agent 展示名',
    `description`     VARCHAR(1024) DEFAULT NULL           COMMENT 'Agent 描述',
    `avatar_url`      VARCHAR(512) DEFAULT NULL            COMMENT '头像 URL',
    `system_prompt`   TEXT         DEFAULT NULL            COMMENT '人设 / 系统提示词',
    `model`           VARCHAR(128) DEFAULT NULL            COMMENT '默认模型（如 claude-opus-4-1），请求体可 override',
    `model_params`    JSON         DEFAULT NULL            COMMENT '模型参数默认值 {temperature, max_tokens, ...}',
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT/PUBLISHED/DISABLED',
    `owner_id`        VARCHAR(64)  DEFAULT NULL            COMMENT 'Agent 所有者用户 ID',
    `kb_config`       JSON         DEFAULT NULL            COMMENT '知识库绑定配置 {kbIds, topK, scoreThreshold}',
    `deleted`         TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_user`     VARCHAR(64)  DEFAULT NULL            COMMENT '创建人',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `update_user`     VARCHAR(64)  DEFAULT NULL            COMMENT '修改人',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_tenant_code` (`tenant_id`, `code`),
    KEY `idx_agent_tenant_status` (`tenant_id`, `status`),
    KEY `idx_agent_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 实体表';

-- Agent 与插件多对多绑定
CREATE TABLE IF NOT EXISTS `agent_plugin` (
    `id`                 BIGINT       NOT NULL                COMMENT '主键',
    `tenant_id`          VARCHAR(64)  NOT NULL                COMMENT '租户 ID',
    `agent_id`           BIGINT       NOT NULL                COMMENT 'Agent ID',
    `plugin_id`          BIGINT       NOT NULL                COMMENT 'Plugin ID',
    `deleted`            TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `create_user`        VARCHAR(64)  DEFAULT NULL            COMMENT '创建人',
    `update_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    `update_user`        VARCHAR(64)  DEFAULT NULL            COMMENT '修改人',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_plugin_tenant_agent_plugin` (`tenant_id`, `agent_id`, `plugin_id`),
    KEY `idx_agent_plugin_agent` (`agent_id`),
    KEY `idx_agent_plugin_plugin` (`plugin_id`),
    KEY `idx_agent_plugin_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 与插件绑定表';

-- 管理后台账户表
-- 启动时 AdminAuthInitializer 会自动写入默认账号 admin / admin123（BCrypt 当场算 hash）
CREATE TABLE IF NOT EXISTS `sys_admin` (
    `id`            BIGINT       NOT NULL                       COMMENT '主键（雪花算法分配）',
    `tenant_id`     VARCHAR(64)  NOT NULL DEFAULT 'default'     COMMENT '所属租户 ID（登录后写入 JWT，gateway 注入 X-Tenant-Id）',
    `username`      VARCHAR(64)  NOT NULL                       COMMENT '登录名（全局唯一）',
    `password_hash` VARCHAR(128) NOT NULL                       COMMENT 'BCrypt 哈希',
    `display_name`  VARCHAR(64)                                 COMMENT '展示名',
    `status`        TINYINT      NOT NULL DEFAULT 1             COMMENT '1=启用 0=禁用',
    `last_login_at` DATETIME                                    COMMENT '最近登录时间',
    `deleted`       TINYINT      NOT NULL DEFAULT 0             COMMENT '0=未删除 1=已删除',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `create_user`   VARCHAR(64),
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `update_user`   VARCHAR(64),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_admin_username` (`username`, `deleted`),
    KEY `idx_sys_admin_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理后台账户';

-- 对话会话（控制台「对话」中的一段会话）
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

-- 对话消息（隶属于 chat_conversation）
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

-- ============================================================================
-- 多租户 RBAC（运营 / 企业 / 角色 / 资源授权 / 成员）—— 详见迁移 V20260601__rbac_multitenant.sql
-- ⚠ 本批 sys_* 表均不纳入 JimengTenantLineHandler.TENANT_AWARE_TABLES（运营跨租户 + 登录早于 TenantContext）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `sys_operator` (
    `id`            BIGINT       NOT NULL                COMMENT '主键（雪花）',
    `username`      VARCHAR(64)  NOT NULL                COMMENT '登录名（全局唯一）',
    `password_hash` VARCHAR(128) NOT NULL                COMMENT 'BCrypt 哈希',
    `display_name`  VARCHAR(64)                          COMMENT '展示名',
    `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=启用 0=禁用',
    `last_login_at` DATETIME                             COMMENT '最近登录时间',
    `deleted`       TINYINT(1)   NOT NULL DEFAULT 0,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `create_user`   VARCHAR(64),
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `update_user`   VARCHAR(64),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_operator_username` (`username`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='平台运营账号（跨租户）';

CREATE TABLE IF NOT EXISTS `sys_enterprise` (
    `id`            BIGINT       NOT NULL                COMMENT '主键（雪花）',
    `tenant_id`     VARCHAR(64)  NOT NULL                COMMENT '租户标识',
    `name`          VARCHAR(128) NOT NULL                COMMENT '企业名称',
    `description`   VARCHAR(512)                         COMMENT '描述',
    `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=启用 0=停用',
    `deleted`       TINYINT(1)   NOT NULL DEFAULT 0,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `create_user`   VARCHAR(64),
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `update_user`   VARCHAR(64),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_enterprise_tenant` (`tenant_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='企业（租户）';

CREATE TABLE IF NOT EXISTS `sys_user` (
    `id`            BIGINT       NOT NULL                COMMENT '主键（雪花）',
    `tenant_id`     VARCHAR(64)  NOT NULL                COMMENT '所属租户',
    `username`      VARCHAR(64)  NOT NULL                COMMENT '登录名（全局唯一）',
    `password_hash` VARCHAR(128) NOT NULL                COMMENT 'BCrypt 哈希',
    `display_name`  VARCHAR(64)                          COMMENT '展示名',
    `user_type`     VARCHAR(16)  NOT NULL DEFAULT 'MEMBER' COMMENT 'SUPER_ADMIN | MEMBER',
    `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=启用 0=禁用',
    `last_login_at` DATETIME                             COMMENT '最近登录时间',
    `deleted`       TINYINT(1)   NOT NULL DEFAULT 0,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `create_user`   VARCHAR(64),
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `update_user`   VARCHAR(64),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_user_username` (`username`, `deleted`),
    KEY `idx_sys_user_tenant` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='企业账号（超管/成员）';

CREATE TABLE IF NOT EXISTS `sys_role` (
    `id`            BIGINT       NOT NULL                COMMENT '主键（雪花）',
    `tenant_id`     VARCHAR(64)  NOT NULL                COMMENT '所属租户',
    `code`          VARCHAR(64)  NOT NULL                COMMENT '角色 slug，租户内唯一',
    `name`          VARCHAR(128) NOT NULL                COMMENT '角色名',
    `description`   VARCHAR(512)                         COMMENT '描述',
    `deleted`       TINYINT(1)   NOT NULL DEFAULT 0,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `create_user`   VARCHAR(64),
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `update_user`   VARCHAR(64),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_role_tenant_code` (`tenant_id`, `code`, `deleted`),
    KEY `idx_sys_role_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='企业自定义角色';

CREATE TABLE IF NOT EXISTS `sys_role_resource` (
    `id`            BIGINT       NOT NULL                COMMENT '主键（雪花）',
    `tenant_id`     VARCHAR(64)  NOT NULL                COMMENT '所属租户（冗余）',
    `role_id`       BIGINT       NOT NULL                COMMENT '角色 ID',
    `resource_type` VARCHAR(32)  NOT NULL                COMMENT 'MENU | AGENT | KNOWLEDGE_BASE | PLUGIN | ...',
    `resource_id`   BIGINT       NOT NULL DEFAULT 0      COMMENT '实例 id；MENU 类为 0',
    `resource_code` VARCHAR(128)                         COMMENT '模块码（MENU）或实例 code（冗余）',
    `deleted`       TINYINT(1)   NOT NULL DEFAULT 0,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `create_user`   VARCHAR(64),
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `update_user`   VARCHAR(64),
    PRIMARY KEY (`id`),
    -- 不设唯一键：service「全删全插」保证一致性；MENU 授权 resource_id 恒 0 + 逻辑删除会撞唯一键（见 V20260602）
    KEY `idx_role_res_role` (`role_id`),
    KEY `idx_role_res_type` (`tenant_id`, `resource_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色-资源授权';

CREATE TABLE IF NOT EXISTS `sys_user_role` (
    `id`            BIGINT       NOT NULL                COMMENT '主键（雪花）',
    `tenant_id`     VARCHAR(64)  NOT NULL                COMMENT '所属租户（冗余）',
    `user_id`       BIGINT       NOT NULL                COMMENT '成员 ID',
    `role_id`       BIGINT       NOT NULL                COMMENT '角色 ID',
    `deleted`       TINYINT(1)   NOT NULL DEFAULT 0,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `create_user`   VARCHAR(64),
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `update_user`   VARCHAR(64),
    PRIMARY KEY (`id`),
    -- 不设唯一键：service「全删全插」保证一致性；逻辑删除反复保存会撞唯一键（见 V20260602）
    KEY `idx_user_role_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='成员-角色绑定';

SET FOREIGN_KEY_CHECKS = 1;
