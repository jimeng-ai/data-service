# S0：删除高德和 HTTP 类连接（一切皆可 skills）

- 依据：工作区 `docs/superpowers/specs/2026-10-05-everything-is-skills-design.md` §7.1。
- 涉及仓库：data-service、jm-agent-sandbox、jm-agent-front。合入 main 后，各自会自动打 tag，到时把版本号补在这里。
- 前提：用户 2026-10-05 确认，没有人在用高德，也没有人在用 HTTP 类连接。

## 一、上线前：生产库（ds-mysql）

1. 先只读核对：

```sql
SELECT id, tenant_id, name, kind, status, deleted FROM connection WHERE kind = 'HTTP';
SELECT ac.id, ac.agent_id, ac.connection_id
FROM agent_connection ac JOIN connection c ON c.id = ac.connection_id
WHERE c.kind = 'HTTP';
```

预期两条查询都接近为空。如果查出了行，先看是谁建的，再决定要不要删。

2. 删除。顺序是先删授权，再删连接，和 `ConnectorService.delete` 一致：

```sql
DELETE ac FROM agent_connection ac JOIN connection c ON c.id = ac.connection_id WHERE c.kind = 'HTTP';
DELETE FROM connection WHERE kind = 'HTTP';
```

不删也不影响其他功能，新代码对这些遗留行做了容错：不探测、不能再授权，模型调 `conn_list` 时看不到它们，只授了遗留行的 Agent 也拿不到 `conn_*` 工具。但「数据连接」页上会一直挂着这些用不了的连接，对它们点「测试连接」会报错，所以还是建议删掉。

## 二、上线顺序

一次上一个仓库。S0 本身对先后没有硬要求：三个仓库的新旧版本怎么搭配都能用（S0 整体评审逐个组合核过），唯一看得见的影响是下面这条。

合并后 main 上的新 tag 会把还没上线的 v1.1.0 和 P0 一起带上。如果一起上，按 `docs/releases/v1.1.0.md` 第三节的顺序走：沙箱 → data-service → 前端。

data-service 上线之后、前端上线之前，「HTTP 出站（兼容）」页面会因为接口已删而报错。这个页面只有超管能看到，线上也没人在用。

## 三、上线并跑稳之后：Nacos `data-server.yml`

删掉下面这些键，新版本已经不读了：

- `GaoDe.api-key`
- `GaoDe.base-Url`
- `okhttp.plugin.read-timeout`（有就删）
- `connector.invoke.*`（有就删）

**回退注意**：S0 之前的 data-server 没有 `GaoDe.*` 起不来。所以回退到旧 tag 之前，要先把这两个键加回去。这也是这一步要等新版本跑稳再做的原因。

## 四、可选：删两张字典表（由你决定）

新代码已经不再读写 `poi_category_dict` 和 `adcode_citycode_dict`。想留底的话，先用 mysqldump 导出这两张表，再执行：

```sql
DROP TABLE poi_category_dict;
DROP TABLE adcode_citycode_dict;
```

## 五、上线后验证

- data-server 启动日志里有「连接器注册表就绪，共 1 种类型: [MYSQL]」。
- 「数据连接」页新建时，类型下拉框里只有 MySQL；侧栏里没有「HTTP 出站（兼容）」。
- 带登录态请求 `POST /data/gaode/get-poi-by-keyword`，返回 404。
- 授权了数据库连接的 Agent 照常能查库，`conn_list`、`conn_query` 都正常。
