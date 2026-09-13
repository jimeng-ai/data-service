-- 连接器的写操作开放程度。对应产品方案第 8 节的能力分级。
--
-- ★ 上线纪律（与 V20260913 相同，本项目没有 Flyway，脚本不会随启动自动跑）：
--   ① 先执行本脚本  ② 校验  ③ 再部署新后端
--   docker exec -i ds-mysql mysql --default-character-set=utf8mb4 -uroot -p<pwd> data-server < 本文件
--   （必须带 --default-character-set=utf8mb4，否则中文注释会双重编码成乱码）
--   顺序反了 = 生产带着缺列上线，连接器相关查询全部 Unknown column 报 500。
--
-- ★ 为什么默认值是 FORBIDDEN，且【不允许】用其它默认值
--   这一列决定 Agent 能不能改客户的生产业务数据，出错的后果是业务事故。
--   「授权的默认值只能是否」——存量行、新建行、以及任何解析不出来的值（见 WritePolicy.parse），
--   一律落到只读。往宽的方向回落就是本项目反复吃亏的那类静默降级，
--   而这里降级的后果是「能改客户的数据」。
--
-- ★ 它放宽的是【平台侧】的闸，放宽不了客户侧的账号权限
--   选了 AUTO 但客户给的仍是只读账号，写操作照样会被数据库拒绝——这是对的，
--   只读的承重层本来就在客户那边，本列只是决定平台要不要把请求发过去。
ALTER TABLE `connection`
  ADD COLUMN `write_policy` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
    NOT NULL DEFAULT 'FORBIDDEN'
    COMMENT '写操作开放程度：FORBIDDEN=只读(默认) | REQUIRE_APPROVAL=写需审批 | AUTO=写自动';

-- 存量行显式回填一次。DEFAULT 已覆盖新行，这条是为了让「全部存量都是只读」这件事
-- 在脚本里有据可查，而不是依赖列默认值的隐式行为。
UPDATE `connection` SET `write_policy` = 'FORBIDDEN' WHERE `write_policy` IS NULL OR `write_policy` = '';

-- 重复执行会报 Duplicate column name 'write_policy'，这是预期的，说明已经执行过。
