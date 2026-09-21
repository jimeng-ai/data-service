# 2026-09-21 开启沙箱平面的连接器（conn_*）+ 补三份漏跑的 DDL

> **本目录的约定**：凡是改了**数据库结构**或 **Nacos 运行时配置**，就在这里留一份日期命名的
> 变更记录。因为这两样东西**都不在代码里**——DDL 没有 Flyway、Nacos 配置存在容器卷里，
> 只有 commit 历史是不够的，看代码永远看不出「线上那台机器现在到底配成什么样」。
>
> **只写键名、语义与回滚办法，绝不写任何真实密钥值。**

## 一、数据库（已在 dev 执行；生产未执行）

本次在 dev（`dev-mysql`，库 `data-server`）执行了三份 DDL。**都是仓库里已有的文件，没有新增
任何自定义结构**，只是它们此前从未被执行过：

| 文件 | 内容 | 漏跑时的症状 |
|---|---|---|
| `db/migration/V20260920b__connector_semantic_coverage.sql` | `connection` 加 `semantic_coverage` / `semantic_gaps` 两列 | **所有碰 `connection` 的接口 500**（新 `Connection` 实体已映射这两列，MyBatis 的 SELECT 会带上它们）→ 管理面都打不开 |
| `db/migration/V20260917__connector_semantic_generation.sql` | 3 张表 + `connector_schema.importance_rank` | 应用照常 `Started`，只有 `SemanticGenerationReconciler` 每轮 WARN「扫描语义层失心跳批次失败」→ **语义层生成静默不工作** |
| `docs/ops-20260614-product-feedback.sql` | `product_feedback` / `product_feedback_image` | `FeedbackImageCleanupJob` 每小时一次 `BadSqlGrammarException`（`fixedDelay=1h, initialDelay=10min`） |

执行方式与自检脚本见 `docs/RELEASE-CHECKLIST-connector.md` 的 ① 与 ⑥。两点必守：

- 一律带 `--default-character-set=utf8mb4`，否则中文 COMMENT 双重编码且改不回去；
- ALTER 不幂等，重复执行报 `ERROR 1060 Duplicate column`，**这是预期的**。

**生产（`ds-mysql`）这三份是否执行过，本记录不作断言。** 上线前按 ⑥ 的脚本自查。

## 二、Nacos（`data-server.yml`，namespace `fe9e39ae-06af-49c3-9c5b-6060df2cf93e`）

### 新增 1：开启沙箱平面的连接器工具代理

```yaml
connector:
  agent:
    enabled: true                                   # 原默认 false
    callback-base-url: http://localhost:10011/data  # dev；生产写 http://localhost:20011/data
    min-sandbox-tools-version: 1
```

**为什么要开**：`ChatRunService.decideExec()` 会把「本轮带附件 / 本会话此前传过文件 /
Agent 绑了 DOER 技能」这三种整轮路由到沙箱平面，而沙箱平面在此之前**一个 `conn_*` 都没有**。
后果不是报错：模型手里没有工具，就拿历史数据讲或者自己编，SSE 与 trace 里也看不出任何异常。
叠加 `chat.sandbox-stickiness-turns` 默认 `0`（= 不限 = 整条会话永久粘在沙箱），用户的体感是
「这个会话传过一次附件之后，Agent 突然不会查数据库了」。

**`callback-base-url` 故意没有默认值，空 = 能力不可用，绝不回落。** `:8088` 是 dev 与 prod
**共用的单进程**、env 只有一份，给默认值会让 dev 派发的运行回调到**生产网关**，在另一套数据上
读写**且一个字都不报错**。

### 新增 2：沙箱边车的服务 token

```yaml
agent:
  sandbox:
    service-token: <与边车进程的 SANDBOX_SERVICE_TOKEN 逐字节相同>
```

这是连接器沙箱平面的**硬前提**：`conn_*` 随沙箱运行下发，派发本身过不去时，上面那三行配了
也等于没配。两侧不一致的症状极易误诊：

- 边车侧为空 ⇒ 边车 **503** 拒绝服务（不是 401，别照着 401 去查鉴权）
- 两边不一致 ⇒ 每次派发 **401**，Agent 运行与技能评测全死

生产的值来自 `jm-agent-sandbox` 仓库的 GitHub Actions secret（部署时写入 launchd plist）；
本地自己生成 `openssl rand -hex 32`，放在 `jm-agent-sandbox/.env`（已 gitignore），由
`npm start` 的 `--env-file-if-exists=.env` 自动加载。

### 两条绝不能做（改了上面的键之后尤其要复查）

1. 回调前缀 `/data/internal/connector-agent/**` **不得进** `gateway.yml` 的 `ignore.auth.whitesUrl`
   ——进了网关就不再注入 `user-id` / `X-Tenant-Id`，过滤器的 claims↔头交叉核对必然失败（全部 403）；
   若同时放宽核对，就是裸奔。
2. **也不得进** `tenant.skip-paths`——进了 `TenantContext` 未设，查询走 `__no_tenant__` 兜底查不到
   任何行，fail-closed 但排查极其费劲。

自查（应分别为 401 与 0）：

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' -d '{}' \
  http://localhost:10011/data/internal/connector-agent/tool      # 期望 401
grep -c 'connector-agent' <(curl -s "http://<nacos>/nacos/v1/cs/configs?dataId=gateway.yml&group=DEFAULT_GROUP&tenant=<ns>")  # 期望 0
```

## 三、生效方式与回滚

`ConnectorProperties` 是 `@ConfigurationProperties` 但**没有** `@RefreshScope`，热刷新不会重新
绑定——**改完必须重启 data-server**。

回滚：把 `connector.agent.enabled` 改回 `false` 即可，其余键留着无害（`enabled=false` 时整段
不下发，行为与改造前逐字一致）。DDL 不回滚：那两列/三张表对旧代码是多余列，无影响。

改 Nacos 的安全姿势（这次就是这么做的）：

```
GET 原文备份 → 本地插入 → diff 确认删除行数为 0 → yaml.safe_load 验可解析
→ POST 发布 → 回读逐字节比对（发布后有 1~2 秒最终一致延迟，第一次读到旧值是正常的）
```
