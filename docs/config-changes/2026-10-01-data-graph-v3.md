# 2026-10-01 数据星图 v3：给业务人员看的对象关系图

> 本目录的约定见 `2026-09-21-enable-connector-agent-plane.md` 开头：改了数据库结构或运行时配置就留一份记录，
> 只写键名、语义与回滚办法，绝不写任何真实密钥值。设计见 `docs/superpowers/specs/2026-09-30-enterprise-data-graph-design.md`。

## 一、数据库（生产由你执行，先于部署）

| 文件 | 内容 | 漏跑时的症状 |
|---|---|---|
| `db/migration/V20261001__data_graph_business_view.sql` | 新表 `connector_business_view`（对象的业务名、一句说明、业务领域；关系的角色名）、`connector_enrichment_state`（每个连接一行：补全链的认领、上次结果、输入指纹） | 应用照常 `Started`；数据星图接口 500（`Table ... doesn't exist`），补全链每次触发都失败 |

两张表都是租户隔离表，已登记进 `JimengTenantLineHandler.TENANT_AWARE_TABLES`（代码里，不需要配置）。
执行方式同其它 DDL：`docker exec -i <mysql容器> mysql --default-character-set=utf8mb4 -uroot -p... data-server < <文件>`。
`CREATE TABLE IF NOT EXISTS`，重复执行无害。

## 二、Nacos（`data-server.yml`）

**没有必须新增的键**。模型沿用 `connector.semantic.infer-model`。可选的键：

| 键 | 默认 | 说明 |
|---|---|---|
| `connector.semantic.enrichment.enabled` | `true` | 补全链总开关。关掉后推导成功照旧直接派发采样核对，定时对账不跑，星图照常用已有数据 |
| `connector.semantic.enrichment.reconcile-interval-ms` | `600000` | 定时对账间隔（`@Scheduled` 直接读占位符，改它要重启） |

## 三、权限

新增模块码 `DATA_GRAPH_MODULE`（显示名「数据星图」）。企业超管始终可用；普通成员要在 jm-admin 的角色授权里勾上「数据星图」
才能看到入口和数据。jm-admin 的授权弹窗按接口渲染，不用发版。

## 四、上线后会发生什么

- 部署后定时对账（首次在启动 5 分钟后）逐个给已有连接补跑补全链，每 10 分钟最多一个连接。每个连接大约调用模型 4–6 次，
  并对新补出的关系派发一轮采样核对（打客户库，走原有的速率预算）。
- 之后语义层每次生成完成（规则推导、增量补写、agent 生成定稿）都会自动接着跑一遍。

## 五、回滚

- data-service：把 `connector.semantic.enrichment.enabled` 设为 `false` 即可停掉补全链；回滚代码也行，两张新表是纯新增，留着不影响旧代码。
  关系发现写进语义层的 JOIN 行是普通的推断行（`detail_json.origin` = `FK` / `NAME_RULE` / `RELATION_PASS`），旧代码照常读。
- 前端：回滚代码即可。
