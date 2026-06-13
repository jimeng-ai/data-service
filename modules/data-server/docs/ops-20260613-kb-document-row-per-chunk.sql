-- 知识库文档增加「表格逐行切片」开关列。
-- 背景：xlsx/csv 默认按 token 合并多行成一个 chunk；FAQ 表希望「一问一答一片」。
-- 上传时勾选 rowPerChunk=1 → 入库时表格每个数据行独立成 chunk（仅对 xlsx/csv 生效）。
-- 历史文档为 0（维持原合并行为）；要让旧 FAQ 文档逐行需删除后勾选重传。
ALTER TABLE kb_document
    ADD COLUMN row_per_chunk TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '表格逐行切片：1=xlsx/csv每数据行独立成chunk(FAQ)，0=按token合并' AFTER file_size;
