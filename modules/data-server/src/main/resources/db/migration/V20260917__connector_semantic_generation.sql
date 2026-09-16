-- 语义层生成 agent：批次 / 批次内每表 / 重新生成暂存，外加结构快照的重要性排名。
--
-- 执行方式（本项目没有 Flyway，必须手工执行，而且要先于部署新后端）：
--   dev ：docker exec -i dev-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 data-server < 本文件
--   生产：在生产 Mac mini 上执行同一条命令，容器名换成 ds-mysql，库名以生产为准
-- 漏掉 --default-character-set=utf8mb4，下面的中文 COMMENT 会被双重编码成乱码。
--
-- 重复执行：三张表用 CREATE TABLE IF NOT EXISTS，可以反复跑；
-- 末尾给 connector_schema 加列的 ALTER 不幂等，第二次会报
-- ERROR 1060 Duplicate column name 'importance_rank'。【这是预期的】，说明那一段已经执行过。
--
-- 顺序不能反：新版 ConnectorSchema 实体映射了 importance_rank。列不存在时，所有读 connector_schema 的查询
-- （结构刷新、conn_catalog、推导）一律 Unknown column → 500。
--
-- 【别忘了】三张表都带 tenant_id，必须同时登记进 JimengTenantLineHandler.TENANT_AWARE_TABLES。
-- 漏登记【不报错】：查询照常返回，只是不带租户条件，跨租户静默泄露。
-- 这里存的是客户的表名、列名和对它们的说明，敏感程度与 connector_semantic 相同。
--
-- 三张表都只做【物理删除】，唯一键因此不含 deleted。不要给这几张表加逻辑删除入口：
-- BaseMapper.delete 在全局 @TableLogic 下是软删，死行会占住唯一键。

-- ============================================================================
-- connector_semantic_generation：一次「生成语义层」一行
-- ============================================================================
CREATE TABLE IF NOT EXISTS `connector_semantic_generation` (
  `id`                    bigint        NOT NULL COMMENT '主键，也是回调 token 里的 gen',
  `tenant_id`             varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户 ID',
  `connector_id`          bigint        NOT NULL COMMENT 'connection.id',
  `mode`                  varchar(16)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'DIRECT=按表直写主表，写完立即可见（从未成功生成过的连接）/ STAGED=写暂存表，全部覆盖完再用一个事务替换（重新生成）',
  `trigger_kind`          varchar(24)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'CONNECTOR_CREATED=新建连接后自动触发 / MANUAL_REGENERATE=管理台点「重新生成」',
  `triggered_by`          bigint        DEFAULT NULL COMMENT '触发的超管 sys_user.id（实体字段 Long，签回调 token 时 String.valueOf）。agent 路径必填（回调 token 的 id claim）；异步线程拿不到 create_user，所以显式存',
  `status`                varchar(16)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED / RUNNING / FINALIZING / INTERRUPTED 为未结束；READY / FAILED / FELL_BACK / CANCELLED 为终态',
  -- 派生列：只为下面那个唯一键服务，保证「每条连接最多一个未结束的批次」。
  -- 实体【不得】映射这一列：MyBatis-Plus 一旦往生成列里写值，就会报 ERROR 3105。
  `active_connector_id`   bigint GENERATED ALWAYS AS (CASE WHEN `status` IN ('QUEUED','RUNNING','FINALIZING','INTERRUPTED') THEN `connector_id` ELSE NULL END) VIRTUAL COMMENT '未结束时等于 connector_id，终态为 NULL；只用于唯一键',
  `model`                 varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最近一次下发给 sandbox 的模型名（如 deepseek-flash）',
  `models_seen`           varchar(255)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '各片 message_start 里实际出现过的模型名，逗号分隔去重；出现配置外的名字即停批',
  `total_tables`          int           NOT NULL DEFAULT '0' COMMENT 'N：纳入生成范围的表数（不含 SKIPPED / REMOVED）',
  `done_tables`           int           NOT NULL DEFAULT '0' COMMENT 'k：DONE 的表数，即进度的分子',
  `skipped_tables`        int           NOT NULL DEFAULT '0' COMMENT '不在范围内的表数：没有列 / 表名超 191 / 描述失败',
  `gave_up_tables`        int           NOT NULL DEFAULT '0' COMMENT '已放弃的表数（提交次数或派发次数用尽、结构反复变化）',
  `removed_tables`        int           NOT NULL DEFAULT '0' COMMENT '生成期间从结构快照里消失的表数',
  `slice_count`           int           NOT NULL DEFAULT '0' COMMENT 'M：预计总片数，每片开始时按剩余表数重新估算',
  `current_slice_no`      int           NOT NULL DEFAULT '0' COMMENT 'i：当前或最近一次派发的片号，从 1 开始；0 表示还没派发过',
  `current_run_id`        varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '正在跑的 sandbox runId；运行结束或取消后立即置 NULL，回调过滤器以它校验 token 的 rid',
  `current_run_callbacks` int           NOT NULL DEFAULT '0' COMMENT '当前这次运行收到的回调次数；为 0 说明回调没到达（NO_CALLBACK 判定）',
  `owner_token`           varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '当前持有者（编排器每轮排空生成的随机串）。心跳、推进、收尾都以它做 CAS；QUEUED 时为 NULL',
  `heartbeat_at`          datetime      DEFAULT NULL COMMENT '最近一次心跳。RUNNING / FINALIZING 超过 180 秒未更新视为持有者已死',
  `claim_at`              datetime      DEFAULT NULL COMMENT '与 connection.semantic_claim_at 同值的认领凭据，续期时两边一起换新值',
  `prev_semantic_status`  varchar(16)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '首次认领前 connection.semantic_status 的值；STAGED 中断或失败时据此恢复',
  `resume_count`          int           NOT NULL DEFAULT '0' COMMENT '被再次触发续跑的次数',
  `not_before`            datetime      DEFAULT NULL COMMENT 'QUEUED 行最早可被排空的时间（连接认领暂时抢不到时用）',
  `input_tokens`          bigint        NOT NULL DEFAULT '0' COMMENT '各片 summary.usage 累加：input_tokens',
  `output_tokens`         bigint        NOT NULL DEFAULT '0' COMMENT '累加：output_tokens（含推理 token）',
  `cache_read_tokens`     bigint        NOT NULL DEFAULT '0' COMMENT '累加：cache_read_input_tokens',
  `cache_write_tokens`    bigint        NOT NULL DEFAULT '0' COMMENT '累加：cache_creation_input_tokens',
  `slices_without_usage`  int           NOT NULL DEFAULT '0' COMMENT '拿不到 summary.usage 的片数（被杀、超时），用量因此是下界',
  `tokens_at_resume`      bigint        NOT NULL DEFAULT '0' COMMENT '最近一次续跑时 input_tokens + output_tokens + cache_write_tokens 的值；成本闸按本轮用量（累计减去它）判定',
  `config_json`           text          CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '建批次时 connector.semantic.agent.* 的快照（不含 auth-token），只作留痕，不参与判定',
  `reason_code`           varchar(32)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '中断 / 失败 / 降级 / 取消的机器码，取值见设计文档 8.10 的 GenerationReasonCode；QUEUED 行认领暂时抢不到时记 CLAIM_BUSY，排空 CAS 时清空',
  `note`                  varchar(512)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '给人看的原因。只放平台自己写的安全文案，绝不放原始异常',
  `started_at`            datetime      DEFAULT NULL COMMENT '第一次进入 RUNNING 的时间',
  `finished_at`           datetime      DEFAULT NULL COMMENT '进入终态的时间',
  `deleted`               tinyint(1)    NOT NULL DEFAULT '0' COMMENT '逻辑删除。BaseEntity 全局 @TableLogic 要求有这一列；本表只做物理删除',
  `create_time`           datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（即排队时间）',
  `create_user`           varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `update_time`           datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `update_user`           varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修改人',
  PRIMARY KEY (`id`),
  -- 同一租户、同一条连接上最多一个未结束的批次。终态行的派生列为 NULL，InnoDB 唯一键允许多个 NULL。
  -- 字节数：256 + 8 = 264，远低于 3072。
  UNIQUE KEY `uk_connector_semantic_generation_active` (`tenant_id`,`active_connector_id`),
  -- 取某条连接最近一次批次
  KEY `idx_connector_semantic_generation_conn_time` (`connector_id`,`create_time`),
  -- 排空队列（QUEUED 按创建时间先进先出）
  KEY `idx_connector_semantic_generation_status_time` (`status`,`create_time`),
  -- 找心跳过期的批次（9.6）
  KEY `idx_connector_semantic_generation_status_hb` (`status`,`heartbeat_at`),
  KEY `idx_connector_semantic_generation_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='语义层生成批次';

-- ============================================================================
-- connector_semantic_generation_table：批次内一张表一行
-- ============================================================================
-- object_name 用 varchar(255) 而不是 191：唯一键里只有 generation_id + object_name，
-- 字节数 8 + 255*4 = 1028，放得下；而且与 connector_schema.object_name 同宽，SKIPPED 的长表名也能原样留下。
CREATE TABLE IF NOT EXISTS `connector_semantic_generation_table` (
  `id`                 bigint        NOT NULL COMMENT '主键',
  `tenant_id`          varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户 ID',
  `connector_id`       bigint        NOT NULL COMMENT 'connection.id',
  `generation_id`      bigint        NOT NULL COMMENT 'connector_semantic_generation.id',
  `object_name`        varchar(255)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '表名，与 connector_schema.object_name 原样一致',
  `object_type`        varchar(32)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'TABLE / VIEW，从快照拷贝',
  `importance_rank`    int           DEFAULT NULL COMMENT '入批时从 connector_schema 拷贝；本批次内只按这一列排序，之后的刷新重新排名也不影响本批',
  `slice_no`           int           DEFAULT NULL COMMENT '最近一次被分到第几片（从 1 开始）；从未派发为 NULL',
  `status`             varchar(16)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / DISPATCHED / DONE / SKIPPED / GAVE_UP / REMOVED',
  `dispatch_count`     int           NOT NULL DEFAULT '0' COMMENT '随已开始的运行被派发却没有提交的次数；达到上限转 GAVE_UP',
  `submit_count`       int           NOT NULL DEFAULT '0' COMMENT '通过形状与范围校验的提交次数；达到上限且未 DONE 转 GAVE_UP',
  `structure_retries`  int           NOT NULL DEFAULT '0' COMMENT '因结构变化被重新排队的次数；超过 2 转 GAVE_UP',
  `structure_stamp`    varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最近一次 DONE 时本表的结构指纹。已覆盖 = DONE 且它与当前快照算出的指纹一致',
  `accepted_rows`      int           NOT NULL DEFAULT '0' COMMENT '最近一次提交写入的行数',
  `dropped_rows`       int           NOT NULL DEFAULT '0' COMMENT '最近一次提交被退回或丢弃的条目数',
  `last_reject_reason` varchar(512)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '最近一次退回、跳过、放弃或重新排队的原因（机器码 + 安全文案）',
  `dispatched_at`      datetime      DEFAULT NULL COMMENT '最近一次派发时间',
  `done_at`            datetime      DEFAULT NULL COMMENT '最近一次 DONE 的时间',
  `deleted`            tinyint(1)    NOT NULL DEFAULT '0' COMMENT '逻辑删除。BaseEntity 全局 @TableLogic 要求有这一列；本表只做物理删除',
  `create_time`        datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_user`        varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `update_time`        datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `update_user`        varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修改人',
  PRIMARY KEY (`id`),
  -- 一个批次里同一张表只有一行。排序规则大小写不敏感，与 connector_schema 的唯一键是同一个限制。
  UNIQUE KEY `uk_connector_semantic_generation_table` (`generation_id`,`object_name`),
  -- 取下一片、按状态计数、续跑时把 DISPATCHED 拨回 PENDING
  KEY `idx_connector_semantic_generation_table_gen_status` (`generation_id`,`status`,`slice_no`),
  KEY `idx_connector_semantic_generation_table_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='语义层生成批次内的每张表';

-- ============================================================================
-- connector_semantic_staged：重新生成（STAGED）的暂存行
-- ============================================================================
-- 列形状与 connector_semantic 中机器推断会写的那部分一致；answered_* / trace_id / history_json 只属于 HUMAN 行，这里不要。
-- 不设 source 列：暂存行一律是 INFERRED，搬入时用字面量写。
-- 暂存行放在库里而不是 Redis：push main 即部署，半轮暂存必须能扛过重启，续跑才有东西可接。
CREATE TABLE IF NOT EXISTS `connector_semantic_staged` (
  `id`            bigint        NOT NULL COMMENT '主键。收尾时原样作为 connector_semantic.id 搬入（雪花 id 全局唯一，暂存行在同一事务里删除）',
  `tenant_id`     varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户 ID',
  `connector_id`  bigint        NOT NULL COMMENT 'connection.id',
  `generation_id` bigint        NOT NULL COMMENT 'connector_semantic_generation.id',
  `owner_object`  varchar(191)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '哪张表的提交产出了这一行。OBJECT / FIELD / JOIN 等于 object_name；CAVEAT 记第一个提交它的表，只作留痕',
  `scope`         varchar(16)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'OBJECT / FIELD / JOIN / CAVEAT（agent 永远不产出 METRIC）',
  `object_name`   varchar(191)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '同 connector_semantic.object_name；CAVEAT 为空串',
  `field_name`    varchar(191)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '同 connector_semantic.field_name；JOIN 存左侧列名',
  `term`          varchar(191)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '同 connector_semantic.term；仅 CAVEAT 使用',
  `gloss`         varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '同 connector_semantic.gloss',
  `detail_json`   text          CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '同 connector_semantic.detail_json',
  `evidence`      varchar(16)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'COMMENT / DATA / NAME；GUESS 在提交时已丢弃；CAVEAT 为 NULL',
  `confidence`    tinyint       DEFAULT NULL COMMENT '0-100',
  `verified`      varchar(16)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'NONE' COMMENT 'agent 路径不做采样验证，恒为 NONE',
  `status`        varchar(16)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT；收尾预检发现 JOIN 右端结构已变时改成 STALE',
  `anchor_kind`   varchar(16)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'NONE / FIELD / JOIN，由服务端按快照计算',
  `anchor_hash`   varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '写入时的锚点 sha256，由服务端按快照计算',
  `deleted`       tinyint(1)    NOT NULL DEFAULT '0' COMMENT '逻辑删除。BaseEntity 全局 @TableLogic 要求有这一列；本表只做物理删除',
  `create_time`   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_user`   varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人',
  `update_time`   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `update_user`   varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修改人',
  PRIMARY KEY (`id`),
  -- 与 uk_connector_semantic 同形，只是把 (tenant_id, connector_id) 换成 generation_id：
  -- 暂存阶段就按主表唯一键去重，搬入时批次内部不会自己撞键。
  -- 字节数：8 + 64 + 191*4*3 = 2364，低于 3072。任何新列都不要再往里加。
  UNIQUE KEY `uk_connector_semantic_staged` (`generation_id`,`scope`,`object_name`,`field_name`,`term`),
  -- 按表替换暂存行（同一张表重交、结构变化作废）
  KEY `idx_connector_semantic_staged_owner` (`generation_id`,`owner_object`),
  -- 删除连接时清理
  KEY `idx_connector_semantic_staged_connector` (`connector_id`),
  KEY `idx_connector_semantic_staged_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='语义层重新生成的暂存行';

-- ============================================================================
-- connector_schema.importance_rank：刷新快照时这张表在目录里的位置
-- ============================================================================
-- 从前「重要性」只在 MySqlSession 拉目录时现算，不落库；快照读回按表名字母序排。
-- 生成语义层要按重要性切片，所以在落快照时把目录位置一起存下来。
-- 刻意【不建索引】：子项目 1 每条连接最多 200 行，按连接取出后排序足够。子项目 2 放开上限时再评估。
ALTER TABLE `connector_schema`
  ADD COLUMN `importance_rank` int DEFAULT NULL
  COMMENT '本次刷新时这张表在连接器目录里的位置，从 1 开始，越小越重要（MySQL：估算行数数量级降序 → 被外键引用次数降序 → 表名升序）。NULL = 本列上线之前落的快照，读取时按 id 升序回退'
  AFTER `content_hash`;
