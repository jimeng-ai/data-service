-- 待审批的写操作队列。配合 connection.write_policy = 'REQUIRE_APPROVAL' 这一档。
--
-- ★ 上线纪律（本项目没有 Flyway，脚本不会随启动自动跑）：
--   ① 先执行本脚本  ② 校验  ③ 再部署新后端
--   docker exec -i ds-mysql mysql --default-character-set=utf8mb4 -uroot -p<pwd> data-server < 本文件
--   （必须带 --default-character-set=utf8mb4，否则下面的中文 COMMENT 会被双重编码成乱码）
--   顺序反了 = 生产带着缺表上线，审批相关查询全部 Table doesn't exist 报 500。
--   校验：SHOW CREATE TABLE `connector_pending_write`\G 看列注释是不是中文、表尾有没有 COLLATE。
--
-- ★ 【别忘了第三步】这张表带 tenant_id，必须同时出现在
--   JimengTenantLineHandler.TENANT_AWARE_TABLES 里。漏登记【不报错】——查询照常返回，
--   只是不带租户条件，于是 A 租户的超管能在审批列表里看到并【批准】B 租户的写操作。
--   本次改动已经把 connector_pending_write 加进去了，并在单测里补了断言。
--
-- ★ 为什么这张表值得单独建，而不是塞进 connector_audit
--   connector_audit 是【仅追加】的既成事实记录；这张表是【有状态、会流转】的待办队列，
--   还要被并发地抢（两个超管同时点批准）。把一张需要 UPDATE ... WHERE status='PENDING'
--   抢锁的表混进"能改的审计等于没有审计"的那张表里，两边的性质都会被破坏。

CREATE TABLE IF NOT EXISTS `connector_pending_write` (
  `id`             bigint        NOT NULL COMMENT '主键',
  `tenant_id`      varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户 ID',
  `connector_id`   bigint        NOT NULL COMMENT 'connection.id',
  `connector_name` varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '连接名，冗余一份：连接删掉之后这条审批记录还得说得清当时要改的是哪个系统',
  `agent_id`       bigint        NOT NULL COMMENT '提交写请求的 Agent。批准执行时要按它重建 Agent 上下文——审批执行的是"当初那个 Agent 的请求"，权限就该按那个 Agent 判，所以它参与授权，不允许为空',
  `trace_id`       varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '链路 ID，与 ai_trace 对齐：可回到"模型当时为什么要写这一条"的完整上下文',
  `operation`      varchar(16)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '操作类型：INSERT / UPDATE / DELETE。由 WriteSqlGuard 解析语句类型得出，不是模型自报的',
  `target_table`   varchar(255)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '目标表名，同样来自护栏解析。审批界面靠它让人一眼看出改的是哪张表',
  `statement_text` text          CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '待执行的语句原文。★批准时【照这一列执行】，所以它绝不能被截断落库（截断过的语句要么语法错，要么 WHERE 少一半变成范围完全不同的更新），超长在提交时就拒；落库后也不再改写——人批准的就是这段文本',
  `status`         varchar(16)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING | APPROVED | REJECTED | EXPIRED | FAILED。FAILED 是终态不回退 PENDING：回退会让人以为还能再点一次，而那条语句可能已经部分生效',
  `affected_rows`  int           DEFAULT NULL COMMENT '实际影响行数，执行后回填。为空 = 还没执行，或执行结果未知（进程在执行途中挂了）',
  `error_detail`   varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败/拒绝/过期原因。只放 ConnectorException 的 safeDetail 或平台自己写的文案，绝不放原始异常——原始异常带 SQL 片段/主机名/连接参数，而这一列随审批列表出网',
  `submitted_by`   varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '提交时请求头里的 user-id。可空：Agent 跑在异步线程上时不一定捎带得到',
  `decided_by`     varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审批人 user-id。定时过期作废的行这里为空，表示"没有人做过决定"',
  `decided_at`     datetime      DEFAULT NULL COMMENT '审批时间',
  `expires_at`     datetime      NOT NULL COMMENT '过期时间。写请求会腐烂：模型三天前按当时数据算出的 WHERE 条件，今天命中的可能是另一批行——让陈年请求还能被点批准，等于拿旧判断改新数据',
  `deleted`        tinyint(1)    NOT NULL DEFAULT '0' COMMENT '逻辑删除。BaseEntity 全局 @TableLogic，这列少了会让所有查询报 Unknown column',
  `create_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，也是提交时间',
  `create_user`    varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `update_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `update_user`    varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修改人',
  PRIMARY KEY (`id`),
  -- 管理面主查询：本租户还有哪些待办、按提交时间倒序。status 放中间是因为界面默认只看 PENDING，
  -- create_time 放最后让排序直接吃这个索引，不退化成 filesort。
  KEY `idx_connector_pending_write_tenant_status_time` (`tenant_id`,`status`,`create_time`),
  -- 从一条连接反查它累积了哪些写请求（删连接前要看一眼）。
  KEY `idx_connector_pending_write_connector` (`connector_id`),
  -- 从一个 Agent 反查它到底想改些什么——排查"这个 Agent 是不是被提示词注入了"时的第一入口。
  KEY `idx_connector_pending_write_agent` (`agent_id`),
  KEY `idx_connector_pending_write_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='待审批的写操作队列';

-- 刻意【无唯一键】：同一个 Agent 在同一毫秒对同一张表提两条一样的写请求是合法的
-- （比如给两个不同的人补同一类备注，语句碰巧相同）。去重会吞掉第二条，而被吞掉的那条
-- 正是事后要回溯的证据。无唯一键也顺带躲开了"软删行占着唯一键、第二次插入撞车"那个坑。
--
-- 表只增不减，目前【没有】归档任务。expireStale 只改状态不删行——过期未处理这件事本身
-- 就是要留痕的（说明有人把写请求晾在那没管）。量起来之后要补按 create_time 的归档。
