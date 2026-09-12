-- Agent 与技能的绑定表。承接插件下线后失去的「按 Agent 限定工具范围」能力。
--
-- 为什么是新建而不是 RENAME agent_plugin：agent_plugin.plugin_id 存的是 plugin.id，
-- 与 ai_skill.id 是两个 id 域，没有一行可以搬过来。
--
-- 为什么存 skill_id 而不是 name：ai_skill 刻意没有 name 唯一键
-- （V20260619__ai_skill.sql 开头注明「业务表除主键外不加唯一键」），同名行可以共存，
-- 用 name 做绑定键会出现"绑了哪一个说不清"。给模型看的仍是 name（见 ToolPackage.getName）。
--
-- 业务表除主键外本不加唯一键，这里破例加一个 (tenant_id, agent_id, skill_id)：
-- 它不是业务语义约束，是幂等保护——bind 接口重复调用、以及下面的回填重跑，都靠它兜底。

CREATE TABLE IF NOT EXISTS `agent_skill` (
  `id`          bigint       NOT NULL COMMENT '主键',
  `tenant_id`   varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户 ID',
  `agent_id`    bigint       NOT NULL COMMENT 'Agent ID',
  `skill_id`    bigint       NOT NULL COMMENT 'ai_skill.id',
  `deleted`     tinyint(1)   NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_user` varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `update_user` varchar(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修改人',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_skill_tenant_agent_skill` (`tenant_id`,`agent_id`,`skill_id`),
  KEY `idx_agent_skill_agent` (`agent_id`),
  KEY `idx_agent_skill_skill` (`skill_id`),
  KEY `idx_agent_skill_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 与技能绑定表';

-- ── 回填：把「今天的隐式可见集」显式物化下来 ─────────────────────────────────
--
-- 必须做，而且必须在代码上线【之前】做。今天 filterByAgentAllowlist 对 kind==SKILL 无条件放行，
-- 即租户内每个 ACTIVE 技能对每个 agent 都可见；新代码改成「只有绑定的才可见」。
-- 不回填就等于所有 agent 在上线瞬间同时丢掉全部租户技能——不报错，工具悄悄少了一批。
--
-- 回填 PRIVATE 与 TENANT 两种 scope 都包含，是刻意的、且不会越权：
-- DbTenantSkillSourceProvider.filterVisible 的 scope/owner 过滤在绑定过滤【之前】执行，
-- PRIVATE 技能仍然只对其 owner 可见。绑定不放大可见性，只收窄。
--
-- UUID_SHORT() 生成主键：这是一次性回填，不经应用的雪花服务；唯一键保证重跑幂等。
INSERT IGNORE INTO `agent_skill` (`id`, `tenant_id`, `agent_id`, `skill_id`, `create_user`)
SELECT UUID_SHORT(), a.`tenant_id`, a.`id`, s.`id`, 'migration:V20260912'
FROM `agent` a
JOIN `ai_skill` s
  -- 显式 COLLATE：ai_skill.tenant_id 是 utf8mb4_0900_ai_ci，agent.tenant_id 是 utf8mb4_unicode_ci
  -- （库里 4 张表用前者、21 张用后者，是既有的不一致）。不写这句 JOIN 直接报
  -- "Illegal mix of collations"。根治要单独把那 4 张表归一，不在本迁移的范围内。
  ON s.`tenant_id` COLLATE utf8mb4_unicode_ci = a.`tenant_id`
 AND s.`deleted` = 0
 AND s.`status` = 'ACTIVE'
WHERE a.`deleted` = 0;

-- 核对（人工执行，不是断言）：回填行数应当 = 每个未删 agent × 同租户 ACTIVE 技能数
-- SELECT COUNT(*) FROM agent_skill;
-- SELECT a.name, COUNT(*) FROM agent_skill b JOIN agent a ON a.id=b.agent_id GROUP BY a.id;
