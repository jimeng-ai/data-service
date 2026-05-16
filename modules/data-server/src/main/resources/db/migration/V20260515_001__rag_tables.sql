-- ============================================================
-- RAG 子系统数据表
-- 列命名与现有 BaseEntity (create_time / update_time / deleted) 对齐
-- ============================================================

CREATE TABLE IF NOT EXISTS `knowledge_base` (
  `id`          BIGINT       NOT NULL                COMMENT '主键（雪花 ID）',
  `name`        VARCHAR(128) NOT NULL                COMMENT '知识库名称',
  `description` VARCHAR(512) NULL                    COMMENT '描述',
  `deleted`     TINYINT(1)   NOT NULL DEFAULT 0     COMMENT '逻辑删除',
  `create_time` DATETIME     NULL                    COMMENT '创建时间',
  `create_user` VARCHAR(64)  NULL                    COMMENT '创建人',
  `update_time` DATETIME     NULL                    COMMENT '更新时间',
  `update_user` VARCHAR(64)  NULL                    COMMENT '更新人',
  PRIMARY KEY (`id`),
  KEY `idx_kb_name` (`name`)
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
