-- 语义层：跟着一条连接走的「说明书」。不搬数据、不建仓，只在我们自己的库里存一份文字描述客户那个库。
--
-- 执行方式同前（本项目没有 Flyway，必须手工执行、且先于部署新后端）：
--   docker exec -i ds-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 data-server < 本文件
-- 漏掉 --default-character-set=utf8mb4 会把下面的中文 COMMENT 双重编码成乱码。
--
-- 重复执行：建表用 CREATE TABLE IF NOT EXISTS，可反复跑；但文件末尾给 connection 加列的那段
-- ALTER 不幂等，第二次会报 ERROR 1060 Duplicate column name 'semantic_status'。
-- 【这是预期的】，说明这一段已经执行过，前面的建表并没有出问题（同 V20260913c 的做法）。
--
-- 【别忘了第三步】：本表带 tenant_id，必须同时出现在 JimengTenantLineHandler.TENANT_AWARE_TABLES 里。
-- 漏登记【不报错】——查询照常返回，只是不带租户条件，跨租户静默泄露。本次改动已经加进去了。
-- 这里存的是客户的表名、字段名、业务口径，是实打实的客户信息，比 connector_schema 更敏感。

-- ============================================================================
-- connector_semantic：一条语义断言一行
-- ============================================================================
-- 为什么不塞进 connector_schema.detail_json（设计决策 S2）：
-- 那张表的 refresh() 是【物理删除重插】（ConnectorSchemaService:200-201），而且它的 diff 只比
-- content_hash——那个哈希算的是结构指纹，不含 detail_json 的自定义键。所以把人工确认过的口径
-- 写进去，会被下一次刷新【静默】抹掉，且 diff 里一条记录都不会有。两者必须分生命周期。
--
-- 还有一条更硬的理由：connector_schema 每行的主键在每次 refresh 后都是【新的雪花 id】，
-- 任何外键指过去在第一次刷新后就悬空。所以本表用 (connector_id, object_name, field_name)
-- 这组【字符串名字】定位，绝不引用 connector_schema.id。
--
-- 唯一键刻意【不含 deleted】，沿用 connector_schema 的思路，但手段不同：
--   * source='INFERRED' 的行在重新推导时【物理删除】重插（ConnectorSemanticMapper#physicalDeleteInferred）；
--   * source='HUMAN' 的行【永不删除，只原地 UPDATE】——口径的纠正走覆盖，不走删除再建。
-- 两条合起来保证这张表上永远不会出现软删死行，也就不会撞 uk。
-- 不要给本表加逻辑删除入口：一旦有了，「同一逻辑行第二次软删撞键」这个坑会立刻回来
-- （ConnectorSchemaMapper:18-24 记了它已经咬过三次）。
CREATE TABLE IF NOT EXISTS `connector_semantic` (
  `id`            bigint        NOT NULL COMMENT '主键',
  `tenant_id`     varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '租户 ID',
  `connector_id`  bigint        NOT NULL COMMENT 'connection.id',

  `scope`         varchar(16)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'OBJECT=这张表是干什么的 / FIELD=这个字段什么意思 / JOIN=表怎么连 / METRIC=口径 / CAVEAT=告诫',
  `object_name`   varchar(191)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '对象名（表名）。METRIC 行为空串——口径挂在整条连接上，不挂某张表',
  `field_name`    varchar(191)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '字段名。仅 FIELD/JOIN 用；JOIN 存左侧列名',
  `term`          varchar(191)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '业务词条。仅 METRIC 用，如「销售额」。确定性改写要靠它【精确匹配】，不做模糊',

  `gloss`         varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '给模型看的那一句话。注入 conn_catalog 时截断到 80 字，完整版只在 conn_describe 给',
  `detail_json`   text          CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '按 scope 定形：JOIN={to_object,to_column,cardinality,containment,sample_n,match_n,basis}；METRIC={sql_fragment,ambiguities,applies_to}；OBJECT={table_shape,typical_questions}',

  `source`        varchar(16)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'INFERRED=机器推的 / HUMAN=人在对话里答的 / IMPORTED=从客户库注释等一手事实直接采信。HUMAN 永不被推断覆盖',
  `evidence`      varchar(16)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '依据来源（分界线是「有没有依据」不是「能不能验证」）：COMMENT=客户库注释 / DATA=数据里查得到 / NAME=名字本身说明含义 / GUESS=只能靠常识猜。GUESS 的东西不该被写进来',
  `confidence`    tinyint       DEFAULT NULL COMMENT '0-100，仅 INFERRED 有意义',
  `verified`      varchar(16)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '采样验证结论：CONFIRMED(包含率>=0.9) / WEAK(0.5~0.9) / REJECTED(<0.5) / UNDECIDABLE(左侧非空样本<10，表太空，判不了) / NONE(没验过)',
  `status`        varchar(16)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'DRAFT=推出来还没被用过 / CONFIRMED=可直接注入 / STALE=挂的结构已变，说明可能过时',

  -- ── 漂移锚点 ────────────────────────────────────────────────────────────────
  -- 【这一列是本设计里最要紧的一列】：产出一份说明书不难，让它长期为真才难。
  -- 但锚点的【粒度】不能按表：connector_schema.content_hash 是整表指纹
  -- （表名|类型 + 每列 name:type:nullable:comment，见 ConnectorSchemaService:255-266），
  -- 客户加一列它就变。若 OBJECT 行也锚它，那么「这张表是订单主表」会因为加了一个无关列
  -- 被标成 STALE —— 而加一列并不改变这句话。所以锚点按 scope 分：
  --   OBJECT  → 不锚结构（本列为 NULL）。只有整张表消失（ObjectDiff REMOVED）才失效。
  --   FIELD   → 锚【这一列自己】的指纹 name:type:nullable:comment。改别的列不影响它。
  --   JOIN    → 锚左右两列指纹的组合。任一端变了这条关系就不可信。
  --   METRIC  → 有 sql_fragment 时锚它引用到的列集合；纯人工口径没有 SQL 片段，为 NULL。
  --   CAVEAT  → NULL。
  `anchor_kind`   varchar(16)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '本行锚的是什么：NONE / FIELD / JOIN / COLUMN_SET。决定 anchor_hash 怎么重算',
  `anchor_hash`   varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '写入时对应锚点的 sha256。刷新后对不上 → status 置 STALE，而不是悄悄变成假的',

  -- ── 谁答的、什么时候答的 ────────────────────────────────────────────────────
  -- 不能依赖 BaseEntity 的 create_user：口径是在【对话流】里沉淀的，而对话跑在 streamExecutor 上，
  -- MdcAsyncSupport.wrap 只传 TenantContext 和 MDC，【不传 RequestContextHolder】
  -- （MdcAsyncSupport:28-30），于是 MyMetaObjectHandler.getCurrentUserId() 返回 null，
  -- create_user 是空的。而设计明确要求「记住是谁、什么时候答的」——配置期填口径的通常是懂业务的人，
  -- 对话里回答的可能是任何一个业务方，他说「扣退款」也许只是他那个场景扣。所以显式存。
  `answered_by`   varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '在对话里回答这条口径的人（sys_user.id）。引用时要亮出来，这是唯一的纠错通道',
  `answered_name` varchar(128)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '回答人显示名，快照留存。不实时 join——人离职改名后，"9月13日由张三确认"应该还是张三',
  `answered_at`   datetime      DEFAULT NULL COMMENT '回答时间',
  `trace_id`      varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '沉淀这条口径的那次对话。审批页能顺着 trace_id 回到对话，口径也应该能',

  -- ── 变更留痕 ────────────────────────────────────────────────────────────────
  -- 设计文档把这件事推给「将来若发现口径被改坏得太频繁再说」。不采纳，理由是成本不对称：
  -- 现在加，是这一列 + 几十行代码；等到发现口径被改坏，【历史根本不存在】，
  -- 连「什么时候开始错的」都查不出来。记录变更 ≠ 重新引入审核，这是两件事。
  -- BaseEntity 已经免费给了 who/when，真正缺的只有【旧值】，所以只存旧值。
  `history_json`  text          CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci COMMENT '追加型变更留痕 [{at,by,by_name,from_gloss,from_detail,trace_id}]，只保留最近 20 条。任何人都能在对话里覆盖口径且不做权限区分，这是那个已知代价的唯一取证材料',

  `deleted`       tinyint(1)    NOT NULL DEFAULT '0' COMMENT '逻辑删除。BaseEntity 全局 @TableLogic，这列少了会让所有查询报 Unknown column',
  `create_time`   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `create_user`   varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '创建人。异步推导写入时为 NULL（工作线程没有 RequestContextHolder），人答的口径看 answered_by',
  `update_time`   datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
  `update_user`   varchar(64)   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '修改人',
  PRIMARY KEY (`id`),
  -- 一条连接里同一个 (scope, 表, 字段, 词条) 只能有一行，否则注入时取哪一行变成不确定行为。
  -- 不含 deleted，前提是这张表【不产生软删死行】——见表头注释。
  --
  -- 【为什么这三列是 191 而不是 255】InnoDB 单个索引最长 3072 字节，utf8mb4 每字符算 4 字节。
  -- 按 255 算：tenant_id 256 + connector_id 8 + scope 64 + 255*4*3 = 3388 字节，
  -- 建表直接报 ERROR 1071「Specified key was too long」——【整张表建不出来】。
  -- 而这个功能是"没有就静默降级成没有说明书"，所以建表失败不会有任何人发现，
  -- 只会看到语义层永远是空的。已在 mysql:8.0 上实测过 255 必失败、191 可通过。
  -- 191 够用：MySQL 的表名/列名上限本来就是 64 字符，term 是「销售额」这类业务词。
  -- 收窄的是进索引的三列；gloss/detail_json 不进索引，不受影响。
  UNIQUE KEY `uk_connector_semantic` (`tenant_id`,`connector_id`,`scope`,`object_name`,`field_name`,`term`),
  -- 注入路径的主查询：按连接 + scope 取（catalog 取 OBJECT+METRIC，describe 取某表的 FIELD+JOIN）
  KEY `idx_connector_semantic_conn_scope` (`connector_id`,`scope`),
  -- 漂移处置：某张表结构变了，要把挂在它上面的行挑出来
  KEY `idx_connector_semantic_object` (`connector_id`,`object_name`),
  KEY `idx_connector_semantic_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='连接器语义层：表用途 / 字段含义 / 表关系 / 业务口径';

-- ============================================================================
-- connection 上的语义层状态，给管理台显示「语义层：生成中 / 已生成（N 项）/ 失败」
-- ============================================================================
-- 放在 connection 行上而不是单独一张表：它是每条连接一行的状态，且要跟着连接一起读。
ALTER TABLE `connection`
  ADD COLUMN `semantic_status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
      DEFAULT NULL COMMENT '语义层推导状态：NONE / RUNNING / READY / FAILED。推导失败不影响连接可用' AFTER `write_policy`,
  ADD COLUMN `semantic_synced_at` datetime DEFAULT NULL COMMENT '语义层最近一次【成功】生成的时间。失败的重试不动它——否则一次失败就把「上次什么时候还是好的」抹掉了，而那正是排查时最需要的一条信息',
  ADD COLUMN `semantic_claim_at` datetime DEFAULT NULL COMMENT '本次/最近一次推导【认领】的时间。它是并发认领的凭据：抢占用它做 CAS，回写终态也用它比对，防止一次跑得慢的失败盖掉后面那次成功。超过阈值未回写视为进程中途挂了，可被抢占（否则重启一次这条连接就永远卡在「生成中」）',
  ADD COLUMN `semantic_note` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
      DEFAULT NULL COMMENT '推导结果摘要或失败原因，给管理台显示',

  -- ── 数据出库档位（§10 三档数据落点开关）────────────────────────────────────
  --
  -- 【这一列是给客户看的安全承诺，不是内部开关】。下面这段注释和列上的 COMMENT 一起，
  -- 就是「我们到底从你的库里拿走什么」这个问题的完整答案；写不清楚，客户只能靠猜，
  -- 而靠猜的结论一定是往坏处猜。
  --
  -- ★ 为什么是三档，不是一个布尔
  -- 「数据不出域」和「不能读数据」是两件事。并成一个开关的代价有确切数字：同一批【真实生产库】上，
  -- 纯元数据推断（Nexus，专为隐私约束设计）F1 0.61 / 精确率 0.49；允许在库内采样的（Tursio）
  -- F1 0.80 / 精确率 1.00。一个字节的数据值都不碰，表关系推断的精确率【大约腰斩】——
  -- 而推错的表关系不报错，它让模型 join 出一个看着很正常的错数字。
  -- 表名、「这一列 NULL 率 3%」、「这一列的 top-20 实际取值」三者的敏感度差着数量级，
  -- 用一个布尔表达，客户只能一刀切到最严那头，然后为此付掉上面那半个精确率。
  --
  -- ★ 第 3 档必须是显式动作，兜底永远到不了它
  -- 空值 → 第 2 档（列是后加的，NULL 只说明「没选过」，不说明「选了最严的」；
  -- 把没选过一律压到第 1 档，等于一次升级里静默把所有存量客户的推断精确率打对折）。
  -- 认不出来的值 → 第 1 档（手工改过库、或更新版本写下了本版本不认识的档位，不认识就按最严处理）。
  -- 两条兜底方向不同但共守一条：SAMPLE_VALUES 只能由原样匹配到达。解析见 SemanticDataTier.parse。
  --
  -- ★ 第 3 档在 PII 过滤（S4）落地之前【不得对外开放】
  -- top-k 样本值天然会把 PII 捞出来——姓名、手机号、地址就躺在高频取值里。
  -- 同类实现公布的 PII 检测指标是 recall 95% / precision 91%，即大约每 20 个 PII 取值仍漏 1 个；
  -- 那是做过这件事的人的水平，更说明「没过滤就开第 3 档」是什么量级的事。
  -- 参考实现（Quick BI 的「维值学习」）索引的正是真实取值，而它的官方数据安全页回避了这一点。
  -- 我们不照搬这种叙事：这一列的 COMMENT 里必须出现「真实取值」四个字，接口文档和给客户的
  -- 安全说明用的是同一句话（SemanticDataTier.egressStatement()）。三处分叉，等于没有安全说明。
  --
  -- 审计谁开了第 3 档，一条 SQL 就够（这也是为什么存枚举名而不是 1/2/3——
  -- 存数字，审计和代码里迟早都会出现写反了的 >=）：
  --   SELECT id, name FROM `connection` WHERE `semantic_data_tier` = 'SAMPLE_VALUES';
  --
  -- 刻意【不建索引】：单租户的连接是个位数到两位数，低基数列上的索引只是白占写入成本；
  -- 上面那条审计 SQL 全表扫就够。（另：本文件里 uk_connector_semantic 已用到 3072 字节上限中的 2620，
  -- 任何新列都不要往那个索引里加。）
  ADD COLUMN `semantic_data_tier` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
      DEFAULT NULL COMMENT '数据出库档位——「数据不出域」不等于「不能读数据」，这一列就是那条界线的位置。METADATA_ONLY=第1档，只有表名/列名/类型/可空性/索引/客户自己写的注释离开数据库，不做任何聚合；DERIVED_STATS=第2档（默认），允许在客户库内聚合、只带走统计量：distinct数、NULL率、min/max、字符形状、两列包含率、基数、minhash sketch（K个哈希值，不是原始值）——逐行业务记录不出库，但要如实说：min/max 本身就是两个真实取值；SAMPLE_VALUES=第3档（默认关闭，须企业超管显式开启），把【真实取值】本身带出数据库：top-k 实际值、低基数列的全量维值索引——「地区」列的维值索引就是客户所有地区名进我们的库，这件事不做委婉表述。NULL=第2档（没选过不等于选了最严的）；认不出来的值一律按第1档处理；第3档只能由原样匹配到达，兜底永远到不了';
