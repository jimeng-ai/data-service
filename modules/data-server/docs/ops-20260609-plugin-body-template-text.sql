-- ops-20260609-plugin-body-template-text
-- plugin_http_mapping.body_template 从 JSON 改为 TEXT。
--
-- 原因：body_template 是「请求体模板」，对 number/boolean/object/array 入参会生成裸占位符
--       （如 {"items": {{input.items}}}、{"qty": {{input.qty}}}），这并非合法 JSON；
--       而该列原本是 JSON 类型，会在写入时被 MySQL 校验拒绝：
--       "Invalid JSON text: Missing a name for object member"。
--       结果：任何含 非字符串 body 入参 的工具都无法保存（字符串入参因占位符在引号内恰好是合法 JSON 才幸免）。
--       这也是 Array<Object> 等复杂 body 入参一直存不进库的根因。
--
-- 修复：把 body_template 改成 TEXT（模板本就不该被当作 JSON 校验）。
--       headers_template / query_template 仍是 JSON（其值始终是带占位符的字符串，恒为合法 JSON），无需改。
--       现有数据均为合法 JSON 文本，JSON→TEXT 转换无损。
--
-- 注意：生产库（ds-mysql / 线上）也需执行同一条 ALTER。

ALTER TABLE plugin_http_mapping
    MODIFY COLUMN body_template TEXT
    COMMENT 'HTTP 请求体模板（含 {{input.x}} 占位符，非合法 JSON，故用 TEXT 而非 JSON）';
