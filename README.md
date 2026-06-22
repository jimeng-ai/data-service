# data-service

`data-service` 是一个基于 **Spring Boot 3 + Spring Cloud Alibaba + Nacos** 的多租户 AI Agent 平台后端。

它最初只做高德地图数据查询与 POI 分析，目前核心已经演进为一套**与厂商无关的 LLM 网关**：支持多轮工具调用、技能（Skill）/ 插件（Plugin）扩展、RAG 知识库、对话式 Agent 构建、计费与调用链追踪，并在网关层做统一鉴权与租户隔离。

> **接手提示**：仓库历史上还存在过一个独立的 `sys-server` 模块，**现已不存在**。所有业务 + 管理后台 + AI 能力都收敛到了 `data-server` 这一个业务模块里（原 `sys-server` 能力并入其 `admin` 包）。文档与代码冲突时，**以代码和 `bootstrap.yml` 为准**。

## 项目定位

当前仓库已落地的能力大致分四块：

- **AI 平台（核心）**：provider 抽象的 LLM 网关、多轮工具调用循环、技能/插件体系、RAG 知识库、对话式 Agent 构建、模型管理、按「模型 × 功能」计费、调用链 Trace。
- **管理后台**：两层管理模型（运营 operator / 企业租户）、基于 RBAC 的资源授权、企业（租户）与成员管理、产品反馈、运营统计。
- **地图数据（早期能力，保留）**：对接高德开放接口的关键词 / 周边 POI 查询，按 `typecode` 分类后做 DBSCAN 聚类与周边分析，行政区编码 / POI 分类字典维护。
- **通用基础设施**：统一响应 / 异常、JWT、Redis/Redisson、OkHttp、Knife4j、Snowflake ID、MyBatis-Plus、多租户拦截、SSE 工具等。

## 架构概览

两个可运行的 Spring Boot 应用：

- **`GatewayApplication`**（`gateway`）—— Spring Cloud Gateway，统一路由 + JWT 鉴权过滤器。
- **`DataServerApplication`**（`modules/data-server`）—— 唯一的业务模块，承载 `gaode` / `admin` / `ai` 全部逻辑。

配置统一从 **Nacos** 加载（不在仓库里）。各服务 `bootstrap.yml` 声明要拉取的 `data-id`（如 `data-server.yml`、`gateway.yml`、`default-mysql.yml`、`knife4j.yml`），命名空间 `fe9e39ae-06af-49c3-9c5b-6060df2cf93e`，分组 `DEFAULT_GROUP`。改运行时配置请改 Nacos，不要改仓库。

## 技术栈

- Java 17（`maven.compiler.parameters=true` 是刻意开启的，用于 Spring 参数名解析，勿删）
- Spring Boot 3.0.2
- Spring Cloud 2022.0.0 / Spring Cloud Alibaba 2022.0.0
- Spring Cloud Gateway + OpenFeign
- Nacos（注册中心 + 配置中心）
- MyBatis-Plus 3.5.5（MySQL 8）
- Redis / Redisson 3.13.4
- RabbitMQ（RAG 入库异步流水线）
- Elasticsearch 8.13.4（RAG 向量 + 全文检索，需 `analysis-ik` 中文分词插件）
- MinIO 8.3.7（文件 / 图片对象存储）
- OkHttp 4.9.3 / OkHttp SSE（LLM 上游调用与流式）
- Knife4j 4.3.0 / OpenAPI 3
- 文档解析：Tika 2.9.2 / PDFBox 3.0.3 / POI 5.2.5 / commonmark 0.22.0；分词计数 jtokkit 1.1.0
- Hutool / Guava / Fastjson2 / EasyExcel 等工具库

## 模块结构

```
data-service
├── common
│   ├── common-core         # 横切基础设施：统一响应/异常、JWT、Redis/Redisson、OkHttp、
│   │                       #   Knife4j、tenant 多租户包、MyBatis-Plus 配置、SSE 工具
│   ├── common-persistence  # 全部 MyBatis-Plus 实体 + Mapper；BaseEntity + MyMetaObjectHandler 自动填充
│   └── common-identifier   # Snowflake ID 服务
├── export                  # 对外暴露的 Feign API 接口（如 SysApi）
├── gateway                 # Spring Cloud Gateway：路由 + JWT 鉴权过滤器
└── modules
    └── data-server         # 唯一业务模块，三大包：gaode / admin / ai
```

`modules/data-server` 内部主要包：

- `gaode` —— 高德 POI 查询 + DBSCAN 聚类（早期能力）。
- `admin` —— 鉴权 / RBAC / 多租户运营后台（`auth`、`operator`、`rbac`）。
- `ai` —— 平台核心：`provider`、`protocol`、`conversation`、`skill`、`plugin`、`plugingen`、`rag`、`agent`（含 `builder`）、`chat`、`run`、`claude`、`openai`、`model`、`billing`、`trace`、`stats`、`search`、`feedback`、`image` 等。

## 核心子系统

### 1. AI 子系统（`ai`）

平台主体：一套 provider 无关的 LLM 网关，支持工具调用、技能/插件与 RAG。

- **Provider 抽象**（`ai/provider`）：`ProviderRegistry` + SPI（`ChatClient` / `EmbeddingClient` / `RerankClient` / `ContextualizationClient`）。激活的 provider 由 `ai.provider` 决定；`@PostConstruct` 做 **fail-fast** 校验，缺 bean 或缺 yml 字段则启动中止。
- **协议适配**（`ai/protocol`）：`AiProtocolAdapter` 统一 `ClaudeProtocolAdapter`（Anthropic）与 `OpenAiProtocolAdapter` 的差异（工具定义、tool_use 提取、多轮消息拼装、usage 解析、流式分帧）。
- **对话循环**（`ai/conversation`）：`AiConversationLoop` 驱动多轮工具调用（阻塞 `runBlocking` / 流式 `runStream`），受 `skill.max-tool-rounds` 限制，累计 token 用量，发 SSE 事件（`progress` / `tool_result` / `summary` / `error`），并记录每次模型调用。
- **技能 vs 插件**（`ai/skill`、`ai/plugin`）：`ToolPackage` 要么是平台 **Skill**（`tenantId == null`），要么是租户 **Plugin**（`tenantId != null`）。Skill 走「发现 → `activate_skills` → 注入」，避免工具定义淹没上下文；Agent 绑定的插件则直接作为 tool_use 注入。插件是 DB 驱动的 HTTP 工具（`Plugin` / `PluginTool` / `PluginHttpMapping` / `PluginCredential`），由 `PluginTemplateRenderer` 渲染请求、`PluginResponseExtractor` 抽取结果、`PluginAuthApplier`（ApiKey / Basic / Bearer / Hmac）施加鉴权。
- **RAG**（`ai/rag`）：入库经 RabbitMQ 异步（`IngestionQueueProducer` → `IngestionQueueConsumer`）：解析（Tika / PDFBox / docx / markdown 注册表）→ `HierarchicalChunker` → `ContextualizationService` → `EmbeddingService` → Elasticsearch 索引；查询走 `HybridSearchService` + `RerankService` → `RagAnswerService`。
- **Agent 构建与运行**（`ai/agent`）：对话式 Agent 向导（`builder`）、Agent 运行时与执行（`runtime` / `exec`）。
- **可重连对话**（`ai/run`、`ai/chat`）：服务端自有 run + Redis Stream 续播，发送后可离开 / 可重连。
- **计费与可观测**（`ai/billing`、`ai/trace`、`ai/stats`）：按「模型 × 功能（`biz_type`）」记账；调用链 Trace 埋点（LLM / 工具 / 插件 / KB / Rerank）。

### 2. 管理后台与多租户（`admin`）

**两层管理模型**：

- *运营层*（`SysOperator`）管理企业（`SysEnterprise` = 租户）。
- *企业层*（`SysUser`，超管 / 成员）走 RBAC，角色经 `SysRoleResource` 授予对资源类型 `MENU / AGENT / KNOWLEDGE_BASE / PLUGIN` 的权限。`PermissionResolver` 按请求实时解析有效权限（权限**不**写进 JWT）。

**租户隔离核心不变量**（勿削弱）：

1. 网关 `AuthorizeFilter`（order 99）校验 JWT，注入下游头 `user-id`、`X-Tenant-Id`、`x-trace-id`，并**剥离客户端自带的 `X-Tenant-Id`**——租户身份只来自签名 token。
2. `TenantContextFilter`（common-core）读取并校验 `X-Tenant-Id`，写入 `TenantContext`（ThreadLocal），`finally` 清理；缺失 / 非法 → 403。
3. `JimengTenantLineHandler`（MyBatis-Plus）按**正向白名单** `TENANT_AWARE_TABLES` 自动注入 `WHERE tenant_id = ?`。**新增租户表必须把表名加进白名单**（或 `tenant.extra-tenant-tables`），否则跨租户漏数据。
4. 合法的跨租户 / 系统查询（启动缓存加载、运营后台）用 `TenantContext.runAsSystem(...)` 跳过租户过滤。

> **流式 / 异步注意**：流式端点在独立 `streamExecutor` 线程跑，请求作用域的 ThreadLocal（`RequestContextHolder` / `TenantContext` / MDC / `AdminRequestContext`）**不会自动传播**。新增异步 / 流式逻辑时用 `MdcAsyncSupport.wrap(...)` 包装任务，否则租户过滤与用户解析会失败。

### 3. 地图数据（`gaode`，早期能力）

| 端点 | 说明 |
|---|---|
| `POST /data/gaode/get-poi-by-keyword` | 关键词检索高德 POI |
| `POST /data/gaode/get-poi-by-around` | 按坐标 + 半径检索周边 POI |
| `POST /data/gaode/get-poi-cluster` | 按 `typecode` 分类后做 DBSCAN 聚类 |
| `POST /data/gaode/analysis-around-poi` | 先聚类，再分析聚类中心周边 POI 与写字楼分布 |
| `/data/adcode-citycode-dict/*` | 行政区编码字典查询 / 更新 |
| `/data/poi-category-dict/*` | POI 分类字典查询 / 更新 |

聚类：经纬度球面距离，默认 `eps = 3000m`、`minPoints = 3`，输出聚类中心点、簇内 POI 与噪声点。

## 网关与路由

仓库 `gateway/bootstrap.yml` 中声明的路由：`/data/**` → `lb://data-server`（并开启 discovery locator）。所有业务 / 管理 / AI 端点都挂在 `/data/**` 下（如 `/data/gaode/*`、`/data/rag/*`、`/data/admin/*`、`/data/skills/*` 等）。鉴权：网关统一校验 `Authorization` 中的 JWT，白名单路径放行，校验通过后注入 `user-id` / `X-Tenant-Id` / `x-trace-id`。

## 本地开发

### 1. 一键起基础设施

仓库自带 `deploy.sh`（macOS）和 `docker/docker-compose.yml`：

```bash
./deploy.sh            # 装宿主环境 + 起 docker 基础设施（不跑 mvn build）
./deploy.sh check      # 只检查环境
./deploy.sh infra      # 只起/重启基础设施
./deploy.sh build      # 只跑 mvn clean install -DskipTests
./deploy.sh all-with-build  # 装环境 + 起 docker + mvn build（完整流程）
./deploy.sh down       # 停止容器（保留数据卷）
```

基础设施（容器名前缀 `ds-`，凭据为本地开发用）：

| 服务 | Host:Port | 凭据 / 说明 |
|---|---|---|
| Nacos | `localhost:8848`（gRPC `9848`） | 免鉴权；控制台 `/nacos`。命名空间 `fe9e39ae-...`，组 `DEFAULT_GROUP`，运行时配置都在这里 |
| MySQL | `localhost:3306` | `root` / `123456`，时区 `Asia/Shanghai`，utf8mb4 |
| Redis | `localhost:6379` | 无密码，AOF 开启 |
| RabbitMQ | `localhost:5672`（UI `15672`） | `guest` / `guest` |
| MinIO | `localhost:9000`（S3）/ `9001`（控制台） | `minioadmin` / `minioadmin` |
| Elasticsearch | `localhost:9200`（`9300`） | 关安全、单节点、ES 8.13.4；首启自动装 `analysis-ik`，data-server 启动时**硬校验**其存在 |
| Kibana | `localhost:5601` | 浏览日志 / ES，zh-CN |
| Filebeat | — | 把 `~/logs/data-server/*.log` 送进 ES 索引 `data-server-yyyy.MM.dd` |

### 2. 准备 Nacos 配置

配置不在仓库里。需在 Nacos 命名空间 `fe9e39ae-06af-49c3-9c5b-6060df2cf93e` / 组 `DEFAULT_GROUP` 下准备各服务 `bootstrap.yml` 声明的 `data-id`，例如：

- `data-server`：`data-server.yml`、`default-mysql.yml`、`default-redis.yml`、`default-rabbitmq.yml`、`default-okhttp.yml`、`knife4j.yml` 等
- `gateway`：`gateway.yml`、`default-mysql.yml`

> 以各服务 `bootstrap.yml` 中声明的 `data-id` 为准。

### 3. 构建与启动

```bash
mvn clean install -DskipTests       # 构建全部模块（或 ./deploy.sh build）
```

按序启动：先起 Nacos/MySQL/Redis/RabbitMQ/Elasticsearch（`./deploy.sh infra`）→ 准备好 Nacos 配置 → 启动 `DataServerApplication` → 启动 `GatewayApplication`。

> 本地 IDE 直跑时 Nacos 默认 `localhost:8848`；容器部署由环境变量 `NACOS_SERVER_ADDR` / `NACOS_NAMESPACE` 覆盖。

### 4. 测试

测试集中在 `modules/data-server`（JUnit 5 + jqwik 属性测试）：

```bash
mvn -pl modules/data-server test                                   # 全部
mvn -pl modules/data-server test -Dtest=HmacAuthApplierTest        # 单类
mvn -pl modules/data-server test -Dtest=PluginTemplateRendererTest#methodName  # 单方法
```

## 约定

- Controller 返回原始 payload，`GlobalResponseHandler` 包成 `CommonResponse`，`GlobalExceptionHandler` 映射异常；业务错误抛 `ServiceException(ExceptionCode.X, msg)`。
- ID 用 Snowflake；实体继承 `BaseEntity`，由 `MyMetaObjectHandler` 自动填充创建 / 更新审计字段。
- AI 模型调用由 `AiModelCallRecordService` 落 `ai_model_call_log`（大 body 落 `ai_model_call_content`）以便回放 / 观测，按 `connectionId` / trace-id 索引，日志可 grep。
- 二进制下载端点必须用 `void` + `OutputStream` 直接写出，否则会被 `GlobalResponseHandler` 包成 JSON 损坏前端 blob。

## 接口文档

集成 Knife4j / OpenAPI 3，文档经网关聚合 / 转发，实际访问地址取决于 Nacos 中的端口配置。相关排障见仓库内 `KNIFE4J_CONFIG.md`、`FIX_KNIFE4J_404.md`、`GATEWAY_KNIFE4J_WHITELIST.md`。

## 更多说明

仓库根目录 `CLAUDE.md` 提供了更细的架构不变量与排障指引；`docs/` 下有专题设计文档。接手时建议优先以代码、`bootstrap.yml` 与 `CLAUDE.md` 为准。
