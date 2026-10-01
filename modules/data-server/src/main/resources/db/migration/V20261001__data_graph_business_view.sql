-- 数据星图 v3：给人看的业务视图 + 语义层补全链的运行状态（设计文档 docs/superpowers/specs/2026-09-30-enterprise-data-graph-design.md §6、附录 D）。
--
-- 没有 Flyway：这份 DDL 要手工执行（见 docs/RELEASE-CHECKLIST-connector.md 的 ① 与 ⑥ schema 自检）。
-- 两张表都带 tenant_id，已登记进 JimengTenantLineHandler.TENANT_AWARE_TABLES。
-- CREATE TABLE IF NOT EXISTS，重复执行无害。
--
-- 唯一键刻意【不含 deleted】，做法同 connector_semantic：MODEL 行物理删除，HUMAN 行只原地更新，
-- 表上永远不出现软删死行。不要给这两张表加逻辑删除入口。deleted 列只是 BaseEntity 的全局 @TableLogic 要求它存在。

CREATE TABLE IF NOT EXISTS `connector_business_view` (
  `id`             BIGINT        NOT NULL COMMENT '雪花ID',
  `tenant_id`      VARCHAR(64)   NOT NULL COMMENT '租户ID',
  `connector_id`   BIGINT        NOT NULL COMMENT 'connection.id',
  `kind`           VARCHAR(16)   NOT NULL COMMENT 'OBJECT / RELATION',
  `object_name`    VARCHAR(191)  NOT NULL COMMENT '表名；RELATION 为起点表',
  `field_name`     VARCHAR(191)  NOT NULL DEFAULT '' COMMENT 'RELATION 为起点列；OBJECT 为空串',
  `display_name`   VARCHAR(64)   DEFAULT NULL COMMENT '业务名 / 关系角色名',
  `summary`        VARCHAR(255)  DEFAULT NULL COMMENT '一句话说明（仅 OBJECT）',
  `domain`         VARCHAR(32)   DEFAULT NULL COMMENT '业务领域（仅 OBJECT）',
  `source`         VARCHAR(16)   NOT NULL COMMENT 'MODEL / HUMAN；HUMAN 行永远不被模型覆盖',
  `input_hash`     CHAR(64)      DEFAULT NULL COMMENT '生成名称 / 说明 / 角色名时输入的 SHA-256，输入变了才重新生成',
  `model_code`     VARCHAR(64)   DEFAULT NULL COMMENT '生成所用模型',
  `prompt_version` VARCHAR(32)   DEFAULT NULL COMMENT '生成所用提示词版本',
  `deleted`        TINYINT       NOT NULL DEFAULT 0 COMMENT 'BaseEntity 全局 @TableLogic 要求有这一列；本表不做逻辑删除',
  `create_time`    DATETIME      DEFAULT NULL,
  `create_user`    VARCHAR(64)   DEFAULT NULL,
  `update_time`    DATETIME      DEFAULT NULL,
  `update_user`    VARCHAR(64)   DEFAULT NULL,
  PRIMARY KEY (`id`),
  -- 64*4 + 8 + 16*4 + 191*4*2 = 1856 字节，低于 InnoDB 索引 3072 字节上限（191 的来历同 connector_semantic）
  UNIQUE KEY `uk_business_view` (`tenant_id`, `connector_id`, `kind`, `object_name`, `field_name`),
  KEY `idx_business_view_conn` (`connector_id`, `kind`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据星图：给人看的业务名称与说明';

CREATE TABLE IF NOT EXISTS `connector_enrichment_state` (
  `id`                        BIGINT        NOT NULL COMMENT '雪花ID',
  `tenant_id`                 VARCHAR(64)   NOT NULL COMMENT '租户ID',
  `connector_id`              BIGINT        NOT NULL COMMENT 'connection.id',
  `claim_at`                  DATETIME      DEFAULT NULL COMMENT '认领时间（秒）；非空且未满 30 分钟 = 正在跑，跑的过程中每步续期',
  `last_status`               VARCHAR(16)   DEFAULT NULL COMMENT '上次结果 READY / FAILED；NULL = 从没跑完过',
  `input_fingerprint`         CHAR(64)      DEFAULT NULL COMMENT '上次尝试时的输入指纹（设计文档 §4），定时对账据此判断要不要补跑',
  `relation_pass_fingerprint` CHAR(64)      DEFAULT NULL COMMENT '上次模型关系那一遍的输入指纹；没变就不再问模型',
  `last_attempt_at`           DATETIME      DEFAULT NULL COMMENT '上次开始时间；失败后的 6 小时退避按它算',
  `finished_at`               DATETIME      DEFAULT NULL,
  `relation_note`             VARCHAR(500)  DEFAULT NULL COMMENT '关系发现：新增 / 替换条数、来源分布、失败原因',
  `view_note`                 VARCHAR(500)  DEFAULT NULL COMMENT '业务文字：生成条数、校验退回条数、失败原因',
  `deleted`                   TINYINT       NOT NULL DEFAULT 0 COMMENT 'BaseEntity 全局 @TableLogic 要求有这一列；本表不做逻辑删除',
  `create_time`               DATETIME      DEFAULT NULL,
  `create_user`               VARCHAR(64)   DEFAULT NULL,
  `update_time`               DATETIME      DEFAULT NULL,
  `update_user`               VARCHAR(64)   DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_enrichment_state` (`tenant_id`, `connector_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据星图：语义层补全链的运行状态';
