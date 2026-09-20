-- connector_audit.capability 放开 NULL：这张表新增了第三类行——「管理面上人的动作」，它一次连接器能力都没用到。
--
-- 执行方式同前（本项目没有 Flyway，必须手工执行、且先于部署新后端）：
--   docker exec -i ds-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 data-server < 本文件
-- 本文件幂等，可反复执行（MODIFY 到同一定义不报错）。
--
-- 【必须先于部署】：不执行的话，新增的凭据取回接口会在插审计那一步抛
--   `Field 'capability' doesn't have a default value`，整个请求 500。
-- 这不是缺陷而是刻意的——ConnectorService.revealCredential 的纪律是「审计写不进去就不给明文」，
-- 所以漏执行这份 DDL 的后果是【功能不可用】，而不是【明文在没有记录的情况下流出去】。
-- 方向选对了的失败：吵闹、当场可见、不会悄悄放行。
--
-- ============================================================================
-- 为什么是放开 NULL，而不是给取回行随便填一个能力值
-- ============================================================================
-- capability 回答的是「这次访问用了连接器的哪一种能力」（QUERY / DESCRIBE / INVOKE / HEALTH）。
-- 凭据取回一种都没用到——它动的是我们自己库里那串密文，客户库上什么都没发生。
--
-- 填任何一个现有枚举值都是假话，而且坏的方向很具体：客户的 DBA 在管理台按 capability=QUERY
-- 过滤，想回答的是「你们到底查了我什么」，结果会看到一条根本没碰过他数据库的行。
-- 一个分不清「访问过」和「没访问过」的审计表，在最需要它的那次对话里是没有用的。
--
-- 所以 NULL 在这里不是「缺值」，是一个有意义的值：**这一行没有用到任何能力**。
--
-- ============================================================================
-- 顺带修掉一处早就存在、只是没人踩到的代码 ⇄ DDL 不一致
-- ============================================================================
-- ConnectorAuditService 的 record() 与 recordPlatformStage() 从第一版起就写着
--   row.setCapability(cap == null ? null : cap.name());
-- 也就是说 Java 侧一直认为 capability 可以为空，而 DDL 一直是 NOT NULL。
-- 只是既有调用方恰好全都传了非空值，于是这条不一致躺了下来。
-- 本次的管理面动作是第一个真的传 null 的调用方，把它照出来了。
--
-- 教训照例记一笔：**「代码允许、数据库不允许」这类分歧不会在写的时候报错，
-- 只会在第一个真正走到那条分支的调用方上爆炸**，而那通常是上线之后。

ALTER TABLE `connector_audit`
    MODIFY COLUMN `capability` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL
        COMMENT '能力：QUERY / DESCRIBE / INVOKE / HEALTH；NULL = 这一行没有用到任何能力（管理面动作，如 admin.credential_reveal）';
