# 运营平台 · 模型管理 设计文档

- 日期：2026-06-16
- 状态：设计已确认，待写实现计划
- 涉及仓库：`data-service`（后端，分支 `feat/agent-builder`，worktree `data-service-agent-builder`）、`jm-operator`（运营前端）
- 落地路径：A（一份 spec / 一份计划，①目录 + ②按模型路由 + ③计费 + 运营页 一次性做完）

## 1. 背景与目标

### 现状的痛点
一个「可选模型」当前被拆在三处，加一个模型要跨配置中心 + 改代码 + 重启：

| 维度 | 当前位置 | 谁消费 |
|---|---|---|
| ① 目录（下拉里的 value/label/描述/启用/maxTemp） | Nacos `ai.model-catalog.models`（`ModelCatalogProperties` 绑定） | 调试台 / 构建器模型下拉、`draft_agent` 校验 |
| ② 计费单价 | `ModelPricing.java` 硬编码前缀表（自带 TODO：迁配置中心） | 花费统计 `ai_model_call_log.cost_usd` |
| ③ 上游路由（实际打哪个 provider / 上游模型名 / key） | Nacos `ai.provider` 全局开关 + `providers.<name>.chat.model` | 真实对话调用 |

### 目标
在运营平台（jm-operator）做一个「模型管理」页，让运营在一个页面上**完整定义一个模型**：展示信息 + 单价 + 实际路由到哪个上游、上游叫什么名字。加模型不再改代码、不再手改 Nacos 目录。

### 非目标（本次不做）
- provider 连接（base-url / api-key / 协议）的页面化管理。**连接继续留在 Nacos `providers.*`**，模型行只「引用连接名」。密钥不落 DB、不上 UI（安全考量）。
- embedding / rerank / 上下文化 的按模型路由。这三类不是 Agent 可选项，继续绑全局 `ai.provider`。
- 模型的「按租户启用」差异化。模型目录是平台级、全租户共享。

## 2. 关键约束（来自现有代码）

1. **chat 调用缝**：`requestBody.model` 承载模型名，调用点只有两处——`ClaudeService`（anthropic 协议路径）与 `OpenAiService`（openai 协议路径），各自 `providerRegistry.chat()` 取**全局唯一** active provider 的 client。按模型解析 provider 必须拦在这两处调 chat 之前。
2. **协议是两个独立维度**：目录里的 `provider: anthropic|openai` 实为**协议形态**（决定走 ClaudeService 还是 OpenAiService、用哪个 `AiProtocolAdapter`），与「上游连接是谁」正交。因此一个模型行的 **协议必须 == 其绑定连接的协议**，否则请求体形状与上游不匹配。本设计强约束这一点。
3. **计费按字符串算**：`ModelPricing.compute(requestBody.model, usage)`。计价必须发生在「改写成 upstream_model 之前」，并以**逻辑 value** 为口径；调用日志 `model` 列继续存逻辑 value，保证花费统计口径不变。
4. **provider client bean 注册**：`ProviderRegistry` 启动 `@PostConstruct` 仅校验全局 active provider 配齐 4 类 client。改为按模型路由后，**每个 enabled 模型引用的连接都必须有对应协议的 chat client bean + Nacos 连接块**，启动时 fail-fast。
5. **Nacos 配置自动刷新**：`@ConfigurationProperties` 在 Nacos 变更时自动 rebind（本项目已验证）。DB 化后，目录/单价的「热更新」靠 `ModelRegistry` 进程内缓存 + 写操作后主动刷新实现。

## 3. 数据模型

### 3.1 连接（不变）
留 Nacos `providers.*`。新增一个只读运营接口给页面下拉用：

```
GET /admin/operator/model-providers
→ [{ "name": "302ai", "protocol": "anthropic" }, { "name": "openrouter", "protocol": "anthropic" }, ...]
```
数据来源：`AiProviderProperties.getProviders()` 的 key + 各自 `chat.protocol`。

### 3.2 新表 `ai_model`（平台级，**不进** `TENANT_AWARE_TABLES`）

```sql
CREATE TABLE ai_model (
  id                BIGINT       NOT NULL COMMENT '雪花ID',
  value             VARCHAR(128) NOT NULL COMMENT '逻辑模型id（下拉 value、Agent 存的就是它）',
  label             VARCHAR(128) NOT NULL COMMENT '展示名',
  description        VARCHAR(512)          COMMENT '选型提示',
  protocol          VARCHAR(32)  NOT NULL COMMENT 'anthropic | openai（须与连接协议一致）',
  provider          VARCHAR(64)  NOT NULL COMMENT '引用 Nacos providers.* 的连接名，如 302ai',
  upstream_model    VARCHAR(128) NOT NULL COMMENT '真正下发给上游的模型名',
  max_temp          DECIMAL(3,2) NOT NULL DEFAULT 1.00 COMMENT 'temperature 上限：anthropic=1 openai=2',
  price_input       DECIMAL(12,4) NOT NULL DEFAULT 0 COMMENT 'USD/百万token 输入价',
  price_output      DECIMAL(12,4) NOT NULL DEFAULT 0 COMMENT 'USD/百万token 输出价',
  price_cache_read  DECIMAL(12,4) NOT NULL DEFAULT 0 COMMENT 'USD/百万token 缓存读价',
  price_cache_write DECIMAL(12,4) NOT NULL DEFAULT 0 COMMENT 'USD/百万token 缓存写价',
  enabled           TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '下线置0，不删行',
  sort              INT          NOT NULL DEFAULT 0 COMMENT '下拉排序，小在前',
  -- BaseEntity 审计列：create_time/update_time/create_user/update_user/deleted
  PRIMARY KEY (id),
  KEY idx_enabled_sort (enabled, sort)
) COMMENT='可选模型目录（平台级，运营维护）';
```

> **唯一键策略**：遵循项目「业务表除主键外禁唯一键」约定（见记忆 `jm-no-unique-keys-policy`）。`value` 重复在**应用层/前端**校验拦截并给可读提示，不加 DB 唯一索引。

## 4. 后端改造

### 4.1 ① 目录源切换：Nacos → DB
- 新增 `AiModel` 实体（`@Data` class，**非 record**，遵循 `jm-jackson-no-record-dto`）+ `AiModelMapper`（MyBatis-Plus）。
- `ModelCatalogService` 数据源由 `ModelCatalogProperties` 改为 `ModelRegistry`（见 4.2）。`listEnabled()` / `isValidModel()` / `maxTempOf()` 行为与出参不变。
- `/data/admin/models` 出参形态（value/label/provider/maxTemp/description）保持不变，前端无需改。
- 迁移完成后删除 `ModelCatalogProperties` 与 Nacos `ai.model-catalog` 配置块。

### 4.2 ② chat 按模型路由
- **`ModelRegistry`（新）**：唯一查询入口，`value → {protocol, provider, upstreamModel, maxTemp, Price}`。进程内缓存（如 `ConcurrentHashMap`），运营 CRUD 写操作后调用 `refresh()` 重建。所有「可选模型」判断、路由解析、取价都走它。
- **`ProviderRegistry`**：新增 `ChatClient chat(String providerName)` 重载，按名取 client；保留 `chat()`（= 全局 active，仅供无模型上下文的旧路径/兜底）。
- **`ClaudeService` / `OpenAiService`**：调 chat 前插入解析：
  1. 取 `requestBody.model`（逻辑 value）→ `ModelRegistry.resolve(value)`；解析不到 → 业务异常（`ExceptionCode`，可读提示）。
  2. 校验三方协议一致：当前服务协议（anthropic/openai）== 模型 `protocol` == 连接 `chat.protocol`。不一致 → 业务异常。
  3. `providerRegistry.chat(model.provider)` 取目标连接 client。
  4. 计价用逻辑 value 先取（见 4.3），再把 `requestBody.model` 改写为 `upstream_model` 下发。
- **embedding / rerank / 上下文化**：不动，继续 `providerRegistry.embedding()/rerank()/contextualization()`（全局 `ai.provider`）。`ai.provider` 语义保留为「基础设施默认 provider」。
- **启动校验**：`ProviderRegistry.validate()` 扩展——遍历 `ai_model` 所有 enabled 行，校验其 `provider` 有对应协议的 chat client bean + Nacos 连接块齐全；缺失 fail-fast，错误信息点名是哪个模型/连接。

### 4.3 ③ 计费读 registry
- `ModelPricing.compute(model, usage)`：先 `ModelRegistry.priceOf(value)` 精确取价；命中即用该行四件套。
- 未命中（embedding/rerank/上下文化模型、历史日志里的旧名）→ 落回**现有前缀表**逻辑（保留 `PRICES` / embedding / rerank / 默认兜底，一字不改）。
- 取价发生在 chat 路径改写 upstream 名**之前**；`ai_model_call_log.model` 继续存逻辑 value，花费统计「模型×功能」口径不变（见记忆 `jm-billing-biztype`）。

### 4.4 运营 CRUD 接口
`/admin/operator/models`（SysOperator 鉴权，`runAsSystem` 跨租户写）：
- `GET /admin/operator/models` 列表（含 disabled，按 sort）
- `POST /admin/operator/models` 新增
- `PUT /admin/operator/models/{id}` 编辑
- `PATCH /admin/operator/models/{id}/enabled` 启用/下线开关
- `DELETE /admin/operator/models/{id}` 删除（软删）
- 写操作成功后触发 `ModelRegistry.refresh()`。
- 入参校验：`protocol == 所选 provider 的连接协议`；`value` 重名拦截给可读提示（不靠 DB 唯一键）；`provider` 必须是 Nacos 已配置的连接名。

## 5. 运营前端（jm-operator）

- 新增 `src/pages/operator/ModelManagePage.tsx` + `src/features/operator/modelApi.ts`（沿用 `/admin/operator/*` 前缀与现有 feature 分层），并入现有页面那排（Enterprise/Usage/Trace/Feedback/Settings）。
- 表格列：value / label / provider / protocol / 四档单价 / enabled 开关 / 操作。
- 新增·编辑抽屉：provider 下拉来自 `GET /admin/operator/model-providers`，**选定 provider 自动带出并锁定 protocol**（强约束协议一致）；单价四件套、upstream_model、maxTemp、sort、description。
- 数值字段注意 `data-service` 把数字按字符串下发（见记忆 `jm-api-numbers-as-strings`），算术前 `Number()` 兜底。
- 文件下载/二进制无关，此页纯 JSON CRUD。

## 6. 迁移 / 兼容 / 回滚

- **迁移脚本**（仓库内 `modules/data-server/docs/ops-20260616-ai-model-table.sql`）：建表 + seed。seed 数据 = 当前 Nacos `ai.model-catalog` 的 3 条（claude-opus-4-8 / opus-4-6 / sonnet-4-6 / haiku-4-5...）与 `ModelPricing` 对应档位价合并；`upstream_model` 先 = `value`（当前 302ai 直连场景一致），`provider` = `302ai`，`protocol` = `anthropic`。
- **存量兼容**：Agent 表里已存的 model value 必须能在新 `ai_model` 里解析到；seed 必须覆盖现网在用的所有 value。
- **灰度保险**：一期内若 `ai_model` 为空，`ModelCatalogService` 临时回落读 Nacos `ai.model-catalog`（双源择一）；迁移验证通过后移除回落与 Nacos 块。
- **回滚**：保留 Nacos 目录块到迁移稳定后再删，可快速切回。

## 7. 测试 / 验收（符合「改完必自测」硬要求）

- **单测**：`ModelRegistry` 解析与缓存刷新；协议一致校验（含不一致报错）；`ModelPricing` 精确价命中 + 前缀表兜底；启动校验对「引用了缺失连接的 enabled 模型」fail-fast。
- **端到端实跑 + 查库**（本地 8020 + 8849 Nacos + :8082/运营前端）：
  1. 运营页新增一个模型 → `/data/admin/models` 出现 → 调试台下拉可见。
  2. 选该模型发真实对话 → 走对目标上游连接、下发的是 `upstream_model`。
  3. 查 `ai_model_call_log`：`model` 列 = 逻辑 value、`cost_usd` 按该行单价算。
  4. 下线模型 → 下拉消失、`isValidModel` 拒绝。
  5. 协议不一致的模型行 → 保存被拦 / 启动 fail-fast。

## 7.5 实现说明（与设计的差异，已落地并实测）

- **无 Flyway，迁移手动执行**：本项目并无 Flyway 自动迁移（历史 `V*.sql` 均手动跑）。`V20260616__ai_model.sql` 需手动在 `data-server` 库执行，**务必带 `--default-character-set=utf8mb4`**，否则中文 seed（description）会被按 latin1 双重编码成乱码：
  `docker exec -i dev-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 data-server < modules/data-server/src/main/resources/db/migration/V20260616__ai_model.sql`（生产按实际连接调整）。
- **首次装载用 ApplicationReadyEvent**：`ModelRegistry` 改用 `@EventListener(ApplicationReadyEvent.class)` 而非 `@PostConstruct`，避免 bean 初始化早于建表；并对首次装载加容错（失败暂用空快照、不拖垮启动）。
- **计费口径按「逻辑 value 或 upstream_model」**：chat 路径会把 `requestBody.model` 改写为 `upstream_model` 下发，故调用日志 `model` 列存的是 upstream 名；`ModelPricing` 先按 registry（value 或 upstream 双向命中）取价，再落回前缀表。302ai 现状 upstream==value，实测计费 0.022425（=1475/1e6×15 + 4/1e6×75），与改造前同价，**口径零变化**。
- **路由未命中优雅回落**：`ModelResolver` 解析不到模型时回落全局 active provider 且不改写，兼容 embedding/历史值/灰度空表（比设计的硬 fail-fast 更稳）。
- **禁用=彻底停用（运行时拒绝）**：`enabled=false` 不止从下拉隐藏，`ModelResolver` 运行时命中 disabled 行也直接抛「模型已下线」拒绝对话（存量 Agent 用到会立即报错，需改 Agent 模型）。三态矩阵：命中+启用→路由；命中+禁用→拒绝；未命中→回落 active。**计费历史口径不受影响**：`ModelPricing.priceOf` 仍解析 disabled 行，过往日志成本照算。
- **启动校验降级为运行期 + 装载告警**：未对「enabled 模型引用缺失连接」做启动 fail-fast（避免启动顺序耦合），改为请求期在 `ModelResolver` 抛错 + 装载日志可见。
- **本地运行**：data-server:8020 以 `NACOS_SERVER_ADDR=127.0.0.1:8849`（dev-nacos 映射端口）启动；MySQL 在 dev-mysql `:3307`、库 `data-server`。

## 8. 已解决的决策点（备查）

- 管理范围：①+②+③ 全做（路径 A）。
- 路由诉求：多 provider 同时生效（按模型分流），但**仅 chat**；embedding/rerank/上下文化 仍单 provider。
- 连接与密钥：连接留 Nacos，模型只引用连接名，密钥不落 DB/不上 UI。
- 模型目录层级：平台级、全租户共享（不做按租户差异化）。
