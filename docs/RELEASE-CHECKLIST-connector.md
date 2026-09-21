# 上线清单（本轮连接器/语义层改动）

> 本仓库 **push main 即部署到这台机器**，且**没有 Flyway**。
> 下面两件事漏做会直接 500 或静默失效，顺序不能反。

## ① 先跑 DDL，再部署新后端（必须）

```bash
# dev
docker exec -i dev-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 data-server \
  < modules/data-server/src/main/resources/db/migration/V20260920b__connector_semantic_coverage.sql
# 生产：容器名换成 ds-mysql，库名以生产为准
```

- **漏掉 `--default-character-set=utf8mb4`**：中文 COMMENT 会被双重编码成乱码，改不回去只能重 ALTER。
- **漏掉这一步就部署**：新 `Connection` 实体已映射 `semantic_coverage` / `semantic_gaps` 两列，
  MyBatis 生成的 SELECT 会带上它们 → 所有碰 `connection` 的接口 `Unknown column` → **500，管理面都打不开**。
- ALTER 不幂等，第二次报 `ERROR 1060 Duplicate column`，**这是预期的**。

### ★ 还有两份同样必须跑、但极易【整份】漏掉（2026-09-21 在 dev 上实测漏着）

`db/migration/` 既不是全集，也**没有任何机制校验它跑过没有**。这两份漏掉时应用照常
`Started`，只在定时任务里报错，所以很容易被当成"没事"：

```bash
# ① V20260917 整份漏跑过：缺 connector_semantic_generation /
#    connector_semantic_generation_table / connector_semantic_staged 三张表
#    + connector_schema.importance_rank 一列。
#    症状：SemanticGenerationReconciler 每轮 WARN「扫描语义层失心跳批次失败，留待下轮重试」，
#    底下藏着 Table ... doesn't exist。语义层生成从此静默不工作。
docker exec -i dev-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 data-server \
  < modules/data-server/src/main/resources/db/migration/V20260917__connector_semantic_generation.sql

# ② docs/ops-*.sql 不在 db/migration 目录下，一律需要单独执行。
#    product_feedback / product_feedback_image 漏跑的症状是 FeedbackImageCleanupJob
#    每小时抛一次 BadSqlGrammarException（@Scheduled fixedDelay=1h, initialDelay=10min）。
docker exec -i dev-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 data-server \
  < modules/data-server/docs/ops-20260614-product-feedback.sql
```

跑完用下面 ⑥ 的自检脚本确认，不要凭"应该跑过了"。

## ② Nacos 配置（不配则能力静默不可用）

```yaml
# 沙箱平面的 conn_* 连接器（B1）——默认关，不配就是现状
connector:
  agent:
    enabled: true
    callback-base-url: http://localhost:10011/data   # 生产写 http://localhost:20011/data
    min-sandbox-tools-version: 1

# ★ 沙箱边车的服务 token —— 连接器沙箱平面的【硬前提】：
#   conn_* 是随沙箱运行下发的，派发本身过不去，上面那三行配了也等于没配。
#   这个值必须与 jm-agent-sandbox 进程的 SANDBOX_SERVICE_TOKEN 逐字节相同：
#     · 边车侧为空  ⇒ 边车 503 拒绝服务（不是 401，别照着 401 去查鉴权）
#     · 两边不一致  ⇒ 每次派发 401，Agent 运行与技能评测全死
#   边车监听 0.0.0.0:8088、机器同时在局域网与 Tailscale 上，所以绝不能用
#   SANDBOX_ALLOW_NO_AUTH 绕过——那等于把任意代码执行开给任何够得到这个端口的人。
#   生产的 SANDBOX_SERVICE_TOKEN 来自 jm-agent-sandbox 仓库的 GitHub Actions secret
#   （部署时写进 launchd plist），本地自己生成一个：openssl rand -hex 32
agent:
  sandbox:
    service-token: <与边车逐字节相同的值>

# 对话 provider 的重试与 failover
ai:
  provider: deepseek
  chat-fallback-providers: [volcengine]
  retry:
    max-attempts: 3
    backoff-base-ms: 1000

# 沙箱粘性窗口（B12）——0 = 不限 = 与改造前逐字一致，要收窄再改
chat:
  sandbox-stickiness-turns: 0
```

`callback-base-url` **故意没有默认值**，空串 = 能力不可用。`:8088` 是 dev 与 prod 共用的单进程、
env 只有一份，给默认值会让 dev 平面派发的运行回调到生产网关，在另一套数据上读写**且不报错**。

**两条绝不能做**：回调前缀 `/data/internal/connector-agent/**` 不得进网关白名单（进了就不再注入
`user-id`/`X-Tenant-Id`，交叉核对必然失败），也不得进 `tenant.skip-paths`（进了 `TenantContext` 未设，
查询走 `__no_tenant__` 兜底，fail-closed 但极难排查）。

## ③ 上线顺序（三轮 CI，不能反）

1. **data-service 回调面**（纯加法，总开关默认关，行为零变化）
2. **jm-agent-sandbox**（工具 + 装配 + `connectorToolsVersion` 版本位；未下发 `connectorContext` 时行为逐字不变）
3. **data-service 派发侧 + 前端**

## ④ 还没做的验证（需要 Docker，本机 daemon 未运行）

按本工作区约定，涉及 DB/UI 的改动走**端到端实跑 + SQL 查库**。以下都只过了单测：

- **B1 沙箱平面 conn_***：传附件把会话钉进沙箱 → 下一轮问纯查库问题 → 验 SSE 里有
  `mcp__connector__conn_query`、答案带 `statement`/`row_count` 且与直接查库一致、
  `connector_audit` 有对应行、`docker exec <容器> env` 里**没有**回调 token 与数据库密码
- **护栏负向**：沙箱里跑一条不带 WHERE 的 UPDATE → `guard_blocked`、SSE status=error、库里一行未改
- **吊销**：run 中删掉 `agent_connection` → 下次 `conn_query` 回 `not_found`；
  run 结束后重放 token → 403
- **B4 删除入口**：管理台删一行 → `SELECT COUNT(*) FROM connector_semantic WHERE id=?`（**不加 deleted 条件**）应为 0
- **B21 eval**：`evals/evals.json` 9 条用例从未真跑过（要 docker + 真 LLM + 真库，会烧 token）

## ⑤ 已知局限（不要在对外文档里说过头）

**COLUMN_SET 锚点抓不住 B5 最可怕的那一种。** `fieldAnchor = sha256(名字:类型:可空:注释)`，
所以它能抓住「列被删 / 改名 / 改类型 / 改注释」，但抓不住**「列一个字节没动、含义被业务方悄悄复用」**
——只有在客户同时改了注释时才会被抓到。

**eval 用例 3/6/7 可能空转。** 它们的核心断言是条件式的（`truncated` / `guard_blocked` /
glossary 命中都要真实数据才触发），前提没触发时按通过计，只在 `evalFeedback.overall` 里留一句话说明。

**增量补写路径不写覆盖面两列。** 它只补指定几张表，算不出「本该覆盖多少」这个分母；
硬算出来的会是个假全集，比不写更坏。那两列继续描述上一次全量生成的结论。

## ⑥ schema 自检：别凭"应该跑过了"（2026-09-21 新增）

```bash
cd modules/data-server

# 1) 库里现有的表与列，【一次性】拉成文件
#    ★ 绝对不要在 while read 循环里用 `docker exec -i`：-i 会把循环的 stdin 吃掉，
#      第一条之后循环直接结束，于是给出「全部已应用」的假结论。这个坑实际发生过，
#      而它的方向恰好是最坏的那个方向——让人以为库是全的。
docker exec dev-mysql mysql -uroot -p123456 -N -B data-server \
  -e "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='data-server';" \
  | sort > /tmp/live-tables
docker exec dev-mysql mysql -uroot -p123456 -N -B data-server \
  -e "SELECT CONCAT(TABLE_NAME,'.',COLUMN_NAME) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='data-server';" \
  | sort > /tmp/live-cols

# 2) 所有 .sql 声明的表 —— 必须【含 docs/ops-*.sql】，只扫 db/migration 会漏
grep -rhioE 'CREATE TABLE( IF NOT EXISTS)? *`?[a-z_]+' --include='*.sql' . \
  | tr -d '`' | awk '{print tolower($NF)}' | sort -u | grep -v '^if$' > /tmp/want-tables

# 3) 比对。plugin / plugin_tool / plugin_credential / plugin_http_mapping / agent_plugin
#    这 5 张「缺失」是【预期的】：V20260912d 故意 DROP 掉了，而 docs/mysql-schema.sql
#    是插件下线前的全量导出，没跟着更新。
comm -23 /tmp/want-tables /tmp/live-tables | grep -vE '^(plugin|agent_plugin)'
# 无输出 = 表全了
```

列的比对同理：把各 `.sql` 里的 `ALTER TABLE x ... ADD COLUMN y` 抽成 `x.y` 与
`/tmp/live-cols` 比对。注意 `ops-20260607-agent-preset-questions-avatar.sql` 把 ALTER 包在
`PREPARE` 的字符串里、写的是 `` `data-server`.`agent` ``，粗暴正则会把库名当表名解析出一个
不存在的 `data` 表——那是解析假阳性，不是缺列。
