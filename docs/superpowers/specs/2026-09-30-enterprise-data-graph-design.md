# 数据星图设计（v2）

日期：2026-09-30

关联仓库：`data-service`、`jm-agent-front`

取代：本文件的上一版（`e6a4ecc`，快照 + 跨库 AI 推断方案）及其实施计划（`ff06785`）。上一版的实现在两个仓库的
未合并分支 `codex/enterprise-data-graph` 上，**不合并**，只按 §7 带入少量前端外观。

## 1. 目标与验收

给客户看：每个业务系统（一条数据连接）里有哪些表、表与表之间怎样关联。看的人按「客户的企业超管 / IT 登录控制台自己看」
设计（其他看法见 §11-4）。

验收全部用本地 `test` 租户现有数据逐条核对：

1. 画面上只有表与表之间的连线，没有「库包含表」之类的线。`erp_real` 画 7 条关系线（5 条多对一、2 条一对一），
   外加 `EPS_WBSELEMENT` 的「有上下级」标记；`test` 画 2 条多对一，外加 `D1_ACCOUNT` 的「有上下级」标记，
   另外 16 张表进「未发现关联的表」列表。
2. 把任意一条已画出的 JOIN 行改成 `verified=CONFIRMED` 后刷新页面，这条线变实线；把它的
   `detail_json.human_verdict` 改成 `UNRELATED` 后刷新，这条线消失。两者都不需要任何重建动作。
3. 27 张表里 23 张以中文名作为标题（画布卡片与右侧列表同一规则）；其余 4 张——`EPS_WBSELEMENT` 没有注释，
   两张 `D1_PROFITCENTER` 与 `EFI_VOUCHERDTL_EXT` 的注释是说明句而不是名称——以物理名作标题、注释作副标题。
4. 点任意一张表：控制台无报错，所有卡片位置不变，右侧出现该表的注释、字段和关系说明。
5. 同一份数据连续刷新 10 次，每张卡片的位置完全一致。
6. 星图页面的界面文案（表名、字段名、注释等客户数据除外）不出现：`READY` / `RUNNING` / `FAILED` 等枚举原文、
   `INSPECTOR`、`UNKNOWN`、置信度或百分比、「AI 推测」「AI 可信度」、语义层的说明 / 依据 / 核对原文。
   只排除界面文案，是因为客户数据本身可能含这些字母，例如本地表名 `EFI_CMS_ZP0FIAIF0017A` 里就有 `AI`。

## 2. 为什么推翻上一版

2026-09-30 用本地 `test` 租户超管实测上一版（2 个连接、27 张表），并对照代码：

| 现象 | 原因 |
|---|---|
| 48 条「关系」里 27 条是「库包含表」，关系列表第一页全是「包含」 | 每张表都生成一条 `CONTAINS` 边，而且排在最前 |
| 表只显示物理名，如 `EFI_CMS_ZP0FIAIF0017A` | 客户的表注释存在，但没上画布 |
| 点表节点：控制台报 `TypeError … reading 'getName'`，右侧详情不出现，整张图重排 | 鼠标松开即写位置 → 整图 `notMerge` 重建 → 力导布局重跑；节点详情请求从未发出 |
| 关系详情显示「基数 UNKNOWN / 可信度 55% / 已探查但判不出来……验证时先统计 VENDORID=0 的占比」 | 直接搬了语义层写给 Agent 的说明 |
| 画出 `SLOCK↔SLOCK`、`STATUS_FI↔STATUS_FI` 等语义层自己说「不构成关系」的线 | 只排除 `verified=REJECTED` |
| 页面满是 READY、INSPECTOR、「画布对象预算」、「AI 可信度」滑杆、「重新生成」 | 按运维视角设计 |
| 「跨库推测」恒为 0 | `candidateTopK` 默认 0，跨库推断默认不运行 |
| （读代码确认，未实测）采样核对、对话中确认关系、删除语义行之后，星图不会更新 | 快照只在推导发布、增量补写、结构刷新时重建；采样核对回写（`validate()`）、对话里业务方确认关系（`annotateJoin`）、管理员删除语义行都不触发 |

最后一条是结构性的：只要星图单独存一份副本，语义层每多一个修改入口就要多挂一个触发，漏一个就不一致。v2 不存副本。

## 3. 定位与非目标

**定位**：星图是语义层 JOIN 行加结构快照的**只读投影**。它不产生任何新结论，只做两件事：按 §5.2 的规则筛选关系，
把结构元数据翻译成客户看得懂的呈现（§5.4、§6）。

**非目标**（本版明确不做）：

- 跨连接（跨系统）的关系。
- 在星图里确认、否认、编辑关系。关系的确认只来自采样核对和对话中的业务方确认。
- 修改语义层。关系覆盖不足是本版的已知上限（§11-1）。
- 导出图片、分享链接、对企业超管以外的人开放。
- 展示任何模型写的文字（语义层的 gloss、依据、注意事项、置信度）。

## 4. 架构

```text
connection ────────────────┐
connector_schema ──────────┼─ 每次请求现算（纯函数投影，§5）─→ /data/admin/data-graph/* ─→ 前端画布（§6）
connector_semantic(JOIN) ──┘
```

- **不落库**：没有快照表、队列、定时任务、重建触发、模型调用、Nacos 配置项、DDL。
- **一致性**：语义层和结构快照的任何改动，下一次打开页面即生效。
- **规模**：每条连接的结构快照最多 200 个对象（`ConnectorSchemaService.MAX_OBJECTS`）。JOIN 行每个源列最多一条：
  唯一键 `uk_connector_semantic` 为 `(tenant_id, connector_id, scope, object_name, field_name, term)`，
  推导和人工写入的 JOIN 行 `term` 都是空串。
- **性能验收**：用合成数据造一条 200 张表、200 条 JOIN 行的连接，预热后连续请求 `GET /systems/{id}` 50 次，
  本地 P95 < 300 ms。达不到再加进程内缓存
  （键 = 租户 + 连接 + 该连接 `connector_schema.max(synced_at)` + `connector_semantic.max(update_time)`），不预先做。
- **租户**：三张源表都在 `JimengTenantLineHandler.TENANT_AWARE_TABLES` 里，请求在调用方租户下查询，不需要 `runAsSystem`。

## 5. 后端

### 5.1 接口

控制器 `DataGraphController`，`@RequestMapping("/data/admin/data-graph")`，每个方法先调
`superAdminGuard.requireSuperAdmin()`（与 `ConnectorAdminController` 一致）。控制器返回原始对象，由
`GlobalResponseHandler` 包装。所有 ID 以字符串出网；`spring.jackson.write_numbers_as_strings=true` 下计数也是字符串。

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/systems` | 系统列表 |
| GET | `/systems/{connectorId}` | 该系统的表卡片与关系 |
| GET | `/systems/{connectorId}/tables?name=` | 单表详情 |

连接不存在或不属于当前租户：`ServiceException(NOT_FOUND, "系统不存在")`；表不存在：`ServiceException(NOT_FOUND, "表不存在")`。

**SystemSummary**（`/systems`，按连接 id 升序；只列出结构快照里至少有一个 TABLE / VIEW 的连接）

| 字段 | 说明 |
|---|---|
| `connectorId` / `name` / `kind` / `status` | 连接基本信息 |
| `semanticStatus` | `READY` / `RUNNING` / `FAILED` / `null`（从未生成）。只给前端判断空状态，不上屏 |
| `tableCount` | 结构快照里 TABLE + VIEW 的数量 |

**SystemGraph**（`/systems/{connectorId}`）

| 字段 | 说明 |
|---|---|
| `connectorId` / `name` / `semanticStatus` | 同上 |
| `tables[]` | 全部 TABLE / VIEW，每项为 TableCard |
| `relations[]` | 通过 §5.2 的关系，不含自关联 |

TableCard：

| 字段 | 说明 |
|---|---|
| `name` | 物理表名 |
| `displayName` | §5.4；注释不像名称时为 `null` |
| `comment` | 去掉平台后缀的表注释全文；没有为 `null` |
| `objectType` | `TABLE` / `VIEW`（`BASE TABLE` 归一为 `TABLE`） |
| `related` | 是否至少有一条非自关联的关系 |
| `selfReferences[]` | 通过 §5.2 的自关联 `{fromColumn, toColumn}`，按 `fromColumn` 排序 |
| `keyColumns[]` | 主键列 `{name, comment}`；没有主键时取列数最少的唯一键（并列取名字最小）；都没有为空 |
| `relationColumns[]` | 本表作为起点的关系列 `{name, comment}`，按列名排序 |
| `fieldCount` | 字段总数 |

Relation：

| 字段 | 说明 |
|---|---|
| `id` | 稳定键：`fromTable`、`fromColumn`、`toTable`、`toColumn` 做长度前缀编码后取 SHA-256 的前 16 位十六进制 |
| `fromTable` / `fromColumn` | 起点（引用方） |
| `toTable` / `toColumn` | 终点（被引用方） |
| `cardinality` | `MANY_TO_ONE` / `ONE_TO_ONE` / `null`（§5.3） |
| `tier` | `CONFIRMED`（实线）/ `INFERRED`（虚线） |
| `confirmedBy` | `DATA`（采样核对通过）/ `BUSINESS`（业务方在对话中确认）/ `null` |
| `label` | 起点列的注释；为空或与列名相同（忽略大小写）时为 `null` |
| `discriminatorColumn` | 多态关系的判别列名，只出现在已确认的多态关系上。**从不返回判别值** |

**TableDetail**（`/systems/{connectorId}/tables?name=`）

| 字段 | 说明 |
|---|---|
| `name` / `displayName` / `comment` / `objectType` / `selfReferences` | 同 TableCard |
| `fields[]` | `{name, type, nullable, comment, key, inRelation}`；`key` 为 `PRIMARY` / `UNIQUE` / `null`；按快照原顺序 |
| `relations[]` | 以本表为起点或终点的全部 Relation |

**永不出网**：语义层的 `gloss`、`evidence`、`confidence`，`detail_json` 里的 `basis` / `note` / `care_reason` /
`verify_note` / `discriminator_value` / 任何取值与样本，锚点哈希，`tenant_id`，连接凭据。

### 5.2 关系判定

输入：该连接的 JOIN 行（`scope=JOIN`）。起点 = `object_name.field_name`，终点 = `detail_json.to_object.to_column`。
按顺序判定，先命中者为准：

| # | 条件 | 结果 |
|---|---|---|
| 1 | 起点表、起点列、终点表、终点列任一不在当前结构快照里 | 丢弃 |
| 2 | `status = STALE` | 丢弃 |
| 3 | `detail_json.human_verdict = UNRELATED` | 丢弃 |
| 4 | `detail_json.human_verdict = RELATED` | `CONFIRMED`，`confirmedBy=BUSINESS` |
| 5 | `verified = CONFIRMED` | `CONFIRMED`，`confirmedBy=DATA` |
| 6 | `verified ∈ {REJECTED, WEAK}` | 丢弃 |
| 7 | `detail_json.join_kind = POLYMORPHIC` | 丢弃：只在判别列取特定值时成立，未确认前画成无条件的线会误导 |
| 8 | 终点列单独构成终点表的一个唯一键（§5.3） | `INFERRED` |
| 9 | 其余 | 丢弃 |

判定只看上表列出的字段；`confidence`、`source`、`evidence` 不参与。

两条语义层既有约定，实现时不能读错：

- 业务方的任何一次确认（`annotateJoin` → `upsertHuman`）都会把 `status` 写成 `CONFIRMED`、`verified` 写成 `NONE`，
  否认只记在 `human_verdict=UNRELATED`。所以 **`status=CONFIRMED` 不代表关系成立**，只能按第 3、4 行判断。
- `verified` 来自采样包含率：≥ 0.9 为 `CONFIRMED`，0.5–0.9 为 `WEAK`，< 0.5 为 `REJECTED`
  （`SemanticJoinValidator.TH_CONFIRMED / TH_WEAK`）；`UNDECIDABLE` 是查了但判不出来，`NONE` 是没查。

自关联（起点表 = 终点表）通过判定后不进 `relations[]`，改记到该表的 `selfReferences[]`。

### 5.3 唯一键与基数

- 唯一键用 `SemanticJoinValidator.uniqueKeysByObject(rows)`：读 `detail_json.extra.unique_keys`（由
  `MySqlSession.describe` 写入）。表不在结果里表示「未知」（该功能上线前拉的旧快照），按「终点列不是唯一键」处理，
  下一次刷新结构后自动恢复。
- 「单独构成唯一键」指：该表存在一个唯一键，其列集合恰好是 `{终点列}`。终点列只是组合键的一部分不算。
- 基数：终点列单独构成唯一键时，若起点列也单独构成起点表的唯一键则为 `ONE_TO_ONE`，否则为 `MANY_TO_ONE`；
  终点列不构成唯一键（只可能出现在 `CONFIRMED` 关系上）则为 `null`，前端不画端点标记。

### 5.4 表名与注释

- `comment`：快照表注释去掉平台追加的「（约 N 行，InnoDB 估算值，不可当作准确计数）」后 trim，空则为 `null`。
- `displayName`：当且仅当 `comment` 像一个名称时等于 `comment`，否则为 `null`。「像名称」= 不超过 16 个字符，
  且不含 `，。；,;.` 和换行。本地 `D1_PROFITCENTER` 的「无公司代码字段，样本数据」和 `EFI_VOUCHERDTL_EXT` 的长说明句
  会因此落到 `null`。
- 字段注释：取快照 `fields[].comment` 并 trim，空则为 `null`。
- **估算行数后缀改为唯一来源。** 现状是写入方 `MySqlSession`（字符串拼接，约第 994 行）和剥离方
  `RuleContext.ROW_ESTIMATE_SUFFIX`（私有正则）各写一份，改一边另一边会静默失效。在 `ai/connector/model/` 新增
  `RowEstimateNote`，同时提供 `append(comment, rows)` 和 `strip(comment)`；`MySqlSession`、`RuleContext`、星图投影
  三处都改用它，并加往返测试 `strip(append(c, n)) == c`。

### 5.5 排序（保证输出确定）

- `tables[]`：`importance_rank` 升序（空值排最后），再按 `name`。
- `relations[]`：按 `fromTable`、`fromColumn`、`toTable`、`toColumn`。
- `fields[]`：快照原顺序。

### 5.6 代码位置

`modules/data-server` 下新建 `ai/connector/graph/`：

- `DataGraphController`：三个接口和超管校验。
- `DataGraphService`：按连接加载 `connection`、`connector_schema`（TABLE / VIEW）、`connector_semantic`
  （`scope=JOIN`），调用投影。
- `DataGraphProjector`：纯静态函数，输入三组行，输出 SystemGraph / TableDetail；不访问数据库、不依赖 Spring。
  §5.2–§5.5 的规则全部在这里。
- `DataGraphViews`：出网用的 record。

## 6. 前端

### 6.1 入口

- 路由 `/console/data-graph`（懒加载）。侧栏「数据星图」放在「数据连接」之后，`superAdminOnly: true`。导航项和图标从
  上一版分支带入（`workbenchNav.ts` 的 `data-graph` 项、`AtlasIcons.tsx` 的 `DataGraphIcon`）。
- 当前选中的系统记在 URL 查询参数 `?system=<connectorId>`，不用 localStorage。

### 6.2 页面结构（自上而下）

1. 页头卡片：标题「数据星图」，说明一句——「查看各业务系统里有哪些表、表与表之间怎样关联。关系来自数据连接的语义层，
   语义层更新后这里自动同步。」
2. 系统切换（多于一个系统时显示）：每项为「连接名 · N 张表」；连接 `status=DISABLED` 时加「已停用」标签。
3. 概览行：表 N ｜ 已确认关系 a ｜ 推断关系 b ｜ 未发现关联的表 c。
4. 主区：左侧深色画布（§6.3），右侧面板（§6.4）。画布上方有搜索框（在当前系统内按中文名或物理名搜，选中即居中并选中
   该表）和「适应画布」按钮；画布左下角是图例——实线「已确认：数据核对通过或业务方确认」，虚线「推断：按表结构，尚未核对」。

### 6.3 画布

- 依赖：新增 `@xyflow/react`（React Flow 12）和 `@dagrejs/dagre`。换掉 ECharts 的原因：ECharts graph 的节点只能是
  符号加文字，连线只能连到节点中心，做不出「表卡片 + 连线接在具体字段行上」。
- 只放 `related=true` 的表。
- 表卡片：
  - 标题 = `displayName ?? name`。
  - 副标题：有 `displayName` 时为物理名（等宽小字）；否则为 `comment`（单行截断，悬停看全文）。
  - 行 = `keyColumns` + `relationColumns`，每行「列名 + 注释」，主键行带钥匙标记。
  - 有 `selfReferences` 时，标题旁加「有上下级」徽标；底部写「共 N 个字段」。
- 连线：从起点列那一行连到终点列那一行。`CONFIRMED` 画实线，`INFERRED` 画虚线；端点标 `N` / `1`（`ONE_TO_ONE`
  两端都标 `1`，`cardinality=null` 不标）；`label` 非空时显示在线的中段。
- 布局：dagre，`rankdir=LR`（引用方在左，被引用的主数据在右），卡片尺寸按行数计算；节点和边按 §5.5 的顺序喂入，
  同一份数据得到同一组坐标。卡片不可拖拽（`nodesDraggable=false`），可以平移、缩放。
- 交互：点卡片即选中——该表和直接相连的表、线保持原样，其余降到约 25% 不透明度；右侧面板显示表详情；坐标不变。
  点空白处取消选中。卡片可以用 Tab 聚焦、Enter 选中。
- 尊重 `prefers-reduced-motion`：关闭视口过渡动画。

### 6.4 右侧面板

- 未选中表时有两个页签：
  - 「关系清单」：全部关系的句子，按起点表排序；点击即在画布上定位并选中起点表。
  - 「未发现关联的表」：`related=false` 的表；点击后面板直接显示该表详情（它不在画布上）。
- 选中表时依次显示：标题和副标题（同卡片）；表注释全文；「关系」小节（句子 + 实线 / 虚线标记 + 来源说明
  「数据核对通过」/「业务方确认」/「按表结构推断，尚未核对」）；「字段」小节（名称、类型、注释、主键 / 唯一键标记，
  参与关系的字段高亮；字段多于 30 个时显示搜索框）。
- 句子模板（`{A}`、`{B}` 取 `displayName ?? name`）：
  - `MANY_TO_ONE`：每条「{A}」对应一条「{B}」（{起点列} → {终点列}）
  - `ONE_TO_ONE`：「{A}」与「{B}」一一对应（{起点列} → {终点列}）
  - `cardinality=null`：「{A}」的 {起点列} 关联「{B}」的 {终点列}
  - 自关联：「{A}」内部有上下级（{起点列} → {终点列}）
  - 已确认的多态关系：在句末加「，按 {判别列} 区分类型」

### 6.5 状态

| 情况 | 显示 |
|---|---|
| 没有任何系统 | 「还没有可以展示的业务系统」+ 跳转「数据连接」 |
| `semanticStatus=null` | 「这个系统的表关系还没整理。在『数据连接』里生成语义层后会自动出现。」 |
| `RUNNING` 且没有关系 | 「正在整理表关系，完成后刷新页面即可看到。」 |
| `FAILED` 且没有关系 | 「表关系整理没有成功，可在『数据连接』查看原因。」 |
| `READY` 且没有关系 | 「暂未发现可以确认的表关系。」右侧面板照常列出所有表 |
| 有关系且 `RUNNING` | 正常显示；概览行下方一行小字「语义层正在更新，完成后刷新页面可看到最新关系」 |
| 接口失败 | antd `Alert` + 重试 |

文案里的「数据连接」链接到该连接的语义层页面 `/console/connectors/{id}/semantic`（已有）。

### 6.6 视觉

沿用上一版分支 `jm-agent-front@codex/enterprise-data-graph` 中 `src/pages/console/data-graph/data-graph.css` 的配色和
质感：浅色页头卡片和统计卡片，深色画布和深色右侧面板，青色描边与发光。节点改成卡片后按同一套色值重做；实线用青色，
虚线用同色降饱和；选中卡片加发光。文字与背景的对比度满足 WCAG AA。

## 7. 删除 / 不带入

v2 从两个仓库的 `main` 新开分支实现，上一版分支不合并。

- **不带入**：`enterprise_graph_*` 三张表及 `V20260930__enterprise_data_graph.sql`、`connector.enterprise-graph.*` 配置、
  快照 / 队列 / 恢复 / 来源指纹 / 跨库候选召回 / AI 判定、四处重建触发、删除连接时的同步清理、ECharts 画布和 LOD
  合并逻辑、置信度和关系类型筛选、「重新生成」按钮。
- **带入**：导航项、图标、页面样式的视觉部分（§6.1、§6.6）。
- `main` 上的上一版实施计划 `docs/superpowers/plans/2026-09-30-enterprise-data-graph.md` 在 v2 分支删除，由 v2 计划取代。
- 上一版 DDL 若在任何环境执行过，那三张表可以删除，v2 代码不读写它们（本地 `dev-mysql` 里有）。

## 8. 测试

后端（`modules/data-server`，JUnit 5）：

- `DataGraphProjectorTest`：
  - §5.2 每一行至少一个用例；优先级：`UNRELATED` 压过 `verified=CONFIRMED`，`RELATED` 压过 `REJECTED`。
  - 唯一键三态（缺失 / 空列表 / 存在），以及终点列只是组合键一部分时不算唯一键。
  - 两种基数和 `null`；自关联进 `selfReferences`、不进 `relations`；`BASE TABLE` 归一。
  - `displayName` 规则（长度、标点、空值）；输出顺序确定。
  - 把输出序列化后断言不含 `gloss`、`confidence`、`basis`、`note`、`care_reason`、`verify_note`、`discriminator_value` 等键。
  - 夹具一律手工构造，覆盖本地数据里出现过的每种形态；**本地真实数据只用于 §1 的手工验收，不进仓库**。
- `RowEstimateNote`：往返测试；`MySqlSession`、`RuleContext` 的既有测试照常通过。
- `DataGraphController`：非超管调用被拒。

前端：

- `npm run typecheck && npm run lint`。
- Playwright 夹具脚本（沿用上一版 `e2e/data-graph-check.mjs` 的写法：自起临时 Vite，拦截接口返回夹具）：
  - 加载后、逐个点击卡片后，控制台都没有报错；
  - 连续加载 3 次，卡片坐标一致；点击前后坐标一致；
  - 页面主体文本不含 §1-6 列出的字样（夹具数据本身不含这些字样，所以可以整页检查）；图例存在；
  - §6.5 的每种状态各渲染一次；
  - 200 张表、200 条关系的夹具能渲染、能适应画布，并记录首屏耗时。
- 手工：§1 全部验收项，在本地全栈上逐条核对。

## 9. 上线

- 先部署 `data-service`，再部署 `jm-agent-front`；两个仓库串行 push（共用一台 runner）。
- 无 DDL、无 Nacos 变更。
- 回滚：前端撤掉路由和导航项即可；后端接口只读，没有副作用。

## 10. 待验证项（实现中用数据决定）

1. 一个系统有 200 张表、200 条关系时，分层图是否还读得清。读不清再定「默认只展开关联最多的前 N 张表，其余靠搜索展开」，
   N 用合成数据试出来。
2. `GET /systems/{id}` 的 P95 是否达标（§4），据此决定要不要加缓存。

## 11. 已知限制

1. **关系覆盖取决于语义层，这是本版有没有用的上限。** 本地 `test` 连接 19 张表只有 11 条 JOIN 行：采购订单头与明细、
   销售订单头与明细、订单到供应商 / 客户等核心关系都没有。该连接的生成记录显示说明书是分批补写的，最后一批「关系 0」，
   并且「模型输出疑似被 max_tokens 截断，说明书不完整」。星图如实展示后，`test` 只有 2 条线。语义层的关系推导需要单独
   排查（不同批次之间是否不找关系、输出截断），另立项；Agent 写 SQL 时缺的也是这些关系。
2. 唯一键未知（`extra.unique_keys` 上线前拉的旧快照）的表，指向它的推断关系不显示，直到下一次刷新结构。
3. 靠业务编码等非唯一列关联的关系，只有采样核对通过或业务方确认后才显示；数据稀疏的库会缺。本地共 4 条属于这种情况
   （如 `ACKMJE_CX2026.F_KMBH → D1_ACCOUNT.CODE`）。
4. 本版按「客户的企业超管 / IT 自己看」设计。如果主要给业务负责人看，或者由我方投屏 / 发给客户，还需要：语义层为没有
   注释或注释不像名称的表产出简短的业务名；导出图片。
