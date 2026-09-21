-- connection 上再加两列：这一份说明书【是不是全本】，以及不全在哪。
--
-- 执行方式同前（本项目没有 Flyway，必须手工执行、且先于部署新后端）：
--   dev ：docker exec -i dev-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 data-server < 本文件
--   生产：容器名换成 ds-mysql，库名以生产为准
-- 漏掉 --default-character-set=utf8mb4，下面的中文 COMMENT 会被双重编码成乱码，而且改不回去只能重 ALTER。
--
-- ALTER 不幂等，第二次会报 ERROR 1060 Duplicate column name 'semantic_coverage'。【这是预期的】。
--
-- 【必须先于部署】：新版 Connection 实体已经映射了这两列，列不存在时 MyBatis 生成的 SELECT 会带上它们，
-- 结果是所有碰 connection 的接口一律 Unknown column → 500，连管理面都打不开。
--
-- ============================================================================
-- 为什么需要这两列（问题不是「没显示」）
-- ============================================================================
-- 残缺这件事后端一直如实记着，只是记在 semantic_note 那段中文散文里。真实例子，一字不改：
--   「模型输出疑似被 max_tokens 截断，已截到最后一个完整条目，说明书不完整
--     （可调大 connector.semantic.max-tokens）」
-- 后面还接着上一轮的「14 张表本次只覆盖 9 张」。诚实，但是【没有形状】：
--   ① 它混在其它说明里，不显眼；
--   ② 更要命的是它【查不出来】——「哪些连接的说明书是残缺的」这个问题，
--      在一段随时会改的散文上只能靠 LIKE 去猜，而猜错的方向是「以为都是全本」。
--
-- 所以这里【不动那段散文】（前端和运维都在读它），只在它旁边落一个结构化信号：
-- 散文回答「这次发生了什么」，这两列回答「这份说明书现在能不能当全本用」。现在那条 SQL 一行就够：
--   SELECT id, name, semantic_gaps FROM `connection` WHERE `semantic_coverage` = 'PARTIAL';
--
-- ★ 为什么这件事值得占一列：说明书残缺的失败方式，正是这套设计从头到尾在防的那一类——
--   模型看不见那些表和字段，它【不会报错】，只会答得不对。少一张订单明细表，
--   它照样给出一个看起来很正常的数字，没有任何一层会为此报警。
--
-- ============================================================================
-- NULL 是三态里的一态，不是缺值
-- ============================================================================
-- NULL = 【没跑过】：存量连接（这一列上线之前建的）、以及从未成功生成过说明书的连接。
-- COMPLETE / PARTIAL 只由一次【成功生成】写下，失败与中断都不动它们——
-- 那两种情况下库里留着的还是上一版说明书，这两列描述的就该还是上一版。
--
-- ★「没跑过」和「残缺」必须分得开。把存量行一律当残缺，等于上线当天把所有连接标成红的，
--   然后所有人一起学会忽略这个标记——那比没有这个标记更糟。前端同样按这条规矩渲染。
--
-- 刻意【不建索引】：单租户的连接是个位数到两位数，低基数列上的索引只是白占写入成本，
-- 上面那条审计 SQL 全表扫就够。（另：connector_semantic 的唯一键已用掉 3072 字节里的 2620，
-- 任何新列都不要往那个索引里加。）

ALTER TABLE `connection`
  ADD COLUMN `semantic_coverage` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
      DEFAULT NULL COMMENT '当前这份说明书是不是全本。COMPLETE=本次该覆盖的都覆盖到了（只说没有整块缺失，不保证内容对）；PARTIAL=有整块内容缺失，缺在哪见 semantic_gaps；NULL=【没跑过】（存量行，或从未成功生成过）——NULL 不等于残缺，两者必须分开显示。只由一次成功生成写下，失败/中断不动它：那时库里还是上一版说明书，这一列描述的也该还是上一版。审计用：WHERE semantic_coverage = 「PARTIAL」' AFTER `semantic_note`,
  ADD COLUMN `semantic_gaps` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
      DEFAULT NULL COMMENT '残缺的成因码，逗号分隔，与 SemanticGap 枚举常量名一一对应：TABLES_MISSING=该覆盖的表没覆盖全 / TABLES_GAVE_UP=有表反复失败后被放弃 / SNAPSHOT_TRUNCATED=结构快照自己就在 200 个对象处截断了（重生成语义层补不回来，得先解决快照那一层）/ MODEL_OUTPUT_TRUNCATED=模型输出被 max_tokens 截断。semantic_coverage=COMPLETE 时是空串（不是 NULL：它和 coverage 在同一次更新里落库，而 MyBatis-Plus 的 NOT_NULL 策略写不了 null，留 NULL 会把上一轮的成因码留在行上，配出一行「说自己完整却列着缺口」的自相矛盾记录）';
