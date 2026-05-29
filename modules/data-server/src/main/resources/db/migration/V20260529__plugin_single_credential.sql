-- 改造为「一个插件只能有一份凭证」。
-- 1) 去重：每个 (tenant_id, plugin_id) 仅保留一行：优先 is_default=1，否则取 id 最小的；其余直接物理删除。
-- 2) 删除旧的 alias / is_default 列及关联索引；新增 (tenant_id, plugin_id) 唯一键。
-- 3) agent_plugin 不再保存 credential_alias。

-- ---------- plugin_credential ----------
-- 物理删除重复行（含已逻辑删除的也不再有意义保留）。
-- 用 WHERE id IN (...) 形式：兼容 IDE 客户端的 safe-mode 检查；
-- 子查询外包一层派生表，避开 MySQL 「不能在子查询里引用同表做 DELETE 源」的限制。
DELETE FROM `plugin_credential`
WHERE id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY tenant_id, plugin_id
                   ORDER BY deleted ASC, is_default DESC, id ASC
               ) AS rn
        FROM `plugin_credential`
    ) t
    WHERE t.rn > 1
);

ALTER TABLE `plugin_credential`
    DROP INDEX `uk_plugin_credential_tenant_plugin_alias`,
    DROP INDEX `idx_plugin_credential_default`,
    DROP COLUMN `alias`,
    DROP COLUMN `is_default`,
    ADD UNIQUE KEY `uk_plugin_credential_tenant_plugin` (`tenant_id`, `plugin_id`);

-- ---------- agent_plugin ----------
ALTER TABLE `agent_plugin`
    DROP COLUMN `credential_alias`;
