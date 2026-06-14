# 对话式生成 Agent（AI 向导）—— 生产部署文档

> 功能：在 Agents 页「新建 Agent → AI 对话生成」，通过多轮对话生成 Agent 草稿并落地为 DRAFT Agent。
> 涉及三仓库分支 `feat/agent-builder`：`data-service`、`jm-agent-front`、`jm-operator`。
> 中间件改动只有两处：**MySQL 加 1 列** + **Nacos 加模型目录配置**。其余（Redis/MinIO/上游 LLM）复用现有、零改动；RabbitMQ/ES 不涉及。

---

## 0. 变更总览（给运维/DBA 的最短清单）

1. **MySQL**：`chat_conversation` 加一列 `builder_draft LONGTEXT NULL`。
2. **Nacos**：`data-server.yml` 的 `ai:` 节点下新增 `model-catalog.models`（⚠️ model 值必须是生产上游实际支持的模型名）。
3. **后端**：合并分支 → 重新构建 `data-server` jar → 滚动重启。
4. **前端**：合并分支 → 构建并发布 `jm-agent-front`、`jm-operator` 两个容器。

无需新建 Redis key、MinIO 桶、MQ 队列、ES 索引。构建器 Agent 按租户**首次使用时自动懒建**，无需 seed。

---

## 1. 前置确认

- 生产 LLM 上游（`ai.provider` 指向的 provider）支持哪些模型 → 决定下面 Nacos 模型目录里的 `value`。
- 生产 Nacos 的地址 / 命名空间 / `data-server.yml` 的 dataId（沿用现有，不新增 dataId）。
- 生产 MySQL 库名、`chat_conversation` 表存在。
- 维护窗口：后端需滚动重启；前端容器需重建。

---

## 2. MySQL 变更（先做）

迁移脚本（仓库内）：`modules/data-server/docs/ops-20260614-chat-conversation-builder-draft.sql`

```sql
ALTER TABLE chat_conversation
  ADD COLUMN builder_draft LONGTEXT NULL COMMENT '构建器草稿快照(JSON)，仅 AI 生成 Agent 向导会话使用';
```

执行（按生产连接方式调整）：

```bash
mysql -h <prod-host> -u<user> -p <db> < modules/data-server/docs/ops-20260614-chat-conversation-builder-draft.sql
```

> ⚠️ MySQL 的 `ADD COLUMN` 不支持 `IF NOT EXISTS`，**只可执行一次**。执行前先确认列不存在：
> ```sql
> SELECT COUNT(*) FROM information_schema.columns
> WHERE table_schema='<db>' AND table_name='chat_conversation' AND column_name='builder_draft';
> ```
> 返回 0 才执行。该变更是**纯新增可空列**，对存量数据与现有功能无影响，可在重启前安全执行。

**其它表无结构变更**：`agent`/`agent_plugin`/`ai_model_call_log`/`ai_trace`/`ai_trace_step` 只写新数据，沿用现有列（trace 头的 `biz_type`/`scene_code` 是既有列，本功能开始填充——存量旧 trace 仍为空，属正常）。

---

## 3. Nacos 变更（重启前做）

在 `data-server.yml` 的**现有 `ai:` 节点下**新增 `model-catalog`（注意是 `ai:` 的子节点，**不要**另起一个顶级 `ai:`，否则 YAML 键重复）：

```yaml
ai:
  # ……（保留现有 provider / system-prompt 等不动）……
  model-catalog:
    models:
      - value: claude-sonnet-4-6          # ⚠️ 改成生产上游实际支持的模型 id
        label: Claude Sonnet 4.6
        provider: anthropic
        max-temp: 1
        description: 均衡之选；日常客服/助手类 Agent 首选，性价比高
        enabled: true
      - value: claude-opus-4-6            # ⚠️ 同上，按生产上游可用模型调整
        label: Claude Opus 4.6
        provider: anthropic
        max-temp: 1
        description: 复杂推理、长文档、高质量人设设计
        enabled: true
      - value: claude-haiku-4-5-20251001
        label: Claude Haiku 4.5
        provider: anthropic
        max-temp: 1
        description: 高频、低延迟、低成本
        enabled: true
      # 可继续加 OpenAI 等模型；max-temp：anthropic=1、openai=2
```

字段说明：`value`=透传给上游的模型 id（**必须上游认**）；`label`=展示名；`provider`=anthropic/openai；`max-temp`=temperature 上限；`description`=给构建器选型用的一句话；`enabled`=false 可临时下线某模型。

> ⚠️ **两个必须对齐生产上游的点**：
> 1. 上面 `model-catalog` 里的 `value` 必须是生产 `ai.provider` 上游真实支持的模型名（否则 `draft_agent` 校验会拒绝、用户无法选模型）。
> 2. **构建器自身**用的模型当前硬编码在 `AgentBuilderService.BUILDER_MODEL = "claude-sonnet-4-6"`。若生产上游不支持该模型，需改这个常量为生产可用模型后再构建。（这块是已知可优化项：后续可改为读配置。）

`data-server.yml` 里 `shared-configs` 已配 `refresh: true`，Nacos 改配置后可热刷新；但 `@ConfigurationProperties` 列表绑定建议**配合重启**确保生效。

---

## 4. 代码集成

三仓库分支均为 `feat/agent-builder`，按你们流程合并：

- `data-service`：`feat/agent-builder` → 主干（`master`）。
- `jm-agent-front`：`feat/agent-builder` → `main`（推 `main` 会触发自助 runner 自动构建发布，见该仓 CI）。
- `jm-operator`：`feat/agent-builder` → `main`（同上，operator 容器端口见该仓 CI）。

> 注：dev 阶段 `data-service` 用的是 worktree（`../data-service-agent-builder`）。合并时以分支为准，worktree 只是工作副本。

---

## 5. 后端构建与部署（data-service）

```bash
# 合并后在主干上构建
mvn clean install -DskipTests
# 产物：modules/data-server/target/data-server-1.0-SNAPSHOT.jar
```

按你们生产方式滚动重启 data-server（替换 jar 后重启进程/容器）。启动日志出现 `Started DataServerApplication` 即可。

**Spring 上下文若能正常启动，即证明新增 Bean 装配无误**（构建器相关 service/executor、SkillRuntimeService 新依赖、模型目录配置绑定）。

---

## 6. 前端构建与部署

两个前端（`jm-agent-front`、`jm-operator`）按现有 CI/镜像流程发布。要点：
- 构建命令 `npm run build`（含 tsc 类型检查）。
- 生产镜像用生产 nginx 配置（`nginx.deploy.conf` 等，按你们部署约定），代理指向生产网关。
- `jm-agent-front` 改动：AI 向导页 + 调试台模型下拉改读 `GET /data/admin/models` + Trace 场景筛选。
- `jm-operator` 改动：仅 Trace 日志加「场景」筛选。

---

## 7. 上线后冒烟验证

用一个**企业端账号**（带 tenant 的 JWT，走生产网关）验证：

```bash
BASE=https://<prod-gateway>/data
TOKEN=<企业端 token>      # 注意：Authorization 直接放裸 token，无 Bearer 前缀

# 1) 模型目录可读（应返回上面配置的模型）
curl -s -H "Authorization: $TOKEN" $BASE/admin/models

# 2) 开会话
CID=$(curl -s -X POST -H "Authorization: $TOKEN" $BASE/admin/agent-builder/sessions \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['conversationId'])")

# 3) 发一轮 + 看流（应有 draft-update 与文本）
RUN=$(curl -s -X POST -H "Authorization: $TOKEN" -H "Content-Type: application/json" \
  -d '{"query":"做一个处理售后退货的客服 agent"}' \
  $BASE/admin/agent-builder/sessions/$CID/turns \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['runId'])")
curl -s -N -H "Authorization: $TOKEN" "$BASE/admin/agent-builder/runs/$RUN/stream"

# 4) 读回草稿
curl -s -H "Authorization: $TOKEN" $BASE/admin/agent-builder/sessions/$CID/draft

# 5) 落地为 DRAFT Agent
curl -s -X POST -H "Authorization: $TOKEN" -H "Content-Type: application/json" \
  -d '{"pluginIds":[],"kbIds":[]}' $BASE/admin/agent-builder/sessions/$CID/finalize
```

DB 侧抽查：
```sql
-- 草稿落库
SELECT builder_draft FROM chat_conversation WHERE id=<CID>;
-- 计费按功能维度
SELECT biz_type, COUNT(*) FROM ai_model_call_log WHERE biz_type='agent_gen' GROUP BY biz_type;
-- Trace 场景
SELECT biz_type, scene_code FROM ai_trace WHERE scene_code='agent_gen' ORDER BY id DESC LIMIT 3;
```

UI：进 Agents →「新建 Agent → AI 对话生成」→ 描述需求 → 右侧预览实时填充 → 点「创建 Agent」跳调试台。运营端 Trace 日志「场景」下拉可筛「生成 Agent」。

---

## 8. 回滚

- **前端**：回滚到上一个镜像 tag 即可（功能纯增量，入口是新页面/新筛选项，回滚无副作用）。
- **后端**：回退到上一个 jar 重启。
- **Nacos**：删除 `ai.model-catalog` 节点（回退后旧代码不读它，留着也无害）。
- **MySQL**：`builder_draft` 列可保留（可空、无人写即恒 NULL，不影响旧代码）。如确需移除：`ALTER TABLE chat_conversation DROP COLUMN builder_draft;`（确认无新功能在用时再做）。

回滚顺序建议：前端 → 后端；Nacos/MySQL 可不动。

---

## 9. 已知事项 / 后续

- 构建器模型 `AgentBuilderService.BUILDER_MODEL` 当前硬编码，建议后续改为配置项。
- 运营端「模型花费」暂无「功能维度(biz_type)」图表；`agent_gen` 数据已记账，可后续单独加图。
- 构建器对话只挂内部工具 `draft_agent`，不调业务插件（设计如此）；业务插件经"右侧推荐勾选→绑到生成的 Agent"，由生成的 Agent 运行时调用。
- 向导每次进入开新会话，不做历史会话恢复（一次性创建流程）；生成进行中断线会自动续播。
