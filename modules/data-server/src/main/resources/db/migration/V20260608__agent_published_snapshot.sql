-- Agent 草稿/发布分离：新增「发布快照」列。
-- 发布那一刻冻结完整运行配置 {code,name,systemPrompt,model,modelParams,kbConfig,pluginIds}；
-- 调试台读实时草稿字段，对话端只读这份快照。为空 = 从未发布过。
-- （hasUnpublishedChanges 为内存计算字段，@TableField(exist=false)，不落库。）
ALTER TABLE `agent`
    ADD COLUMN `published_snapshot` JSON DEFAULT NULL COMMENT '发布快照 JSON：发布时冻结的运行配置；为空表示从未发布' AFTER `kb_config`;
