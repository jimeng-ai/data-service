-- 外部系统连接注册表。替代 plugin / plugin_credential 里"调谁、用什么身份、能调多狠"那部分。
--
-- 与插件的关键区别：这里【不】描述"怎么调"（那是 skill 的 SKILL.md + scripts/ 的事），
-- 只描述"允许调谁、用什么身份、能调多狠"。前者是内容、模型读了才生效；
-- 后者是授权、模型读不读都必须生效，因此必须留在平台侧、由 egress 代理执行。
--
-- 运行时链路：data-service 派发 run 时把被授予的连接下发给边车 → 边车向 egress 代理注册
-- {srcIp -> 连接集合} → 容器内技能脚本 `curl $JM_CONN_BASE/<name>/<path>` → 代理按源 IP
-- 查出真实 base_url 与凭据并注入。容器只见 name，凭据一个字节都不进容器。

CREATE TABLE IF NOT EXISTS `connection` (
  `id`                bigint       NOT NULL COMMENT '主键',
  `tenant_id`         varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户 ID',
  `name`              varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '容器可见标识，也是 URL 里那一段；^[A-Za-z0-9_-]{1,64}$',
  `display_name`      varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '给人看的名字',
  `base_url`          varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '真实上游 base URL，容器不可见',
  `auth_scheme`       varchar(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'bearer' COMMENT 'bearer | api-key',
  `credential_cipher` text         CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'AES-GCM 密文（base64: iv|ciphertext|tag）。绝不存明文',
  `encryption_version` int         NOT NULL DEFAULT '1' COMMENT '1=AES-GCM。0 不允许写入——plugin_credential 就是栽在"0=明文"的回落上',
  `allow_methods`     varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'GET' COMMENT '逗号分隔。默认只读；写操作必须显式声明',
  `allow_paths`       text         CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'JSON 数组的路径 glob；空=["/**"]',
  `transport`         varchar(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'direct' COMMENT 'direct=代理直连公网；tunnel=经客户侧连接器进内网（尚未实现，占位）',
  `status`            varchar(16)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE | DISABLED',
  `deleted`           tinyint(1)   NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  `create_time`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_user`       varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `update_time`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `update_user`       varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修改人',
  PRIMARY KEY (`id`),
  -- 破例加唯一键：name 是【运行时寻址键】（URL 路径段），同租户内重名会让"到底转发给哪条"
  -- 变成不确定行为。这不是业务约束，是可寻址性的前提。
  UNIQUE KEY `uk_connection_tenant_name` (`tenant_id`,`name`),
  KEY `idx_connection_status` (`status`),
  KEY `idx_connection_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='外部系统连接注册表';

-- Agent 与连接的授权。与 agent_skill 同构、同语义（三态）。
--
-- 为什么授权挂在 Agent 而不是 Skill：skill 可以在手册里【声明】它需要哪条连接，
-- 但不能【授予】自己。授予由平台按 Agent/租户决定——否则装一个 skill 就是一次提权，
-- 而 skill 的来源里有 AI 生成的、客户自己写的、从市场装的。
CREATE TABLE IF NOT EXISTS `agent_connection` (
  `id`            bigint      NOT NULL COMMENT '主键',
  `tenant_id`     varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户 ID',
  `agent_id`      bigint      NOT NULL COMMENT 'Agent ID',
  `connection_id` bigint      NOT NULL COMMENT 'connection.id',
  `deleted`       tinyint(1)  NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  `create_time`   datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_user`   varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `update_time`   datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `update_user`   varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修改人',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_connection_tenant_agent_conn` (`tenant_id`,`agent_id`,`connection_id`),
  KEY `idx_agent_connection_agent` (`agent_id`),
  KEY `idx_agent_connection_conn` (`connection_id`),
  KEY `idx_agent_connection_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 与外部连接的授权';

-- 注意：这里【不回填】。agent_skill 要回填是因为它替换了一个已存在的隐式全可见规则；
-- 连接是全新能力，没有"今天的隐式集"可搬，必须逐条显式授予。
