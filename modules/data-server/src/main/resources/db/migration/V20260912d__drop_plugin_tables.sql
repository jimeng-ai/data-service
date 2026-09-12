-- 下线插件子系统：删除 5 张插件表。
--
-- 承接前序迁移：agent_plugin 的「按 Agent 限定工具范围」能力已由 V20260912__agent_skill.sql
-- 的 agent_skill 承接并回填；plugin / plugin_credential 的「调谁、用什么身份、能调多狠」已由
-- V20260912b__connection.sql 的 connection + AES-GCM 凭据承接。此处只负责清掉旧表。
--
-- 为什么是 DROP TABLE IF EXISTS 而不依赖某个 CREATE 迁移回滚：这几张表由更早的基线建立
-- （当前 migration 目录里最老的插件相关脚本 V20260529 已经是 ALTER，不是 CREATE），
-- 没有对称的建表脚本可依。IF EXISTS 保证在「表本就不存在」的环境（全新库、已手工清过的库）
-- 上重跑也不报错。
--
-- 顺序：先删绑定/凭据/子表，最后删主表 plugin。库内这几张表【没有外键约束】
-- （已核 migration 全量无 FOREIGN KEY 指向 plugin*），所以顺序其实不影响成败，
-- 这里排成这样只是让意图清楚：先摘依赖、后删被依赖者。
--
-- 不可逆。生产执行前必须先备份这几张表的存量数据。dev 库存量已导出至
-- docs/superpowers/plans/2026-09-12-plugin-removal/export/。

DROP TABLE IF EXISTS `agent_plugin`;
DROP TABLE IF EXISTS `plugin_credential`;
DROP TABLE IF EXISTS `plugin_http_mapping`;
DROP TABLE IF EXISTS `plugin_tool`;
DROP TABLE IF EXISTS `plugin`;
