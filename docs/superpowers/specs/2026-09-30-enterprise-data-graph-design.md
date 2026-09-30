# 企业数据星图设计

日期：2026-09-30

关联仓库：`data-service`、`jm-agent-front`

## 1. 目标

为企业超级管理员提供一个只读的“企业数据星图”，用知识图谱式关系网络展示同一租户下多个数据库连接中的表、视图及其关联关系。

成功标准：

- 同一企业的多个数据库连接出现在一张可缩放、拖拽、搜索和筛选的关系图中。
- 库内关系复用现有 `connector_semantic` 的 `JOIN` 语义；跨库关系由 AI 根据结构、注释和已有语义推断。
- 每条跨库推测边均展示置信度、依据和“AI 推测”身份；不因低置信度而隐藏。
- 企业拥有大量连接和表时，页面仍能快速打开，并通过连续层级细节（LOD）保持“同一张图”的探索体验。
- 图生成失败不影响数据连接、语义层或 Agent；有旧版本时继续展示旧版本。

## 2. 非目标

- 不把星图作为 Agent 工具、Skill、提示词上下文或 RAG 数据源。
- 不提供新增、修改、确认或删除关系的编辑能力。
- 不引入 Neo4j 或其他独立图数据库。
- 不为生成展示图读取客户库中的真实业务行；只消费平台已经保存的结构快照和语义层。
- 不把 AI 推测关系升级成数据库事实，即使置信度为 100。

## 3. 权限与边界

- 后端所有星图接口调用 `SuperAdminGuard.requireSuperAdmin()`，与数据连接和语义工作台权限一致。
- 所有持久化表包含 `tenant_id`，加入 `JimengTenantLineHandler.TENANT_AWARE_TABLES`。
- 生成线程必须显式恢复触发任务所属的 `TenantContext`，不得在 `runAsSystem` 下读写图数据。
- API 不返回 `tenant_id`、连接凭据或任何真实业务取值。
- 前端入口标记 `superAdminOnly`；前端限制只用于体验，后端才是安全边界。

## 4. 总体架构

星图是一条独立的只读旁路：

```text
connection + connector_schema + connector_semantic(JOIN)
                         │
                         ▼
              企业星图异步生成器
       结构归一化 → 候选召回 → AI 判定 → 校验
                         │
                         ▼
       graph_snapshot + graph_node + graph_edge
                         │
                         ▼
          只读星图 API → 前端 Canvas 关系网络
```

生成批次先以 `PENDING/BUILDING` 写入，节点和边全部写完后才把批次切为 `READY`。读取接口只选择该租户最新的 `READY` 批次，因此发布是原子的。新批次失败时，最新成功批次仍可读取。

## 5. 持久化模型

### 5.1 `enterprise_graph_snapshot`

一行代表一个租户的一次生成批次，同时承担持久化队列和版本记录：

- `id`：雪花 ID。
- `tenant_id`：租户。
- `source_fingerprint`：输入结构、语义更新时间、模型和生成器版本的摘要。
- `status`：`PENDING | BUILDING | READY | FAILED`。
- `trigger`：`CONNECTOR_CHANGED | SCHEMA_REFRESHED | SEMANTIC_READY | SCHEDULED_RECONCILE | MANUAL_RETRY`。
- `node_count`、`edge_count`、`cross_edge_count`。
- `coverage_json`：纳入/跳过的连接与对象数、候选召回数量、截断原因。
- `model_code`、`prompt_version`、`builder_version`。
- `claim_at`、`completed_at`、`error_note`。
- 审计字段使用 `BaseEntity`。

唯一键为 `(tenant_id, source_fingerprint)`，相同输入不会重复排队。手动重试失败批次时将失败行重新置为 `PENDING`，不制造同指纹重复行。

### 5.2 `enterprise_graph_node`

- `snapshot_id`、`tenant_id`。
- `node_key`：稳定字符串键；数据库节点为 `connector:{connectorId}`，对象节点为 `object:{connectorId}:{objectType}:{objectName}` 的不可歧义编码。
- `node_type`：`DATABASE | OBJECT`。
- `connector_id`、`connector_name`、`connector_kind`、`connector_status`。
- `object_type`、`object_name`、`object_comment`。
- `semantic_gloss`：对应 `OBJECT` 语义的业务说明。
- `field_summary_json`：字段名称、类型、nullable、注释；用于只读详情，不把字段铺成图节点。
- `source_hash`、`importance_rank`。

唯一键为 `(snapshot_id, node_key)`。稳定身份不引用会在结构刷新后变化的 `connector_schema.id`。

### 5.3 `enterprise_graph_edge`

- `snapshot_id`、`tenant_id`、`edge_key`。
- `edge_type`：
  - `CONTAINS`：数据库包含对象。
  - `INTERNAL`：现有语义层的库内 `JOIN`。
  - `CROSS_DB_AI`：AI 推断的跨连接关系。
- `source_node_key`、`target_node_key`。
- `source_connector_id`、`target_connector_id`：用于按连接过滤和在连接删除时同步清理，避免解析字符串键。
- `source_field`、`target_field`。
- `direction`：`DIRECTED | UNDIRECTED`。
- `cardinality`：`1:1 | 1:N | N:1 | N:N | UNKNOWN`。
- `confidence`：0—100；库内边可为空，跨库边必填。
- `evidence`：可展示的一句话依据，限制 500 字。
- `verified`、`stale`：库内关系沿用现有结论；跨库 AI 边固定为未验证、非事实。
- `model_code`、`prompt_version`、`source_hash`。

唯一键为 `(snapshot_id, edge_key)`。边键用长度前缀或 JSON 数组编码后做摘要，禁止用点或下划线直接拼接表名字段名。

## 6. 节点和边的构造

### 6.1 节点

- 每条具备结构快照的数据库连接生成一个 `DATABASE` 节点。
- `connector_schema` 中 `TABLE`、`VIEW` 类型生成 `OBJECT` 节点；不认识的对象类型不强行当表处理，并记入覆盖说明。
- 字段不生成独立节点，避免字段数量将画布扩大一个数量级；字段在节点详情面板中展示。
- `OBJECT` 语义行提供业务说明；缺少语义时仍生成节点，只显示结构信息。

### 6.2 库内边

- `connector_semantic.scope=JOIN` 且两端对象仍存在时生成 `INTERNAL` 边。
- 搬运两端字段、基数、`verified`、`status=STALE`、`join_kind` 和 `care_reason`。
- `REJECTED` 关系不生成普通关系边；若产品需要展示风险，可在后续版本增加独立风险层，第一版不做。

### 6.3 跨库 AI 边

只比较不同 `connector_id` 的对象字段，不读取真实数据：

1. 归一化表名和字段名：拆分 snake_case、camelCase，统一 `id/code/no/number/key` 等常见标识词。
2. 建立倒排索引：类型族、字段词元、表业务词、字段/对象语义词。
3. 从共享词元和兼容类型桶中召回候选；每个字段最多保留配置化 Top-K，避免全量 N×N 比较。
4. 按批次把候选、两端结构和语义交给模型，要求严格 JSON 返回：`related`、方向、可能基数、0—100 置信度、简短依据。
5. 校验两端仍存在、类型兼容、枚举合法、文本长度合法，再去重入库。

只要模型明确返回 `related=true`，边就进入快照，不设置最低置信度门槛；界面允许按置信度区间筛选。`related=false` 不是关系，不生成边。

任何 `CROSS_DB_AI` 边永远使用虚线和“AI 推测”标识。模型、提示词和生成器版本进入 `source_fingerprint`，升级推断逻辑会自然产生新快照。

## 7. 生成、合并与恢复

### 7.1 触发点

- 连接创建、编辑、删除或状态改变成功后。
- 结构快照成功刷新后。
- 语义层生成成功发布后。
- 定时 reconciler 发现源指纹与最新 `READY` 不一致时。

触发操作只计算源指纹并插入 `PENDING`，不等待生成。连接和语义层的成功不能被星图故障回滚。

### 7.2 执行策略

- 使用独立的 `enterpriseGraphExecutor`，默认单并发、小队列，避免与聊天、Skill Builder 和语义层争用线程。
- worker 通过 CAS 把 `PENDING` 改为 `BUILDING`；超过配置时限仍在 `BUILDING` 的批次由 reconciler 标成 `FAILED` 并重新排队。
- 同租户在构建期间又发生变化时，新指纹形成新的 `PENDING`；旧批次可以完成，但最新指纹批次随后继续执行。
- 模型调用按候选批次切片；单片失败可重试，超过次数则整批失败，不发布半张图。
- 保留最近 3 个 `READY` 快照和 7 天内的失败记录；清理节点和边后再删快照。

### 7.3 删除的特殊处理

删除连接后不能等待异步重建才隐藏敏感元数据。连接删除事务提交后，必须立即物理删除所有快照中 `connector_id` 对应的节点，以及端点属于这些节点的边，然后再排队生成新快照。旧快照的计数可能短暂不准，但不会继续暴露已删除连接的表和字段。

## 8. 后端 API

新控制器路径：`/data/admin/enterprise-graph`，所有端点仅企业超级管理员可用。

- `GET /status`
  - 最新成功版本、当前生成状态、生成时间、节点/边数量、覆盖说明、失败警告。
- `GET /overview`
  - 返回数据库节点、连接间聚合边、每个连接的代表对象节点和布局种子；用于首屏秒开。
- `GET /objects?connectorIds=&cursor=&limit=`
  - 分页返回选中连接中的对象节点和相关边，用于缩放展开。
- `GET /neighborhood?nodeKey=&depth=1&limit=`
  - 返回某个对象的一跳或两跳邻域；达到上限时明确返回 `truncated=true`。
- `GET /search?q=&limit=`
  - 搜索连接名、表名、字段名、注释和业务说明，返回节点定位信息。
- `GET /nodes/{nodeKey}`
  - 节点详情和字段摘要。
- `GET /edges/{edgeKey}`
  - 关系两端字段、基数、置信度、依据、来源和生成版本。
- `POST /rebuild`
  - 手动重试/重建；只受理任务并立即返回。

所有 Snowflake ID 出网时转换为字符串。分页使用稳定 cursor，不使用大 offset。

## 9. 前端体验

### 9.1 入口与技术实现

- 新路由 `/console/data-graph`，导航名称“数据星图”，位于“数据连接”之后，`superAdminOnly=true`。
- 复用已经安装的 ECharts 6 `graph` 系列，采用 Canvas renderer，不新增图形库。
- 图页使用独立深色画布与现有控制台外壳共存；控件文字和焦点态满足对比度要求。

### 9.2 连续层级细节

页面始终表现为一张关系网络，而不是树形菜单：

- 缩放较远：数据库形成发光聚类，显示跨库关系束和少量代表表。
- 放大某个聚类：渐进加载该连接的表节点，以动画保持空间连续性。
- 点击表：聚焦上下游邻域并打开右侧详情，不替换成另一个页面。
- 超过前端预算的节点不进入当前 ECharts 实例；画布显示“当前渲染 N / 总计 M”，避免让用户误以为已加载全部。

默认渲染预算为 500 个节点；101—500 使用 Canvas 力导布局，超过预算必须先聚合或按邻域加载。布局结果按 `snapshotId + nodeKey` 缓存在浏览器，返回页面时避免节点重新乱跳。

### 9.3 视觉编码

- 数据库连接使用稳定的分类颜色；表/视图用形状或图标进一步区分，不能只靠颜色。
- `INTERNAL`：实线；`CROSS_DB_AI`：橙色虚线；`STALE`：红色点线并带文字警告。
- 节点大小表达关联度或 `importance_rank`，但设置上下限，避免核心节点吞掉画布。
- 关系 hover/click 显示两端字段；跨库边始终显示“AI 推测”和置信度。
- 支持搜索定位、连接筛选、边类型筛选、置信度范围筛选、仅看当前节点上下游和适应画布。

### 9.4 可访问性降级

关系图本身是高风险可视化，必须同时提供“关系列表”视图，支持键盘搜索、排序和查看详情。所有图按钮有可见文字或 `aria-label`；尊重 `prefers-reduced-motion`，关闭非必要的节点入场动画。

## 10. 状态与错误处理

- 从未成功生成：展示空状态和当前生成进度，不伪造空图。
- 正在生成且存在旧版本：继续展示旧图，顶部提示“正在生成新版”。
- 最新生成失败且存在旧版本：继续展示旧图，显示脱敏失败摘要和上次成功时间。
- 无结构快照：说明“暂无可展示的数据库结构”，并引导到数据连接刷新结构。
- 部分覆盖：顶部持续显示覆盖说明；“未比较到”不能表述成“确认无关系”。
- 接口后台刷新失败：保留当前缓存图并提供重试，不清空画布。
- 单个节点详情 JSON 损坏：只降级该节点详情，整图继续可用。

## 11. 配置

Nacos `data-server.yml` 新增：

- `enterprise-graph.enabled`：总开关，默认 `false`，DDL 和模型配置就绪后开启。
- `enterprise-graph.model-code`：跨库关系判定模型。
- `enterprise-graph.candidate-top-k`：每个字段候选上限，默认 10。
- `enterprise-graph.model-batch-size`：每次模型判定的候选数，默认 40。
- `enterprise-graph.model-timeout-seconds`：单批超时。
- `enterprise-graph.max-retries`：单批重试次数，默认 2。
- `enterprise-graph.claim-stale-minutes`：失心跳批次恢复阈值。
- `enterprise-graph.retained-ready-snapshots`：默认 3。

缺少模型配置时生成器失败关闭：接口仍能报告未配置，不能偷偷改用聊天默认模型。

## 12. 验证

后端测试：

- 节点稳定键、边稳定键和去重的属性测试，覆盖点、下划线、大小写和中文名称。
- 候选召回只产生跨连接、类型兼容的 Top-K 候选，且不会退化成 N×N。
- AI JSON 解析和枚举/长度/对象存在性校验。
- 相同指纹幂等排队、CAS 认领、失心跳恢复、旧版本回退和快照清理。
- 租户隔离、超级管理员限制、Snowflake ID 字符串化。
- 删除连接后同步清除旧快照中的节点和边。
- 大图 API 的 cursor、截断标记和最新 `READY` 选择。

前端验证：

- `npm run typecheck && npm run lint`。
- 使用合成数据验证 12 个连接、2,000 张表和高密度边时首屏只加载聚合数据，当前 Canvas 节点不超过预算。
- 搜索定位、缩放展开、邻域聚焦、筛选、详情、旧版本警告、失败降级和关系列表视图。
- 键盘焦点、文字/背景对比度与 reduced-motion。

## 13. 上线顺序

1. 手工执行新 DDL，并将三张表加入 schema 自检和发布清单。
2. 部署后端，保持 `enterprise-graph.enabled=false`，确认读接口和定时任务正常降级。
3. 配置专用模型和生成参数，开启一个测试租户，观察模型成本、候选覆盖和生成时间。
4. 部署前端入口；没有 `READY` 快照时展示明确空状态。
5. 扩大租户范围；监控批次失败率、平均候选数、模型调用量、节点/边数量和 API 延迟。

回滚时先关闭 `enterprise-graph.enabled`，前端隐藏入口；三张新表可保留，不影响连接器、语义层或 Agent。
