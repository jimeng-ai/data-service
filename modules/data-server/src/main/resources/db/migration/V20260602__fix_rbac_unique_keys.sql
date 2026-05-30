-- =====================================================================
-- V20260602: 修复 RBAC 授权/绑定表的唯一键
--
-- 背景：sys_role_resource / sys_user_role 由 service 层「整体覆盖」（先逻辑删旧、再插新），
-- 原唯一键存在两个问题：
--   1) sys_role_resource.uk_role_res = (role_id, resource_type, resource_id, deleted)：
--      MENU 授权的 resource_id 恒为 0、靠 resource_code 区分模块，勾选多个模块时
--      多行 (role,MENU,0,0) 撞唯一键 → Duplicate entry 'xxx-MENU-0-0'。
--   2) 逻辑删除把 deleted 置 1，反复保存会出现多条 deleted=1 的同值行 → 同样撞唯一键。
-- 修复：去掉这两个唯一键。一致性由 service「全删全插（同事务）」保证；
--      查询经 @TableLogic 只取 deleted=0，历史死行无害。lookup 索引（idx_*）保留。
-- =====================================================================

ALTER TABLE `sys_role_resource` DROP INDEX `uk_role_res`;
ALTER TABLE `sys_user_role`     DROP INDEX `uk_user_role`;
