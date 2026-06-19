-- ai_skill：租户私有 skill（P1 仅 PROMPT；DOER 字段预留）。
-- 遵守本项目「业务表除主键外不加唯一键」约定：name 冲突由前端提示，不建唯一键。
CREATE TABLE `ai_skill` (
  `id`            BIGINT       NOT NULL COMMENT '雪花ID',
  `tenant_id`     VARCHAR(64)  NOT NULL COMMENT '租户ID（与 X-Tenant-Id 对齐）',
  `owner_user_id` BIGINT       NOT NULL COMMENT '创建者用户ID',
  `scope`         VARCHAR(16)  NOT NULL DEFAULT 'PRIVATE' COMMENT 'PRIVATE/TENANT',
  `name`          VARCHAR(64)  NOT NULL COMMENT 'frontmatter name',
  `description`   VARCHAR(1024) DEFAULT NULL COMMENT 'frontmatter description',
  `body`          LONGTEXT     DEFAULT NULL COMMENT 'SKILL.md 正文',
  `skill_type`    VARCHAR(16)  NOT NULL DEFAULT 'PROMPT' COMMENT 'PROMPT/DOER',
  `source`        VARCHAR(16)  NOT NULL DEFAULT 'UPLOAD' COMMENT 'UPLOAD/MARKET/AI_GEN',
  `origin_ref`    VARCHAR(512) DEFAULT NULL COMMENT '市面来源 owner/repo@ref:path',
  `status`        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'DRAFT/ACTIVE/DISABLED',
  `bundle_key`    VARCHAR(512) DEFAULT NULL COMMENT 'MinIO bundle 前缀(DOER)',
  `bundle_hash`   VARCHAR(80)  DEFAULT NULL COMMENT 'bundle sha256',
  `version`       INT          NOT NULL DEFAULT 1 COMMENT '版本',
  `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0/1',
  `create_time`   DATETIME     DEFAULT NULL,
  `create_user`   VARCHAR(64)  DEFAULT NULL,
  `update_time`   DATETIME     DEFAULT NULL,
  `update_user`   VARCHAR(64)  DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ai_skill_tenant_status_scope` (`tenant_id`,`status`,`scope`),
  KEY `idx_ai_skill_owner` (`tenant_id`,`owner_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Skill（租户私有）';
