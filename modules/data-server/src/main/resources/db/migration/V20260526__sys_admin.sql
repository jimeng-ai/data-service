-- 管理后台账户表。data-service 自有，与 jm-momi 解耦。
-- 首期不做角色 / 权限粒度——所有 sys_admin 行都是超级管理员。
-- 注意：默认账号（admin / admin123）由 data-server 启动时通过 AdminAuthInitializer 自动写入，
-- 它会用真实的 BCryptPasswordEncoder 生成 hash，避免把哈希硬编码在 SQL 里跑偏。

CREATE TABLE IF NOT EXISTS `sys_admin` (
    `id`            BIGINT       NOT NULL                COMMENT '主键（雪花算法分配）',
    `username`      VARCHAR(64)  NOT NULL                COMMENT '登录名',
    `password_hash` VARCHAR(128) NOT NULL                COMMENT 'BCrypt 哈希',
    `display_name`  VARCHAR(64)                          COMMENT '展示名',
    `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=启用 0=禁用',
    `last_login_at` DATETIME                             COMMENT '最近登录时间',
    `deleted`       TINYINT      NOT NULL DEFAULT 0      COMMENT '0=未删除 1=已删除（逻辑删除）',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `create_user`   VARCHAR(64),
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `update_user`   VARCHAR(64),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_admin_username` (`username`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理后台账户';
