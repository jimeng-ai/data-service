-- MySQL DDL for data-service persistence entities.
-- Source entities:
--   common/common-persistence/src/main/java/com/jimeng/persistence/BaseEntity.java
--   common/common-persistence/src/main/java/com/jimeng/persistence/entity/*.java
-- Database name follows nacos_config/default-mysql.yml:
--   jdbc:mysql://localhost:3306/base-service

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS `base-service`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE `base-service`;

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

SET FOREIGN_KEY_CHECKS = 1;
