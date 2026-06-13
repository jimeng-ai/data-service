# 上传时「逐行切片」开关（表格类文件 xlsx/csv）设计

日期：2026-06-13
涉及仓库：`data-service`（后端 + DB）、`jm-agent-front`（上传 UI）

## 背景与问题

RAG 入库对 xlsx/csv 的现状是「按行渲染、按 token 合并」：

- 解析阶段（`XlsxDocumentParser` / `CsvDocumentParser`）把**每个数据行**渲染成自描述文本
  `表头1: 值1 | 表头2: 值2 | ...`，产出为 `TEXT` 类型的 `DocumentBlock`（一行一 block）。
- 分块阶段（`HierarchicalChunker`）对 `TEXT` block 走 **buffer 累积**：同一 `headingPath`（sheet 名）
  的行一路用 `\n` 拼接，直到超过 `max-size-tokens`(800) 才二次切。

结果：一张 FAQ 表的几十条「问题|答案」被拼进同一个 chunk，而非「一问一答一片」。
对价目表/编码表这是合理的（每行很轻，合并省 embedding、利于召回）；但对 FAQ 表，每条问答是
独立检索单元，混在一个 chunk 里会稀释相关性、答非所问。

## 目标

给上传增加一个**逐行切片开关**：

- 打开 → 表格的每个数据行单独成一个 chunk。
- 关闭 → 维持现状（按 token 合并）。
- 仅对**表格类文件（xlsx / xls / xlsm / csv / tsv）**生效；pdf/docx/md 等不涉及「行」，开关对其无意义、不读取。
- 按**单个文件**控制（上传时勾选）：同一知识库里 FAQ 表勾上、价目表不勾，互不影响。

## 核心机制（关键决策）

分块器对 `TABLE / CODE / IMAGE` 类型的 block **本就是「一块一 chunk」**
（`HierarchicalChunker.addStandaloneChunks`，且自带超限硬切 `hardSplitByTokens`）。

因此最小实现：**开关打开时，解析器把每个数据行产出成 `DocumentBlock.table(...)` 而不是 `.text(...)`。**
一行 = 一个 TABLE block = 一个独立 chunk，复用现成的 standalone 路径。**分块器一行不改。**

- 该路径已被 DOCX 解析器使用（表格转 MD 走 TABLE block），下游（上下文化 / embedding / ES 索引 / 检索）
  对 TABLE 类型已验证可用，风险低。
- 单行超长仍由 `addStandaloneChunks` → `hardSplitByTokens` 兜底（按 ` | ` 边界、代理对安全），不会超 embedding 上限。

### 否决的备选

- **给 chunker 传 flag，让它把「表格来源的 TEXT」当独立块**：需要 chunker 反向感知 block 来源，更脏。
- **新增 `BlockType.TABLE_ROW`**：过度设计，TABLE 已满足。

## 改动清单

### 后端 data-service

1. **DB 迁移**：`kb_document` 加列
   `row_per_chunk TINYINT(1) NOT NULL DEFAULT 0 COMMENT '表格逐行切片：1=每数据行独立成chunk'`。
2. **实体**：`KbDocument` 加 `@TableField("row_per_chunk") private Boolean rowPerChunk;`
3. **上传 API**：`DocumentController.upload` 加
   `@RequestParam(value = "rowPerChunk", defaultValue = "false") boolean rowPerChunk`，透传给 service。
4. **Service**：`DocumentService.upload(kbId, file, rowPerChunk)`：
   - 新建文档：`doc.setRowPerChunk(rowPerChunk)` 再 insert。
   - 幂等重传（非 DONE，复用旧记录重新入队）：同步 `existed.setRowPerChunk(rowPerChunk)` 再 update。
   - DONE 幂等命中：直接返回旧记录、不重入库（开关变更对已完成文档不生效，需删除重传——见限制）。
5. **解析接口**：`DocumentParser` 加默认方法
   `default ParsedDocument parse(InputStream s, String filename, boolean rowPerChunk)`，
   默认实现委托回两参版本（PDF/DOCX/MD/Tika 忽略 flag）。
6. **入库编排**：`DocumentIngestionService.parse(doc)` 读 `Boolean.TRUE.equals(doc.getRowPerChunk())`，
   调用三参 `parser.parse(is, title, rowPerChunk)`。
7. **xlsx/csv 解析器**：`XlsxDocumentParser` / `CsvDocumentParser` override 三参 parse；
   `rowPerChunk` 为真时，**识别到表头后的数据行**用 `DocumentBlock.table(...)` 产出（其余路径——
   前言行、无表头兜底行——仍 `TEXT`）。两参 parse 保留为「rowPerChunk=false」入口。
8. **测试**：`XlsxDocumentParserTest` / `CsvDocumentParserTest` 各加一例：
   rowPerChunk=true 时数据行 block 类型为 TABLE 且每行一块；false 时维持 TEXT。

### 前端 jm-agent-front

9. **类型**：`KbDocument` 加 `rowPerChunk?: boolean`。
10. **API**：`docApi.upload(kbId, file, rowPerChunk)` →
    `upload(url, file, 'file', { rowPerChunk: String(rowPerChunk) })`（`upload` helper 已支持 `extra` 表单字段）。
11. **UI**：`KnowledgeDetailPage` 在 Dragger 旁加一个 `Checkbox`「表格逐行切片（FAQ 表用）」，
    state `rowPerChunk`；`customRequest` 调 `docApi.upload(kbId, file, rowPerChunk)`。
    文案提示「仅对 Excel/CSV 生效」。

## 数据流

```
上传(file, rowPerChunk=true)
  → DocumentController.upload
  → DocumentService.upload  → kb_document.row_per_chunk=1, 发 RabbitMQ
  → IngestionQueueConsumer  → DocumentIngestionService.ingest(docId)
  → parse(doc): 读 doc.rowPerChunk → parser.parse(is, title, true)
  → XlsxDocumentParser: 数据行 → DocumentBlock.table(...)
  → HierarchicalChunker: TABLE → addStandaloneChunks → 一行一 chunk
```

retry 路径自动生效：重试读 DB 文档，`row_per_chunk` 已持久化，无需额外透传。

## 边界与取舍（已确认）

- **只影响「识别到表头后的数据行」**：前言标题行、无表头兜底行不变，行为可预测。
- **重传生效**：已 DONE 的旧 FAQ 文档需「删除 → 重传并勾选」才会按新逻辑重切
  （DONE 幂等命中直接返回，不重入库）。
- **成本**：逐行 = chunk 数暴涨 = 上下文化 LLM 调用数暴涨（有界并发 5，慢且贵）。开关的固有代价，勾选即接受。
- **检索质量**：每行本就自带表头，一行一片语义完整，正合 FAQ。

## 验证（按 jerry 硬性要求：端到端实跑 + 查库）

1. 本地起 data-service（参考 jm-local-stack）。
2. 用 `学生险-FAQ导出.xlsx`：勾选 rowPerChunk 上传 → 等入库 DONE。
3. 查 `kb_chunk`：chunk 数 ≈ FAQ 行数，每条 content 为单行「表头: 值 | ...」，type=TABLE。
4. 对照：不勾选重传另一张表 → chunk 数远小于行数（合并），验证开关关闭维持原行为。
5. 前端：上传页勾选框可见、勾选后入库结果符合预期。
6. 后端单测 `mvn -pl modules/data-server test -Dtest=XlsxDocumentParserTest,CsvDocumentParserTest`；
   前端 `npm run build`（含 typecheck）+ `npm run lint`。
```
