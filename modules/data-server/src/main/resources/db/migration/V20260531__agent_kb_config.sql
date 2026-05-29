-- Agent 知识库绑定配置：存放 {kbIds, topK, scoreThreshold}。
-- 之前「知识库绑定」只有前端表单、未落库；这里补上存储列。
ALTER TABLE `agent`
    ADD COLUMN `kb_config` JSON DEFAULT NULL COMMENT '知识库绑定配置 {kbIds, topK, scoreThreshold}';
