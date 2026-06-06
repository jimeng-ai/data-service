-- =============================================================================
-- 运维脚本（手动执行）—— Agent 预设问题 + 头像上传 上线配套【含 DDL 表结构变更】
-- -----------------------------------------------------------------------------
-- 背景：本批改动（feat: 对话空状态预设问题 + Agent 头像改为图片上传）含两处 agent 表结构变更：
--   1) 新增 agent.preset_questions（对话空状态的预设引导问题，JSON 数组字符串存储）；
--   2) agent.avatar_url 由 varchar(512) 扩为 mediumtext —— 头像改为「上传图片→浏览器压缩成
--      data URL」直接入库，varchar(512) 装不下 base64 data URL。
--
-- 注：同批的「重新入库 kb_chunk 唯一键死循环」修复是【纯代码】（KbChunkMapper 物理删除），
--     无表结构变更，无需在此处理。
--
-- 本项目无 Flyway，需手动执行（库名默认 data-server，生产若不同请替换下方 `data-server`）：
--   mysql -h <host> -u <user> -p < ops-20260607-agent-preset-questions-avatar.sql
-- 上线顺序：先跑①确认现状 → 跑②执行变更 → 部署新 data-server → 前端 main 自动部署。
-- 幂等：②对 preset_questions 做存在性判断、对 avatar_url 重复 MODIFY 同类型均安全可重复执行。
-- =============================================================================


-- -----------------------------------------------------------------------------
-- ① 【必跑·只读】确认现状：avatar_url 当前类型 + preset_questions 是否已存在
--    期望（未升级时）：avatar_url=varchar(512)，preset_questions 查不到。
-- -----------------------------------------------------------------------------
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'data-server'
  AND TABLE_NAME   = 'agent'
  AND COLUMN_NAME IN ('avatar_url', 'preset_questions')
ORDER BY ORDINAL_POSITION;


-- -----------------------------------------------------------------------------
-- ② 【写操作】执行表结构变更
-- -----------------------------------------------------------------------------

-- 2.1 新增 preset_questions（存在则跳过，幂等）
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'data-server' AND TABLE_NAME = 'agent' AND COLUMN_NAME = 'preset_questions'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `data-server`.`agent` ADD COLUMN `preset_questions` json NULL COMMENT ''对话空状态预设引导问题(JSON数组)'' AFTER `avatar_url`',
  'SELECT ''preset_questions 已存在，跳过新增'' AS note');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2.2 avatar_url 扩为 mediumtext（容纳上传图片压缩后的 data URL）。重复执行同类型安全。
ALTER TABLE `data-server`.`agent`
  MODIFY COLUMN `avatar_url` mediumtext NULL COMMENT '头像：上传图片压缩后的 data URL，或外链 URL';


-- -----------------------------------------------------------------------------
-- ③ 【可选·只读】变更后复核
-- -----------------------------------------------------------------------------
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'data-server'
  AND TABLE_NAME   = 'agent'
  AND COLUMN_NAME IN ('avatar_url', 'preset_questions')
ORDER BY ORDINAL_POSITION;
