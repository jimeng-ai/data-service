-- Skill 构建器改为「在沙箱里原样跑 Anthropic skill-creator」（jm-agent-sandbox 的 skill-builder 运行形态）。
--
-- 旧构建器的草稿只活在 JVM 内存里（SkillDraftStore，一次部署全丢）；新构建器的全部状态在 MinIO 工作区里，
-- 这张表只记会话 → 对话 / 工作区 / 目标 skill 的映射。两张表都是租户隔离表（JimengTenantLineHandler 已登记）。
--
-- 没有 Flyway：这份 DDL 要手工执行（见 docs/RELEASE-CHECKLIST 的 schema 自检）。

CREATE TABLE IF NOT EXISTS `skill_builder_session` (
  `id`                  BIGINT       NOT NULL COMMENT '雪花ID',
  `tenant_id`           VARCHAR(64)  NOT NULL COMMENT '租户ID',
  `owner_user_id`       BIGINT       NOT NULL COMMENT '会话属主',
  `conversation_id`     BIGINT       NOT NULL COMMENT '构建器对话 chat_conversation.id',
  `draft_skill_id`      BIGINT       DEFAULT NULL COMMENT '新建 skill 时的 DRAFT ai_skill 行',
  `base_skill_id`       BIGINT       DEFAULT NULL COMMENT '改进已有 skill 时的目标 skill',
  `base_version`        INT          DEFAULT NULL COMMENT '会话开始时目标 skill 的版本（发布时乐观校验）',
  `workspace_prefix`    VARCHAR(512) NOT NULL COMMENT 'MinIO 工作区前缀，以 / 结尾',
  `skill_type_override` VARCHAR(16)  DEFAULT NULL COMMENT '用户指定的运行方式 PROMPT/DOER；NULL=按附带文件推断',
  `status`              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/PUBLISHED/ABANDONED',
  `published_skill_id`  BIGINT       DEFAULT NULL COMMENT '发布出的 skill',
  `last_run_id`         VARCHAR(64)  DEFAULT NULL COMMENT '最近一轮的 runId',
  `deleted`             TINYINT      NOT NULL DEFAULT 0,
  `create_time`         DATETIME     DEFAULT NULL,
  `create_user`         VARCHAR(64)  DEFAULT NULL,
  `update_time`         DATETIME     DEFAULT NULL,
  `update_user`         VARCHAR(64)  DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_builder_session_conversation` (`conversation_id`),
  KEY `idx_skill_builder_session_owner` (`tenant_id`, `owner_user_id`, `status`),
  KEY `idx_skill_builder_session_base` (`tenant_id`, `base_skill_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill 构建器会话';

CREATE TABLE IF NOT EXISTS `ai_skill_version` (
  `id`                  BIGINT        NOT NULL COMMENT '雪花ID',
  `tenant_id`           VARCHAR(64)   NOT NULL COMMENT '租户ID',
  `skill_id`            BIGINT        NOT NULL COMMENT 'ai_skill.id',
  `version`             INT           NOT NULL COMMENT '版本号，从 1 起',
  `name`                VARCHAR(64)   NOT NULL,
  `description`         VARCHAR(1024) DEFAULT NULL,
  `skill_type`          VARCHAR(16)   NOT NULL COMMENT 'PROMPT/DOER',
  `bundle_key`          VARCHAR(512)  DEFAULT NULL COMMENT '这一版 bundle 的 MinIO 前缀 skills/{id}/{version}/',
  `bundle_hash`         VARCHAR(80)   DEFAULT NULL COMMENT 'bundle 全部文件的 sha256',
  `builder_session_id`  BIGINT        DEFAULT NULL COMMENT '产出这一版的构建器会话',
  `deleted`             TINYINT       NOT NULL DEFAULT 0,
  `create_time`         DATETIME      DEFAULT NULL,
  `create_user`         VARCHAR(64)   DEFAULT NULL,
  `update_time`         DATETIME      DEFAULT NULL,
  `update_user`         VARCHAR(64)   DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_skill_version` (`skill_id`, `version`),
  KEY `idx_ai_skill_version_tenant` (`tenant_id`, `skill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Skill 版本历史';
