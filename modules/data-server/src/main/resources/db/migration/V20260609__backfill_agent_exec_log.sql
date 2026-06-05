-- 回填 Agent 沙箱 exec 链路（endpoint='sandbox:agent-exec'）的历史模型调用日志。
-- 背景：仪表盘「最近使用·按 Agent」执行 selectRecentCalls，过滤 agent_id IS NULL（排除 RAG 子调用）。
--   exec 链路此前用 recordComputedCall 落库时未写 agent_id，且 Anthropic usage 不含 total，
--   导致这些行 agent_id / total_tokens 均为 NULL → 不在「最近使用」展示、token 列为空。
-- 代码侧已修：
--   - AiModelCallRecordService.recordComputedCall 增加 agentId 重载并落库；applyUsage total 回退 input+output。
--   - AgentExecService 记账传 run.getAgentId()，persistRunResult 的 run.total 同口径回退。
-- 本脚本只修「存量」数据；新数据由上述代码保证。两条 UPDATE 均幂等（WHERE ... IS NULL），可重复执行。
-- 依赖：必须在 V20260604（agent_id 列）、V20260605（token 列）、V20260606（agent_exec_run 表）之后执行。
-- 注意：本项目无 Flyway 自动执行，需手动在 data-server 库执行本脚本。上线顺序：建表/加列 → 部署新后端 → 跑本回填。

-- ① 回填 agent_id：经 ai_model_call_content.req_body 里的 note.run_id 关联 agent_exec_run.agent_id。
--    全表（不分租户）按 endpoint 刷；匹配不到（content 被归档 / run 已删）的行保持 NULL，前端回退显示模型名。
UPDATE ai_model_call_log l
JOIN ai_model_call_content c ON c.log_id = l.id
JOIN agent_exec_run r
     ON r.id = JSON_UNQUOTE(JSON_EXTRACT(c.req_body, '$.run_id'))
SET l.agent_id = CAST(r.agent_id AS UNSIGNED)
WHERE l.endpoint = 'sandbox:agent-exec'
  AND l.agent_id IS NULL
  AND r.agent_id REGEXP '^[0-9]+$';

-- ② 回填 total_tokens：Anthropic usage 无 total，按 input+output 补齐（与 applyUsage 回退同口径）。
--    只依赖本表，与 ① 互不依赖、先后无所谓。
UPDATE ai_model_call_log
SET total_tokens = COALESCE(input_tokens, 0) + COALESCE(output_tokens, 0)
WHERE endpoint = 'sandbox:agent-exec'
  AND total_tokens IS NULL
  AND (input_tokens IS NOT NULL OR output_tokens IS NOT NULL);
