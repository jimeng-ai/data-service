-- 产品反馈：主记录
CREATE TABLE IF NOT EXISTS `product_feedback` (
  `id`            BIGINT       NOT NULL COMMENT '主键(雪花)',
  `tenant_id`     VARCHAR(64)  NOT NULL COMMENT '租户ID',
  `feedback_type` TINYINT      NOT NULL COMMENT '1=问题反馈 2=功能建议',
  `content`       TEXT         NOT NULL COMMENT '文字描述',
  `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
  `create_time`   DATETIME     NULL,
  `create_user`   VARCHAR(64)  NULL COMMENT '提交人userId',
  `update_time`   DATETIME     NULL,
  `update_user`   VARCHAR(64)  NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_create_user` (`tenant_id`, `create_user`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品反馈主记录';

-- 产品反馈：图片（feedback_id 可空：NULL=已上传待引用的草稿图）
CREATE TABLE IF NOT EXISTS `product_feedback_image` (
  `id`           BIGINT       NOT NULL COMMENT '主键(雪花)',
  `feedback_id`  BIGINT       NULL COMMENT '关联product_feedback.id；NULL=未引用',
  `tenant_id`    VARCHAR(64)  NOT NULL COMMENT '上传者租户',
  `object_key`   VARCHAR(255) NOT NULL COMMENT 'MinIO对象名',
  `content_type` VARCHAR(128) NULL COMMENT 'image/png等',
  `file_size`    BIGINT       NULL COMMENT '字节',
  `sort_order`   INT          NOT NULL DEFAULT 0 COMMENT '展示顺序',
  `deleted`      TINYINT      NOT NULL DEFAULT 0,
  `create_time`  DATETIME     NULL,
  `create_user`  VARCHAR(64)  NULL COMMENT '上传者userId',
  `update_time`  DATETIME     NULL,
  `update_user`  VARCHAR(64)  NULL,
  PRIMARY KEY (`id`),
  KEY `idx_feedback_id` (`feedback_id`),
  KEY `idx_orphan` (`feedback_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品反馈图片';
