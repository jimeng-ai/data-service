-- 把 `connection` 演化成【连接器实例表】：加 7 列，不新建平行表。
--
-- ============================================================================
-- 一、本项目没有 Flyway，这个脚本不会自动执行
-- ============================================================================
-- db/migration/ 只是一个约定目录，仓库里没有任何 Flyway / Liquibase 依赖。
-- 必须【手工】在 data-server 库执行，而且【必须先于部署新后端】：
-- 新版 Connection 实体已经映射了下面这 7 列，列不存在时 MyBatis 生成的 SELECT 会带上它们，
-- 结果是所有碰 connection 的接口一律 Unknown column → 500。连管理面都打不开。
--
-- 上线顺序是三段，不是两段：  加列 → 部署新后端 → 跑回填（本脚本末尾那条 UPDATE 可以并进第一段）。
--
-- ============================================================================
-- 二、怎么执行
-- ============================================================================
--   docker exec -i ds-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 data-server < 本文件
--
-- `--default-character-set=utf8mb4` 不是可选项：漏了它，客户端按 latin1 握手，
-- 上面这些中文 COMMENT 会被【双重编码】写进表定义，之后 SHOW CREATE TABLE 出来全是乱码，
-- 而且改不回去只能重新 ALTER 一遍。
--
-- ============================================================================
-- 三、为什么是演化 connection，不是新建一张 connector_instance
-- ============================================================================
-- 新建平行表看着干净，代价在横切层：租户隔离、凭据加解密、Agent 授权（agent_connection）、
-- 限流、审计、健康探测——这些「只写一遍」的东西会因为有两张注册表而写两遍，
-- 然后在某次只改了一边的修复里静默分叉。两套外部系统注册表并存，还要回答
-- 「同名的 HTTP 连接到底指哪张表里那条」。
--
-- 演化的代价则是可控的：HTTP 类型【继续用旧列】（base_url / auth_scheme / allow_methods /
-- allow_paths），沙箱 egress 代理那条链路一个字节都不用改；新类型（MYSQL 起）的参数进
-- config_json。旧列对新类型为空、新列对旧行为默认值，两边都不打架。
--
-- ============================================================================
-- 幂等性
-- ============================================================================
-- MySQL 不支持 ADD COLUMN IF NOT EXISTS。重复执行会报
--   ERROR 1060 (42S21): Duplicate column name 'kind'
-- 【这是预期结果，说明本脚本已经执行过】，不要试图去改表结构来"修"它。
-- 末尾的 UPDATE 回填是幂等的，可以随便重跑。

ALTER TABLE `connection`
  ADD COLUMN `kind` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
      NOT NULL DEFAULT 'HTTP'
      COMMENT '连接器类型：HTTP | MYSQL。默认 HTTP 是为了让存量行与新建的 HTTP 连接都不用显式填'
      AFTER `display_name`,

  ADD COLUMN `config_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
      DEFAULT NULL
      COMMENT '非敏感参数 JSON，按该类型的参数定义校验后存入。敏感参数一律走 credential_cipher，绝不许落在这里'
      AFTER `status`,

  ADD COLUMN `capability_flags` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
      DEFAULT NULL
      COMMENT '接入时探测出的【实际可用】能力，逗号分隔（QUERY,DESCRIBE,INVOKE,HEALTH）。不是类型静态声明：客户账号读不了元数据时 DESCRIBE 要降级，不回填就是一次静默降级'
      AFTER `config_json`,

  ADD COLUMN `health_state` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
      DEFAULT NULL
      COMMENT '健康态：UNKNOWN | HEALTHY | UNHEALTHY。本项目没有 actuator，没有 HealthIndicator 可挂，健康态只能存在行里'
      AFTER `capability_flags`,

  ADD COLUMN `health_checked_at` datetime
      DEFAULT NULL
      COMMENT '最近一次健康探测时间。为空 = 从没探过，界面不要显示成"健康"'
      AFTER `health_state`,

  ADD COLUMN `health_reason` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
      DEFAULT NULL
      COMMENT '探测失败原因。只放已归类的安全文案，绝不放原始异常——JDBC/HTTP 异常常带主机名与连接参数，而这列会随管理面响应出网'
      AFTER `health_checked_at`,

  ADD COLUMN `readonly_verified_at` datetime
      DEFAULT NULL
      COMMENT '只读验证通过的时间：试一个无害的写操作、期望它失败。为空 = 没验过，不等于"验过是可写的"'
      AFTER `health_reason`;

-- 存量行回填。DEFAULT 'HTTP' 已经覆盖了新行与 ALTER 时的存量行，这条是显式兜底：
-- 万一有人先手工加过一列没带 DEFAULT，或者从别的环境导过数据，这里能补上。幂等，可重复执行。
UPDATE `connection` SET `kind` = 'HTTP' WHERE `kind` IS NULL OR `kind` = '';

-- 刻意【不】给 kind 加索引：单租户的连接数是个位数到两位数，管理面列表的主路径是
-- uk_connection_tenant_name 已经覆盖的 tenant_id 前缀扫描，再加一个低基数索引只是白占写入成本。
