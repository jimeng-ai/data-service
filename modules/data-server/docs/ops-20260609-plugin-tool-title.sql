-- ops-20260609-plugin-tool-title
-- plugin_tool 新增「中文展示名」title 列。
--
-- 背景：tool.name 是函数名，会作为 LLM tools[].name 注入，且 SkillToolDefinition.normalizeModelName
--       会把非 [a-zA-Z0-9_-] 字符（含中文）全替换成 '_'，所以 name 必须是英文，无法直接用中文。
--       为满足「工具名展示成中文」的需求，单独加一个 title 列：name 仍是英文函数名（供调用），
--       title 是中文展示名（仅给人看、不参与 LLM 调用/路由）。为空时前端回退显示 name。
--
-- 注意：生产库（ds-mysql / 线上）也需执行同一条 ALTER。

ALTER TABLE plugin_tool
    ADD COLUMN title VARCHAR(128) NULL COMMENT '中文展示名（给人看；为空回退 name）' AFTER name;
