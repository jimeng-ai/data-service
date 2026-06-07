-- ops-20260607-trace-kb-subtitle
-- 历史 KB_SEARCH 步骤副标题措辞统一：把 "top_k=N · 命中 M 个分片" 改为
-- "召回候选 N · 命中 M 个分片"，与新埋点(TraceRecorder.recordKbSearch)一致——
-- 该 N 是 rerank 前的 RRF 召回候选窗口（非 rag_search 工具入参 top_k），避免与之混淆。

UPDATE ai_trace_step
SET sub_title = REPLACE(sub_title, 'top_k=', '召回候选 ')
WHERE step_type = 'KB_SEARCH'
  AND sub_title LIKE 'top\_k=%';
