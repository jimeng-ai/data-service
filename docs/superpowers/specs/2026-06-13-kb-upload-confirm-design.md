# 知识库上传与「确认入库」分离 — 设计

日期:2026-06-13
涉及仓库:`data-service`(后端)、`jm-agent-front`(前端)

## 背景与目标

当前 `DocumentService.upload` 一步做两件事:存文件(MinIO + `kb_document`)并**立即** publish `IngestionMessage`,异步触发 解析→切片→上下文化→向量化→入 ES 的整条流水线。

用户诉求:上传文档/表格时**只上传、不处理**;新增一个「确认」动作,点击后才对文件做切片、向量化等处理。允许一次上传多个混合类型文件(doc/txt/csv/xlsx 等),点「确认入库」后这批(及本库所有待确认文件)一并处理。

## 状态机

新增 `STAGED`(待确认)状态,位于上传与入队之间:

```
STAGED → UPLOADED → PARSING → CHUNKING → CONTEXTUALIZING → EMBEDDING → DONE/FAILED
(待确认)  (已入队)
```

`STAGED` 仅是新的字符串枚举值,`kb_document.status` 本就是 varchar,**无需 DB 迁移**;`row_per_chunk` 列已存在。

## 后端改动(data-service)

1. **`IngestionStatus`** 枚举加 `STAGED`。
2. **`DocumentService.upload`**:存 MinIO + 落 `kb_document`,状态置 `STAGED`,**不再** publish `IngestionMessage`。上传时不接收/使用 `rowPerChunk`(存默认 false)。
   - 幂等分支:同 `(kbId, fileHash)` 的 DONE 记录仍直接返回跳过整条流水线;非 DONE 的旧记录复用并**重置为 STAGED、不发消息**(等用户再确认)。
3. **新增确认接口** `POST /rag/kb/{kbId}/documents/confirm`,带 `rowPerChunk` 参数:
   - 查出本知识库下所有 `STAGED` 文档;
   - 给每篇写入本次的 `rowPerChunk`、状态置 `UPLOADED`、`publish` 一条 `IngestionMessage`;
   - 返回被确认的文档列表(或数量);若无待确认文件返回空。
   - 入口做 `permissionResolver.assertCurrentAccess(KNOWLEDGE_BASE, kbId)`,与其它接口一致。
4. `retry` / `delete` 不变:删 STAGED 文档无 chunk,`chunkIndexService.deleteByDoc` 为空操作,安全。

**为何 rowPerChunk 移到确认时定**:勾选框在上传弹窗里,用户可能传完文件后再改勾选、最后才点确认。让 confirm 接口携带该值并统一写给本批所有待确认文档,贴合「勾上只对 csv/xlsx 生效、批里没有这两类也能勾(无副作用)」的语义。

## 前端改动(jm-agent-front)

1. **类型/状态**(`api/types.ts` + `features/knowledge/utils.ts`):
   - `DocStatus` 加 `'STAGED'`。
   - `DOC_STATUS_TEXT.STAGED = '待确认'`;`DOC_STATUS_COLOR.STAGED = 'default'`;`DOC_STATUS_PROGRESS.STAGED = 0`。
   - **`isPending` 不把 STAGED 当处理中**:避免列表 3s 轮询因待确认文件空转,「处理中」筛选不误收。
2. **`features/knowledge/api.ts`**:
   - `docApi.upload` 去掉 `rowPerChunk` 入参(上传只存)。
   - 新增 `docApi.confirm(kbId, rowPerChunk)` → `POST /rag/kb/${kbId}/documents/confirm`。
3. **上传弹窗**(`pages/console/knowledge/KnowledgeDetailPage.tsx`):
   - Dragger 行为不变:拖入即逐个上传 → 落 STAGED,列表显示「待确认」。
   - 弹窗 footer 改为 `取消` + 主按钮 `确认入库`。点「确认入库」→ 调 confirm → 提示「已开始处理 N 个文件」→ 刷新列表 + 关窗。
   - rowPerChunk 勾选框留在弹窗内,值在点「确认入库」时传给接口。
   - 文案改写:「文件加入后即开始解析与向量化」→「文件上传后停在『待确认』,点『确认入库』才开始切片与向量化」。
4. **列表行**:STAGED 行显示「待确认」徽标。确认入口在弹窗内;确认范围是「本库所有 STAGED」,因此重新打开弹窗(不传新文件)直接点「确认入库」也能处理遗留待确认文件。弹窗内在存在待确认文件时显示一行提示,避免找不到入口。

## 验证(端到端实跑 + 查库)

- 启动本地栈,上传混合文件(doc+csv+xlsx+txt):查 `kb_document` 状态应全为 `STAGED`,RabbitMQ 无消息。
- 点「确认入库」(勾上逐行切片):查库状态推进、csv/xlsx 的 `row_per_chunk=1`、最终 DONE 且 chunk 落库。
- 幂等:重传已 DONE 文件跳过;重传待确认文件仍 STAGED。

## 不做(YAGNI)

- 不做单文件级别的逐个确认按钮(用户明确选批量)。
- 不做按上传人隔离的待确认范围(确认本库所有 STAGED 即可)。
- 不改分块器/解析器逻辑。
