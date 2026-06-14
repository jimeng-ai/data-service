-- 对话式生成 Agent：构建器会话的草稿快照（仅构建器会话使用，普通对话为 NULL）。
ALTER TABLE chat_conversation
  ADD COLUMN builder_draft LONGTEXT NULL COMMENT '构建器草稿快照(JSON)，仅 AI 生成 Agent 向导会话使用';
