-- =====================================================================
-- V20260601: 多租户 RBAC —— 运营 / 企业 / 角色 / 资源授权 / 成员
--
-- 账号分两套登录域：
--   sys_operator  平台运营（跨租户），JWT tenant_id="platform"，登录 /data/admin/operator/auth/login
--   sys_user      企业账号（超管 SUPER_ADMIN + 成员 MEMBER），登录 /data/admin/auth/login
--
-- ⚠ 重要：本批 sys_* 表均 *不* 纳入 JimengTenantLineHandler.TENANT_AWARE_TABLES。
--   原因：
--     1) sys_operator / sys_enterprise 跨租户（运营侧要列出所有企业），不能被注入 tenant_id；
--     2) sys_user / sys_role / sys_role_resource / sys_user_role 由 service 层显式拼
--        WHERE tenant_id=?（登录早于 TenantContext 设置；运营侧用 runAsSystem 跨租户写）。
--   若日后误把这些表加进 TENANT_AWARE_TABLES，运营/登录查询会被 'platform' 过滤或被 __no_tenant__ 兜底打成 0 行。
--
-- 本文件还修复一处既有越权：knowledge_base 之前没有 tenant_id，KnowledgeBaseService.list() 跨租户全返。
-- =====================================================================

SET NAMES utf8mb4;

-- 1. 平台运营账号（跨租户）。启动时 OperatorAuthInitializer 自动写入 admin / admin123（BCrypt 当场算）。
CREATE TABLE IF NOT EXISTS `sys_operator` (
    `id`            BIGINT       NOT NULL                COMMENT '主键（雪花算法）',
    `username`      VARCHAR(64)  NOT NULL                COMMENT '登录名（全局唯一）',
    `password_hash` VARCHAR(128) NOT NULL                COMMENT 'BCrypt 哈希',
    `display_name`  VARCHAR(64)                          COMMENT '展示名',
    `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=启用 0=禁用',
    `last_login_at` DATETIME                             COMMENT '最近登录时间',
    `deleted`       TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '0=未删除 1=已删除（逻辑删除）',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `create_user`   VARCHAR(64),
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `update_user`   VARCHAR(64),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_operator_username` (`username`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='平台运营账号（跨租户）';

-- 2. 企业（= 租户）。tenant_id 即写入 JWT 并由 gateway 注入 X-Tenant-Id 的取值。
CREATE TABLE IF NOT EXISTS `sys_enterprise` (
    `id`            BIGINT       NOT NULL                COMMENT '主键（雪花算法）',
    `tenant_id`     VARCHAR(64)  NOT NULL                COMMENT '租户标识，[A-Za-z0-9._-] ≤64（TenantContextFilter 校验）',
    `name`          VARCHAR(128) NOT NULL                COMMENT '企业名称',
    `description`   VARCHAR(512)                         COMMENT '描述',
    `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=启用 0=停用（停用后该租户全员禁止登录）',
    `deleted`       TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '0=未删除 1=已删除',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `create_user`   VARCHAR(64),
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `update_user`   VARCHAR(64),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_enterprise_tenant` (`tenant_id`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='企业（租户）';

-- 3. 企业账号（超管 + 成员）。username 全局唯一（jm-agent-front 登录页只有账号+密码，无租户选择器）。
CREATE TABLE IF NOT EXISTS `sys_user` (
    `id`            BIGINT       NOT NULL                COMMENT '主键（雪花算法）',
    `tenant_id`     VARCHAR(64)  NOT NULL                COMMENT '所属租户',
    `username`      VARCHAR(64)  NOT NULL                COMMENT '登录名（全局唯一）',
    `password_hash` VARCHAR(128) NOT NULL                COMMENT 'BCrypt 哈希',
    `display_name`  VARCHAR(64)                          COMMENT '展示名',
    `user_type`     VARCHAR(16)  NOT NULL DEFAULT 'MEMBER' COMMENT 'SUPER_ADMIN | MEMBER',
    `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '1=启用 0=禁用',
    `last_login_at` DATETIME                             COMMENT '最近登录时间',
    `deleted`       TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '0=未删除 1=已删除',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `create_user`   VARCHAR(64),
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `update_user`   VARCHAR(64),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_user_username` (`username`, `deleted`),
    KEY `idx_sys_user_tenant` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='企业账号（超管/成员）';

-- 4. 企业自定义角色（租户内）。
CREATE TABLE IF NOT EXISTS `sys_role` (
    `id`            BIGINT       NOT NULL                COMMENT '主键（雪花算法）',
    `tenant_id`     VARCHAR(64)  NOT NULL                COMMENT '所属租户',
    `code`          VARCHAR(64)  NOT NULL                COMMENT '角色 slug，租户内唯一',
    `name`          VARCHAR(128) NOT NULL                COMMENT '角色名',
    `description`   VARCHAR(512)                         COMMENT '描述',
    `deleted`       TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '0=未删除 1=已删除',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `create_user`   VARCHAR(64),
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `update_user`   VARCHAR(64),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_role_tenant_code` (`tenant_id`, `code`, `deleted`),
    KEY `idx_sys_role_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='企业自定义角色';

-- 5. 角色 → 资源授权（通用资源-权限模型，可扩展）。
--    resource_type=MENU         时 resource_id=0，resource_code 存模块码（AGENT_MODULE / KB_MODULE / ...）；
--    resource_type=AGENT/KNOWLEDGE_BASE/PLUGIN 时 resource_id=雪花实例 id，resource_code 可冗余存实例 code。
CREATE TABLE IF NOT EXISTS `sys_role_resource` (
    `id`            BIGINT       NOT NULL                COMMENT '主键（雪花算法）',
    `tenant_id`     VARCHAR(64)  NOT NULL                COMMENT '所属租户（冗余，便于校验/索引）',
    `role_id`       BIGINT       NOT NULL                COMMENT '角色 ID',
    `resource_type` VARCHAR(32)  NOT NULL                COMMENT 'MENU | AGENT | KNOWLEDGE_BASE | PLUGIN | ...（可扩展）',
    `resource_id`   BIGINT       NOT NULL DEFAULT 0      COMMENT '实例 id；MENU 类为 0',
    `resource_code` VARCHAR(128)                         COMMENT '模块码（MENU）或实例 code（冗余）',
    `deleted`       TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '0=未删除 1=已删除',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `create_user`   VARCHAR(64),
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `update_user`   VARCHAR(64),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_res` (`role_id`, `resource_type`, `resource_id`, `deleted`),
    KEY `idx_role_res_role` (`role_id`),
    KEY `idx_role_res_type` (`tenant_id`, `resource_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色-资源授权';

-- 6. 成员 ↔ 角色（多对多）。
CREATE TABLE IF NOT EXISTS `sys_user_role` (
    `id`            BIGINT       NOT NULL                COMMENT '主键（雪花算法）',
    `tenant_id`     VARCHAR(64)  NOT NULL                COMMENT '所属租户（冗余）',
    `user_id`       BIGINT       NOT NULL                COMMENT '成员 ID（sys_user.id）',
    `role_id`       BIGINT       NOT NULL                COMMENT '角色 ID（sys_role.id）',
    `deleted`       TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '0=未删除 1=已删除',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `create_user`   VARCHAR(64),
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `update_user`   VARCHAR(64),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role` (`user_id`, `role_id`, `deleted`),
    KEY `idx_user_role_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='成员-角色绑定';

-- 7. 既有越权修复：给 knowledge_base 补 tenant_id 并纳入租户隔离（同时把该表加入 TENANT_AWARE_TABLES）。
--    历史 KB 回填 'default' 租户（与 agent/plugin 既有默认租户一致）。
--    ⚠ 必须先回填再让代码侧把 knowledge_base 纳入白名单，否则旧 KB 会从 default 视图消失。
ALTER TABLE `knowledge_base`
    ADD COLUMN `tenant_id` VARCHAR(64) NOT NULL DEFAULT 'default'
        COMMENT '租户 ID（与 X-Tenant-Id 对齐）' AFTER `id`,
    ADD KEY `idx_kb_tenant` (`tenant_id`);

-- 8. 可选：给历史 default 数据一个归属企业，便于运营在列表里看到它。
INSERT INTO `sys_enterprise` (`id`, `tenant_id`, `name`, `description`, `status`, `create_user`, `update_user`)
SELECT 1, 'default', '默认企业（历史数据）', '迁移前既有数据归属', 1, 'system', 'system'
WHERE NOT EXISTS (SELECT 1 FROM `sys_enterprise` WHERE `tenant_id` = 'default');
