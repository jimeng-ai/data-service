# 沙箱平面的 conn_* 连接器（缺陷 B1 的修复）

> 配置位置：**Nacos 的 `data-server.yml`**。合入即部署生产，但**默认关闭**——
> 不在 Nacos 显式开启，行为与现状逐字一致。

## 修的是什么

`ChatRunService.decideExec()` 会把整轮对话路由到沙箱平面，判据有三条：本轮带附件、
**会话里曾经有过 `agent_input_file` 行**、或者 Agent 绑了 DOER 技能。

而沙箱平面过去**一个 `conn_*` 工具都没有**（`jm-agent-sandbox/src` 与 `skills` 全域零 `conn_` 命中）。
后果不是报错：模型只会拿历史数据讲、或者自己编。HTTP 类连接器还能经 egress 代理够到，
**彻底零路径的恰恰是 MYSQL**——语义层真正服务的那一类。

叠加缺陷 B12「一次上传，永久沙箱」，用户的实际体感是：传过一次附件之后，
这个会话的 Agent **突然不会查数据库了**，而且没有任何提示。

## 架构：工具在沙箱，执行留在宿主

```
容器内 CLI ──stdio 控制信道──▶ 边车宿主进程 (connectorTools.ts)
                                  │ Authorization: <per-run JWT 原值，无 Bearer>
                                  │ redirect: "manual"
                                  ▼
                        gateway :10011 AuthorizeFilter（零改动）
                                  ▼
       TenantContextFilter(+50) → ConnectorAgentScopeFilter(+56) → AccountStatusFilter(+60)
                                  ▼
                   ConnectorAgentCallbackController
                     └─▶ ConnectorToolExecutor（与 JVM 对话平面【同一个方法】）
                           └─▶ ConnectorGateway：agent_connection 实时授权 → 能力 → 写策略
                                 → 限流 → 执行 → 审计
                                 └─▶ ReadOnlySqlGuard / WriteSqlGuard
```

**两个平面最终跑的是同一个方法、同一条管道、同一套护栏。**容器连客户库的地址都不知道。

沙箱侧只有一层**极薄的工具代理**：组装请求体 → POST → 把返回的 JSON 原样渲染。
它不解析、不改写、不拼接任何 SQL——一旦在边车碰 SQL，服务端护栏看到的和库里执行的
就不是同一串字节，等于护栏不存在。

## 配置

```yaml
connector:
  agent:
    enabled: true                                   # 总开关，默认 false
    callback-base-url: http://localhost:10011/data  # dev；生产写 http://localhost:20011/data
    min-sandbox-tools-version: 1
```

**`callback-base-url` 故意没有默认值，空串 = 这项能力不可用。** `:8088` 是 dev 与 prod
**共用**的单进程、env 只有一份，给默认值就会让 dev 平面派发的运行回调到生产网关，
在另一套数据上执行读写**且不报错**。与 `jwt.secret` / `CONNECTION_CREDENTIAL_KEY` /
`SANDBOX_SERVICE_TOKEN` 同一条 fail-closed 纪律。

### 两条绝不能做的配置

1. **回调前缀 `/data/internal/connector-agent/**` 不得进网关白名单。**
   进了白名单，网关就不再注入 `user-id` / `X-Tenant-Id`，过滤器的 claims↔头交叉核对必然失败
   （全部 403）；若同时放宽核对，就是裸奔。
2. **也不得进 Nacos 的 `tenant.skip-paths`。**
   进了 `TenantContext` 未设，`TenantContext.required()` 直接抛，或查询走 `__no_tenant__` 兜底
   查不到任何行——fail-closed 但排查极其费劲。

## 下发条件（五个全真才下发，任一不真整段不下发）

1. `connector.agent.enabled` 为 true
2. `callback-base-url` 非空
3. Agent 有 id（有 runtime view）
4. **`userId` 非空**——`AccountStatusFilter` 对非数字 user-id 直接放行，用「无人触发」的系统身份
   会静默绕过账号/企业停用检查，一个已停用的企业仍能经沙箱查客户库
5. 该 Agent 确有被授予的连接，且边车报的 `connectorToolsVersion >= min-sandbox-tools-version`

第 5 条的版本位是 fail-closed 的关键：它让 data-service 能区分「边车太旧、静默忽略了
`connectorContext`」和「这个 Agent 没绑连接」。没有它，一次以为下发了连接器的派发会被当普通 run
跑完并回 success，模型手里一个 `conn_*` 都没有却照样给出答案——那正是 B1 本身的失败形态。

## 凭据与吊销

- 回调 token 是 per-run JWT（`purpose=connector-agent`，**钉死在 `aid` + `rid` 上**），
  只活在边车宿主进程的闭包里：不进容器 env、不进 prompt、不进任何工具结果或日志（全部过打码）。
- token **不携带被授权的 connection 集合**。那会是快照，超管撤销授权要等到下一轮才生效；
  连接归属一律由 `ConnectorGateway` 按 `agent_connection` 的**实时行**判定。
- `ConnectorAgentRunRegistry` 用 Redis 记 `conn-agent:run:{tenantId}:{runId} = agentId`，
  过滤器每次核对。run 结束在 `finally` 里 `DEL`（正常结束、超时被杀、异常中断三条路都走到）。
  少了这一步，一枚泄漏的 token 在整个 TTL 内都是一枚可用的、该 Agent 全部连接器的读写凭据，且无从吊销。
- 一枚带 `purpose` 的 token 打回调前缀**之外**的任何路径一律 403——防「一枚回调 token 变成一枚全权 token」。

## 工具名与文档

沙箱侧 server 名 `connector`，八个短名**原样保留 `conn_` 前缀**：

```
mcp__connector__conn_list      mcp__connector__conn_catalog   mcp__connector__conn_describe
mcp__connector__conn_query     mcp__connector__conn_invoke    mcp__connector__conn_execute
mcp__connector__conn_define_metric                            mcp__connector__conn_annotate
```

下发的是 `tools.json` 里的**全部**工具（`ConnectorAgentContextFactory.toolSchemas()` 逐条按
`^conn_[a-z_]{1,32}$` 校验后整组下发），所以这里的枚举只是当时的快照——真相以 `tools.json` 为准。

保留前缀让 `skills/connector/SKILL.md` **一个字都不用改**就能两平面共用（正文里 `conn_*` 字面量
38 处、tools.json 描述里 28 处）。工具的 name/description/input_schema **随 run 下发**而不是
在边车抄一份：`tools.json` 是唯一真相源，抄一份的结果是两平面的模型行为半年后静默分叉——
而那就是 B1 本身。

SKILL.md 全文经既有的 MinIO + 技能物化通道下发，走 SDK 的渐进式披露（常驻上下文只有一行
description，正文只在模型真去读时才进）。系统提示里只放六条「跳过了就会出错、而且不报错」的硬契约
（约 700 字符，条件段——`prompt-test.mjs` 有 `base.length < 2200` 的上限断言，实测 base=2056，
只剩 144 字符余量）。

## 上线顺序

三轮 CI，**顺序不能反**：

1. **data-service 回调面**（纯加法，总开关默认关，行为零变化）
2. **jm-agent-sandbox**（工具 + 装配 + `connectorToolsVersion` 版本位；未下发 `connectorContext` 时行为逐字不变）
3. **data-service 派发侧**

第 3 轮上线后**仍需在 Nacos 显式配** `connector.agent.enabled=true` 与 `callback-base-url` 才真正生效。
漏配是 fail-closed 静默不可用——必须进上线 checklist。

## 验收

单测已覆盖（`mvn -pl modules/data-server test` + 沙箱 `npm test`，两边全绿）。
**端到端实跑按本工作区约定还需要做**（涉及 DB 的改动走实跑 + SQL 查库）：

1. 起 `dev-*` 基建 + `bash start-local.sh`，给一个 Agent 绑一条 MYSQL 连接
2. 会话里先传一个附件（把会话钉进沙箱平面），**下一轮问一个纯查库问题**
3. 验四条：
   - SSE `progress` 里出现 `mcp__connector__conn_query` 的 tool_use
   - 答案里带 `statement` 与 `row_count`，数字与直接 SQL 查库一致
   - `SELECT * FROM connector_audit WHERE trace_id='<x-trace-id>'` 有对应记录，
     `agent_id` 非空、`operation=conn_query`、`success=1`
   - `docker exec <沙箱容器> env` 里**没有**回调 token、**没有**数据库密码
4. 负向：跑一条不带 WHERE 的 UPDATE → `data.error="guard_blocked"`、SSE `tool_result` 是 error、
   `connector_audit` 里 `success=0`、**库里一行未改**
5. 吊销：run 进行中删掉 `agent_connection` 绑定 → 下一次 `conn_query` 必须 `not_found`；
   run 结束后用抓到的 token 重放回调 → 必须 403

## 代码位置

| | |
|---|---|
| 沙箱工具代理 | `jm-agent-sandbox/src/mcp/connectorTools.ts` |
| JSON-Schema→zod | `jm-agent-sandbox/src/mcp/jsonSchemaToZod.ts` |
| 装配（加一行） | `jm-agent-sandbox/src/profiles/defaultProfile.ts` 的 `MCP_WIRING` |
| 回调鉴权 | `ai/connector/agent/callback/ConnectorAgentScopeFilter.java` |
| 回调端点 | `ai/connector/agent/callback/ConnectorAgentCallbackController.java` |
| run 吊销 | `ai/connector/agent/callback/ConnectorAgentRunRegistry.java` |
| 派发下发 | `ai/agent/exec/service/AgentExecService.java` |
