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

## ② Nacos 配置（不配则能力静默不可用）

```yaml
# 沙箱平面的 conn_* 连接器（B1）——默认关，不配就是现状
connector:
  agent:
    enabled: true
    callback-base-url: http://localhost:10011/data   # 生产写 http://localhost:20011/data
    min-sandbox-tools-version: 1

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
