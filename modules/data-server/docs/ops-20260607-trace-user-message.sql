-- ops-20260607-trace-user-message
-- 调用日志 Trace 头表新增「用户发送的消息」列，并回填历史数据。
--
-- 写入：TraceRecorder.recordUserMessage(...) 在对话循环首步、追加 tool_result 之前，
--       从请求体 messages 取最后一条 role=user 文本写入（仅一次）。
-- 回填：历史 trace 经 ai_trace_step(最早 LLM 步)→ref_log_id→ai_model_call_content.req_body，
--       用 JSON_TABLE 取该次请求里最后一条 user 消息（字符串内容直接取；Claude block 数组取首个 text 块）。
--       需 MySQL 8（JSON_TABLE / 窗口函数）。尽力而为，取不到的行保持 NULL。

ALTER TABLE ai_trace
    ADD COLUMN user_message VARCHAR(2000) NULL COMMENT '本次调用用户发送的消息（首步前捕获，仅记录一次）' AFTER agent_name;

UPDATE ai_trace t
JOIN (
    SELECT x.trace_id,
           LEFT(CASE WHEN JSON_TYPE(x.content) = 'STRING'
                     THEN JSON_UNQUOTE(x.content)
                     ELSE JSON_UNQUOTE(JSON_EXTRACT(x.content, '$[0].text')) END, 2000) AS user_message
    FROM (
        SELECT s.trace_id, jt.idx, jt.content,
               ROW_NUMBER() OVER (PARTITION BY s.trace_id ORDER BY jt.idx DESC) AS rn
        FROM ai_trace_step s
        JOIN (SELECT trace_id, MIN(step_index) AS mn
              FROM ai_trace_step
              WHERE step_type = 'LLM' AND ref_log_id IS NOT NULL
              GROUP BY trace_id) f
          ON f.trace_id = s.trace_id AND f.mn = s.step_index
        JOIN ai_model_call_content c ON c.log_id = s.ref_log_id
        JOIN JSON_TABLE(c.req_body, '$.messages[*]'
              COLUMNS (idx FOR ORDINALITY,
                       role VARCHAR(32) PATH '$.role',
                       content JSON PATH '$.content')) jt ON 1 = 1
        WHERE s.step_type = 'LLM' AND JSON_VALID(c.req_body) AND jt.role = 'user'
    ) x
    WHERE x.rn = 1
) m ON m.trace_id = t.trace_id
SET t.user_message = m.user_message
WHERE (t.user_message IS NULL OR t.user_message = '');
