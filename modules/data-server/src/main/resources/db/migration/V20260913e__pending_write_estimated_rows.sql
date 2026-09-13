-- 审批页需要的两样信息之一：提交时预估的影响行数。
--
-- 为什么要加这一列：原来的审批页只展示语句原文，而**看语句判不出范围**——
-- `UPDATE orders SET status='X' WHERE created_at < '2026-08-01'` 这条，
-- 审批的人无从知道它命中 3 行还是 30 万行，而这正是他唯一需要判断的事。
-- 已有的 affected_rows 是**执行之后**才回填的，那时候批已经批完了。
--
-- 另一样信息（trace_id）这一列本来就有，只是没进 PendingWriteView，属于代码缺陷不是缺列。
--
-- 可空：估不出来（INSERT ... SELECT、超时、方言不支持）就留空，
-- 估不出来不该阻止一条写请求进入审批队列。
ALTER TABLE `connector_pending_write`
  ADD COLUMN `estimated_rows` int DEFAULT NULL
  COMMENT '提交时预估的影响行数。不是承诺——估算在提交时、执行在批准时，中间数据会变；真正的防线仍是执行时 maxAffectedRows 整条回滚。估不出来为 NULL'
  AFTER `affected_rows`;
