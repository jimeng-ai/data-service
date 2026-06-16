-- 可选模型目录（平台级，运营维护）。原先在 Nacos ai.model-catalog.models + ModelPricing 硬编码价，
-- 现统一迁到本表：目录(展示/启用/maxTemp) + 计费单价 + 上游路由(provider 引用名 + upstream_model) 一处定义。
-- 平台级、全租户共享：不进 TENANT_AWARE_TABLES，运营经 runAsSystem 维护。
-- value 不加唯一键（遵循「业务表除主键外禁唯一键」约定），重名在应用层/前端拦截。
CREATE TABLE IF NOT EXISTS `ai_model` (
    `id`                BIGINT       NOT NULL COMMENT '雪花ID',
    `value`             VARCHAR(128) NOT NULL COMMENT '逻辑模型id（下拉 value、Agent 存的就是它）',
    `label`             VARCHAR(128) NOT NULL COMMENT '展示名',
    `description`       VARCHAR(512) DEFAULT NULL COMMENT '选型提示',
    `protocol`          VARCHAR(32)  NOT NULL DEFAULT 'anthropic' COMMENT 'anthropic | openai（须与所选连接协议一致）',
    `provider`          VARCHAR(64)  NOT NULL COMMENT '引用 Nacos providers.* 的连接名，如 302ai',
    `upstream_model`    VARCHAR(128) NOT NULL COMMENT '真正下发给上游的模型名',
    `max_temp`          DECIMAL(3,2) NOT NULL DEFAULT 1.00 COMMENT 'temperature 上限：anthropic=1 openai=2',
    `price_input`       DECIMAL(12,4) NOT NULL DEFAULT 0 COMMENT 'USD/百万token 输入价',
    `price_output`      DECIMAL(12,4) NOT NULL DEFAULT 0 COMMENT 'USD/百万token 输出价',
    `price_cache_read`  DECIMAL(12,4) NOT NULL DEFAULT 0 COMMENT 'USD/百万token 缓存读价',
    `price_cache_write` DECIMAL(12,4) NOT NULL DEFAULT 0 COMMENT 'USD/百万token 缓存写价',
    `enabled`           TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '下线置0，不删行',
    `sort`              INT          NOT NULL DEFAULT 0 COMMENT '下拉排序，小在前',
    `deleted`           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-未删 1-已删',
    `create_time`       DATETIME     DEFAULT NULL COMMENT '创建时间',
    `create_user`       VARCHAR(64)  DEFAULT NULL COMMENT '创建人',
    `update_time`       DATETIME     DEFAULT NULL COMMENT '修改时间',
    `update_user`       VARCHAR(64)  DEFAULT NULL COMMENT '修改人',
    PRIMARY KEY (`id`),
    KEY `idx_enabled_sort` (`enabled`, `sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可选模型目录（平台级，运营维护）';

-- Seed：当前 Nacos 目录 4 条 + ModelPricing 对应档位价。
-- upstream_model 先 = value（当前 302ai 直连场景一致），provider=302ai，protocol=anthropic。
INSERT INTO `ai_model`
    (`id`, `value`, `label`, `description`, `protocol`, `provider`, `upstream_model`,
     `max_temp`, `price_input`, `price_output`, `price_cache_read`, `price_cache_write`,
     `enabled`, `sort`, `deleted`, `create_time`, `create_user`, `update_time`, `update_user`)
VALUES
    (970010000000000001, 'claude-opus-4-8', 'Claude Opus 4.8', '最新旗舰；复杂推理、长文档、高质量人设设计的首选',
     'anthropic', '302ai', 'claude-opus-4-8', 1.00, 15.0000, 75.0000, 1.5000, 18.7500, 1, 0, 0, NOW(), 'system', NOW(), 'system'),
    (970010000000000005, 'claude-opus-4-7', 'Claude Opus 4.7', '上一代旗舰；复杂推理/长文档高质量场景',
     'anthropic', '302ai', 'claude-opus-4-7', 1.00, 15.0000, 75.0000, 1.5000, 18.7500, 1, 5, 0, NOW(), 'system', NOW(), 'system'),
    (970010000000000002, 'claude-opus-4-6', 'Claude Opus 4.6', '复杂推理、长文档、高质量人设设计；成本最高，适合质量要求高的 Agent',
     'anthropic', '302ai', 'claude-opus-4-6', 1.00, 15.0000, 75.0000, 1.5000, 18.7500, 1, 10, 0, NOW(), 'system', NOW(), 'system'),
    (970010000000000003, 'claude-sonnet-4-6', 'Claude Sonnet 4.6', '均衡之选；日常客服/助手类 Agent 首选，性价比高',
     'anthropic', '302ai', 'claude-sonnet-4-6', 1.00, 3.0000, 15.0000, 0.3000, 3.7500, 1, 20, 0, NOW(), 'system', NOW(), 'system'),
    (970010000000000004, 'claude-haiku-4-5-20251001', 'Claude Haiku 4.5', '高频、低延迟、低成本；适合简单问答/分类类 Agent',
     'anthropic', '302ai', 'claude-haiku-4-5-20251001', 1.00, 0.8000, 4.0000, 0.0800, 1.0000, 1, 30, 0, NOW(), 'system', NOW(), 'system');
