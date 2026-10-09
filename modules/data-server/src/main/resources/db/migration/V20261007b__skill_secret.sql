-- 一切皆可 skills · S1：企业 skill 的 key（设计文档 §3.4）。
-- 发布时从 secrets.json 提取、用 CredentialCipher（密钥 CONNECTION_CREDENTIAL_KEY）加密存这里，secrets.json 不进 bundle。
-- 一个版本一组行：新版本没给值且发送范围没变时，沿用上一版的密文（复制一行），范围变了就必须重新给值。
-- 租户隔离表（JimengTenantLineHandler 已登记）。
--
-- ★ 必须先于 data-service 上线执行。没有 Flyway：这份 DDL 要手工执行（见 docs/config-changes/2026-10-07-s1-skill-keys-and-http-gateway.md）。

CREATE TABLE IF NOT EXISTS `skill_secret` (
  `id`              BIGINT       NOT NULL COMMENT '雪花ID',
  `tenant_id`       VARCHAR(64)  NOT NULL COMMENT '租户ID',
  `skill_id`        BIGINT       NOT NULL COMMENT 'ai_skill.id',
  `version`         INT          NOT NULL COMMENT '属于这个 skill 的哪一版',
  `name`            VARCHAR(64)  NOT NULL COMMENT 'key 名，占位符写 {{name}}',
  `hosts`           TEXT         NOT NULL COMMENT 'JSON 数组：只能发往这些域名',
  `methods`         TEXT         DEFAULT NULL COMMENT 'JSON 数组；NULL = 不限',
  `paths`           TEXT         DEFAULT NULL COMMENT 'JSON 数组；NULL = 不限',
  `ciphertext`      TEXT         NOT NULL COMMENT 'CredentialCipher 加密后的值',
  `cipher_version`  INT          NOT NULL COMMENT 'CredentialCipher 的版本',
  `deleted`         TINYINT      NOT NULL DEFAULT 0,
  `create_time`     DATETIME     DEFAULT NULL,
  `create_user`     VARCHAR(64)  DEFAULT NULL,
  `update_time`     DATETIME     DEFAULT NULL,
  `update_user`     VARCHAR(64)  DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_skill_secret` (`skill_id`, `version`, `name`),
  KEY `idx_skill_secret_tenant` (`tenant_id`, `skill_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='技能里的 key（加密保存）';
