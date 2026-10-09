-- 一切皆可 skills · S1：SKILL.md 的解析结果存进库，运行时不再重复解析（设计文档 §3.6）。
-- 内容是 metadata.jm 的五个字段，加上 key 的声明（名字、hosts、methods、paths；不含值）。
-- ai_skill 存当前版本那一份（运行时读这里），ai_skill_version 存每一版那一份（版本记录）。
-- 老数据为 NULL，按「没有平台扩展字段、没有 key」处理，不需要回填。
--
-- ★ 必须先于 data-service 上线执行：实体已经映射了这一列，列不存在时 ai_skill 的每一次查询都会报错。
-- 没有 Flyway：这份 DDL 要手工执行（见 docs/config-changes/2026-10-07-s1-skill-keys-and-http-gateway.md）。

ALTER TABLE `ai_skill`
  ADD COLUMN `manifest` TEXT DEFAULT NULL COMMENT 'SKILL.md 解析结果 JSON：metadata.jm 与 key 的声明（不含值）';

ALTER TABLE `ai_skill_version`
  ADD COLUMN `manifest` TEXT DEFAULT NULL COMMENT '这一版 SKILL.md 的解析结果 JSON';
