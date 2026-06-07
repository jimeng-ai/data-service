-- 知识库文档增加文件大小列。
-- 背景：前端文档列表「大小」一列读 fileSize，但上传时从未记录文件字节数，导致一律显示 “-”。
-- 历史文档无法补出真实大小（原始字节已不在 DB），保持 NULL → 前端继续显示 “-”；新上传开始有值。
ALTER TABLE kb_document
    ADD COLUMN file_size BIGINT NULL COMMENT '文件大小（字节）' AFTER file_hash;
