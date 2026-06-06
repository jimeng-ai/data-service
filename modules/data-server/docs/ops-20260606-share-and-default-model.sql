-- =============================================================================
-- 运维脚本（手动执行）—— 资源分享 / 创建人 / Agent 默认模型 上线配套
-- -----------------------------------------------------------------------------
-- 背景：本批改动（feat: 资源分享(部门/全公司) + 列表创建人 + Agent默认模型）
--       【不含任何表结构(DDL)变更】，无需 Flyway 迁移：
--         - 创建人 creatorName 是 @TableField(exist=false) 瞬态字段，不落库；
--         - 「全公司可见」复用现有 sys_role_resource 表，仅插入 role_id=0 的哨兵行；
--         - 默认模型只是给 agent.model（已存在的列）写默认值。
--
-- 因此本脚本只有两件事：① 上线前只读安全检查（必跑）；② 存量数据回填（可选）。
--
-- 执行（本项目无 Flyway，需手动）：
--   mysql -h <host> -u <user> -p <data-server库> < ops-20260606-share-and-default-model.sql
--   （若生产库名不是 data-server，请替换下方 SQL 中的库名）
-- 上线顺序：先跑 ① 确认 → 部署新 data-server → 前端 main 已自动部署 →（可选）跑 ②。
-- =============================================================================


-- -----------------------------------------------------------------------------
-- ① 【必跑·只读】确认 sys_role_resource.role_id 没有外键约束
--    「全公司可见」会向 sys_role_resource 插入 role_id=0 的哨兵行（表示对全租户可见）。
--    若该列存在指向 sys_role(id) 的外键，插 role_id=0 会因找不到对应角色而失败。
--    期望：返回【0 行】（与本地一致）即可放心部署。
-- -----------------------------------------------------------------------------
SELECT CONSTRAINT_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
FROM information_schema.KEY_COLUMN_USAGE
WHERE TABLE_SCHEMA = 'data-server'
  AND TABLE_NAME   = 'sys_role_resource'
  AND COLUMN_NAME  = 'role_id'
  AND REFERENCED_TABLE_NAME IS NOT NULL;

-- 仅当上面查到外键时（理论上不该有）才执行下面这句，把 <约束名> 换成查到的 CONSTRAINT_NAME：
-- ALTER TABLE `data-server`.`sys_role_resource` DROP FOREIGN KEY <约束名>;


-- -----------------------------------------------------------------------------
-- ② 【可选·写操作】给存量「无模型」的 Agent 回填默认模型
--    背景：默认模型只对【新建】Agent 生效；上线前已存在、model 为空的老 Agent
--    在列表「模型」列仍显示 "-"。如需让老数据也显示默认模型再执行本段；不在意可跳过。
--    默认值须与前端 AVAILABLE_MODELS[0] 及后端 AgentService.DEFAULT_AGENT_MODEL 一致。
--    幂等：只改 model 为空的行，可重复执行。
-- -----------------------------------------------------------------------------

-- 先看影响范围：
SELECT id, name, code, model
FROM `data-server`.agent
WHERE deleted = 0 AND (model IS NULL OR model = '');

-- 确认无误后执行回填：
UPDATE `data-server`.agent
SET model = 'claude-opus-4-7'
WHERE deleted = 0 AND (model IS NULL OR model = '');
