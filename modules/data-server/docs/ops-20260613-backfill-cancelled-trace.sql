-- 回填历史「用户停止」trace：在引入 CANCELLED 状态前，用户主动停止会被记成 ERROR
-- （流式失败路径 recordLlm 一律传 errorMsg=null，取消与真失败在 ai_trace 内无法自分辨）。
--
-- 精确判据（不靠启发式）：取消的助手消息在 chat_message 里落成 status='CANCELLED'，
-- 而真失败落成 FAILED。两表无外键，但同一轮生成的 trace 与助手消息 create_time 同秒、同租户，
-- 故以「存在同租户、±2s 内、status='CANCELLED' 的助手消息」+「trace 自身 error_msg 为空」
-- 双条件锁定，避免把带真实错误信息的失败误判成取消。
--
-- 幂等：只改 status='ERROR' 的行；重复执行不会反向影响已是 CANCELLED 的行。
-- 先跑下方 SELECT 预览受影响行，确认无误再执行两条 UPDATE。

-- ── 预览：将被改判为 CANCELLED 的 trace ──
SELECT t.trace_id, t.create_time, t.step_count, t.error_msg
FROM ai_trace t
WHERE t.deleted = 0
  AND t.status = 'ERROR'
  AND t.error_msg IS NULL
  AND EXISTS (
        SELECT 1 FROM chat_message m
        WHERE m.deleted = 0
          AND m.tenant_id = t.tenant_id
          AND m.role = 'assistant'
          AND m.status = 'CANCELLED'
          AND ABS(TIMESTAMPDIFF(SECOND, t.create_time, m.create_time)) <= 2
      );

-- ── 1) 头表 ERROR → CANCELLED ──
UPDATE ai_trace t
SET t.status = 'CANCELLED'
WHERE t.deleted = 0
  AND t.status = 'ERROR'
  AND t.error_msg IS NULL
  AND EXISTS (
        SELECT 1 FROM chat_message m
        WHERE m.deleted = 0
          AND m.tenant_id = t.tenant_id
          AND m.role = 'assistant'
          AND m.status = 'CANCELLED'
          AND ABS(TIMESTAMPDIFF(SECOND, t.create_time, m.create_time)) <= 2
      );

-- ── 2) 这些 trace 下「error_msg 为空的 ERROR 步骤」同步改判（即被取消的 LLM 步）。
--      仍带真实错误信息的步骤保持 ERROR——头表按「取消优先」算 CANCELLED，但不抹掉步骤级真错。
UPDATE ai_trace_step s
JOIN ai_trace t ON t.trace_id = s.trace_id
SET s.status = 'CANCELLED'
WHERE t.status = 'CANCELLED'
  AND s.status = 'ERROR'
  AND s.error_msg IS NULL;
