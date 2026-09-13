-- 连接器框架的两张新表：自描述结果缓存 + 使用留痕。
--
-- 执行方式同 V20260913（本项目没有 Flyway，必须手工执行、且先于部署新后端）：
--   docker exec -i ds-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 data-server < 本文件
-- 漏掉 --default-character-set=utf8mb4 会把下面的中文 COMMENT 双重编码成乱码。
--
-- 两张表都用 CREATE TABLE IF NOT EXISTS，可重复执行。
--
-- 【别忘了第三步】：这两张表都带 tenant_id，必须同时出现在
-- JimengTenantLineHandler.TENANT_AWARE_TABLES 里。漏登记【不报错】——查询照常返回，
-- 只是不带租户条件，跨租户数据静默泄露。本次改动已经把两个表名加进去了，
-- 将来再加表时记住：那个白名单没有完备性门禁，单测漏了也照绿。

-- ============================================================================
-- connector_schema：自描述结果缓存，一个对象一行
-- ============================================================================
-- 为什么不塞进 connection 行里：它可能很大（几百张表的结构）、要独立刷新、还要能对比前后变化。
-- 塞进实例行等于每次读连接都拖一坨 longtext。
--
-- 为什么带 tenant_id：ai_model_call_content 那张大 JSON 侧表没有 tenant_id、也不在租户白名单里，
-- 照抄它就是零租户过滤。自描述里有客户的表名、字段名、业务注释，是实打实的客户信息。
--
-- 唯一键刻意【不含 deleted】，而刷新走【物理删除重插】（ConnectorSchemaMapper#physicalDeleteByConnector）。
-- 这是本次对「软删占唯一键」那个坑的解法：不是把 deleted 塞进唯一键
-- （uk_sys_enterprise_tenant 那样第二次软删还是撞），而是让这张表根本不产生软删死行。
CREATE TABLE IF NOT EXISTS `connector_schema` (
  `id`             bigint        NOT NULL COMMENT '主键',
  `tenant_id`      varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户 ID',
  `connector_id`   bigint        NOT NULL COMMENT 'connection.id',
  `object_type`    varchar(32)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '对象类型：TABLE / VIEW / ENDPOINT / DIR / TOPIC。由各连接器自己定义，框架不解释',
  `object_name`    varchar(255)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '对象名：表名 / 接口路径 / 目录 / 主题名',
  `object_comment` varchar(512)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '对象注释。目录级概览只回灌 name+comment，给模型当语义线索',
  `detail_json`    longtext      CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '对象细节完整 JSON（字段、类型、注释、索引…）。只在 Agent 明确要看某个对象时才取，全量注入放不下',
  `content_hash`   varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'detail_json 的 sha256。用途是"客户加了一个字段我们应该能知道"——刷新时比哈希挑出变化的对象，否则结构漂移是静默的，模型照旧结构写 SQL 报错才发现',
  `synced_at`      datetime      DEFAULT NULL COMMENT '本行同步时间',
  `deleted`        tinyint(1)    NOT NULL DEFAULT '0' COMMENT '逻辑删除。BaseEntity 全局 @TableLogic，这列少了会让所有查询报 Unknown column',
  `create_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_user`    varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `update_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `update_user`    varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修改人',
  PRIMARY KEY (`id`),
  -- 破例加唯一键：同一条连接里同名同类型的对象只能有一行，否则"看结构"会返回哪一行变成不确定行为。
  -- 配合物理删除重插，这个键上永远不会有软删死行。
  UNIQUE KEY `uk_connector_schema_object` (`tenant_id`,`connector_id`,`object_type`,`object_name`),
  KEY `idx_connector_schema_connector` (`connector_id`),
  KEY `idx_connector_schema_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='连接器自描述结果缓存';

-- ============================================================================
-- connector_audit：每次使用留痕，仅追加
-- ============================================================================
-- 为什么不寄生在 ai_trace_step 上：TraceRecorder.recordStep 的 step_index 是读-改-写无锁
-- （并发记步会重复），且每记一步要 select+insert+update 三次写。审计要的是"只追加 + 能按
-- 租户/连接/时间做区间查询"，形状不一样，硬挤两边都拖坏。两者用 trace_id 对得上就够了。
--
-- 刻意【无唯一键】：同一个 Agent 在同一毫秒重复跑同一条查询是合法的，去重会丢证据。
-- 无唯一键也顺带躲开了"软删行占唯一键"那个坑。
--
-- 表只增不减，目前【没有】任何归档/清理任务。等量起来了要补一个按 create_time 的分区或归档，
-- 这里先把话留下。
CREATE TABLE IF NOT EXISTS `connector_audit` (
  `id`             bigint        NOT NULL COMMENT '主键',
  `tenant_id`      varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户 ID',
  `connector_id`   bigint        NOT NULL COMMENT 'connection.id',
  `connector_name` varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '连接名，冗余一份：连接删掉之后审计还得说得清当时调的是哪条',
  `agent_id`       bigint        DEFAULT NULL COMMENT 'Agent ID。允许为空但空是坏消息：说明这次调用没带上 Agent 身份（AgentContext 只在请求体带 agent_id 时才设置），排查越权要盯这类行',
  `trace_id`       varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '链路 ID，与 ai_trace 对齐',
  `capability`     varchar(16)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '能力：QUERY / DESCRIBE / INVOKE / HEALTH',
  `operation`      varchar(128)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '具体操作：工具名 / 方法名 / HTTP method+path',
  `statement_text` text          CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT 'Agent 写的那条语句原文（SQL / 请求体摘要）。审计的核心证据，出事时全靠它',
  `row_count`      int           DEFAULT NULL COMMENT '返回行数。命中结果上限被截断时，这里记的是实际返回数——截断本身必须另外明说，不能静默',
  `elapsed_ms`     int           DEFAULT NULL COMMENT '耗时毫秒',
  `success`        tinyint(1)    NOT NULL DEFAULT '0' COMMENT '是否成功。默认 0：先写失败再改成功不可能，本表只 insert，写入时必须显式给值',
  `error_code`     varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '统一错误分类（ConnectorErrorCode 的名字），不是 HTTP 码也不是数据库厂商错误码',
  `error_detail`   varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败详情。只放 ConnectorException 的 safeDetail，绝不放原始异常——原始异常带 SQL 片段/主机名/连接参数，而审计列表是要出网给人看的',
  `deleted`        tinyint(1)    NOT NULL DEFAULT '0' COMMENT '逻辑删除。审计不该被删，这列只是为了满足 BaseEntity 的全局 @TableLogic',
  `create_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，也是审计时间轴',
  `create_user`    varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `update_time`    datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `update_user`    varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修改人',
  PRIMARY KEY (`id`),
  -- 管理面主查询：某租户某连接最近用了些什么。create_time 放在最后，区间扫描直接吃这个索引。
  KEY `idx_connector_audit_tenant_conn_time` (`tenant_id`,`connector_id`,`create_time`),
  -- 从一次对话反查它到底碰了哪些客户系统。
  KEY `idx_connector_audit_trace` (`trace_id`),
  -- 从一个 Agent 反查它的全部外部访问。
  KEY `idx_connector_audit_agent` (`agent_id`),
  KEY `idx_connector_audit_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='连接器使用留痕';
