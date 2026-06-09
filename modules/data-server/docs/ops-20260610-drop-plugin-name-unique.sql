-- ops-20260610-drop-plugin-name-unique
-- 删除插件/工具的「租户内名称/代号」唯一键。
--
-- 背景：plugin_tool 有 uk_plugin_tool_tenant_name(tenant_id, name)、plugin 有
--       uk_plugin_tenant_code(tenant_id, code)，都是【租户级】唯一。后果：
--       - 同一租户下，两个不同插件不能各有同名工具（如都叫 list_workflow_history）；
--       - 同名工具哪怕属于「你无权查看的部门」，也会让创建直接 5007 失败，提示难懂。
--       这与既定策略冲突（除主键外不使用唯一键；用应用层/前端做幂等与提示，而非 DB 硬约束）。
--
-- 处理：删除这两个【命名冲突型】唯一键。改由前端在创建前按本插件已有工具名跳过+提示。
--       注意：保留 plugin_http_mapping 的 uk_plugin_http_mapping_tool(plugin_tool_id) 与
--       plugin_credential 的 uk_plugin_credential_tenant_plugin(tenant_id, plugin_id)——
--       它们是 1:1 结构不变量（每工具一份映射、每插件一份凭证），upsert 逻辑依赖之，不动。
--
-- 注意：生产库（ds-mysql / 线上）也需执行同样的 DROP。

ALTER TABLE plugin_tool DROP INDEX uk_plugin_tool_tenant_name;
ALTER TABLE plugin      DROP INDEX uk_plugin_tenant_code;
