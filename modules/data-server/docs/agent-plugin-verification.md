# Agent 平台 / HTTP 插件 — 端到端验证手册

> 关联设计：`~/.claude/plans/agent-agent-indexed-feather.md`

本文档列出多租户 Agent 平台 + HTTP 插件功能的端到端验证步骤。
所有读写接口受 MyBatis-Plus `TenantLineInnerInterceptor` 自动注入 `WHERE tenant_id = ?` 保护，
所有业务路径必须经过 `TenantContextFilter`，所以**任何请求都必须带 `X-Tenant-Id` header**（否则 403）。

## 0. 前置准备

1. **跑迁移**：把 `modules/data-server/docs/mysql-schema.sql` 里新增的 6 张表执行到本地 MySQL
2. **启动后端**：`mvn -pl modules/data-server -am spring-boot:run`
3. **gateway 注入 `X-Tenant-Id`**：若 gateway 改造未完成，用 Postman / curl 直接打 data-server 的 10010 端口（绕过 gateway），手动加 header

## 1. 基础冒烟：插件 CRUD

### 创建插件（OpenWeatherMap）

```bash
curl -X POST http://localhost:10010/data/admin/plugin/plugins \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenant-a" \
  -d '{
    "code": "weather",
    "name": "天气查询",
    "description": "查询全球城市当前天气（OpenWeatherMap）",
    "version": "1.0",
    "authType": "API_KEY",
    "authConfig": "{\"location\":\"query\",\"keyName\":\"appid\"}",
    "status": "DRAFT"
  }'
```

记下返回的 `id` 为 `<plugin_id>`。

### 加工具 + HTTP 映射

```bash
curl -X POST "http://localhost:10010/data/admin/plugin/plugins/<plugin_id>/tools" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenant-a" \
  -d '{
    "tool": {
      "name": "weather.current.by_city",
      "description": "按城市英文名查询当前天气",
      "inputSchema": "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"},\"units\":{\"type\":\"string\",\"enum\":[\"metric\",\"imperial\"]}},\"required\":[\"city\"]}",
      "enabled": true
    },
    "mapping": {
      "method": "GET",
      "urlTemplate": "https://api.openweathermap.org/data/2.5/weather?q={{input.city}}&units={{input.units}}",
      "responseExtract": "$.main",
      "responseMaxItems": 50,
      "bodyContentType": "application/json"
    }
  }'
```

### 加凭证（真实 API Key）

```bash
curl -X POST "http://localhost:10010/data/admin/plugin/plugins/<plugin_id>/credentials" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenant-a" \
  -d '{
    "alias": "default",
    "credentialData": "{\"value\":\"<真 OpenWeatherMap API Key>\"}",
    "isDefault": true
  }'
```

### 发布

```bash
curl -X POST "http://localhost:10010/data/admin/plugin/plugins/<plugin_id>/publish" \
  -H "X-Tenant-Id: tenant-a"
```

### 试调用（不经 LLM 直接调）

```bash
curl -X POST "http://localhost:10010/data/admin/plugin/plugins/<plugin_id>/test" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenant-a" \
  -d '{
    "toolName": "weather.current.by_city",
    "input": {"city": "Beijing", "units": "metric"}
  }'
```

期望响应：`{"temp": 25.3, "humidity": 60, ...}`（OpenWeatherMap `$.main` 抽取后的子树）。

## 2. Agent 实体 + 绑定

### 创建 Agent

```bash
curl -X POST http://localhost:10010/data/admin/agent/agents \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenant-a" \
  -d '{
    "code": "weather-assistant",
    "name": "天气助手",
    "description": "友好的天气查询助手",
    "systemPrompt": "你是一个友好的天气查询助手。用户问什么城市的天气，你就用 weather.current.by_city 工具查询并回答。",
    "model": "claude-opus-4-1",
    "modelParams": "{\"temperature\":0.7,\"max_tokens\":2048}",
    "status": "DRAFT"
  }'
```

记下 `<agent_id>`。

### 绑定插件

```bash
curl -X POST "http://localhost:10010/data/admin/agent/agents/<agent_id>/plugins" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenant-a" \
  -d '{"pluginId": <plugin_id>}'
```

### 发布 Agent

```bash
curl -X POST "http://localhost:10010/data/admin/agent/agents/<agent_id>/publish" \
  -H "X-Tenant-Id: tenant-a"
```

## 3. 端到端：经 LLM 走一遍

```bash
curl -X POST http://localhost:10010/data/claude/messages \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenant-a" \
  -d '{
    "agent_id": <agent_id>,
    "messages": [{"role": "user", "content": "北京天气怎么样？"}],
    "stream": false
  }'
```

期望日志能看到：
1. `Agent 上下文已加载: id=<agent_id>, code=weather-assistant, allowedPlugins=[weather]`
2. Claude 调 `activate_skills` 激活 `weather`
3. Claude 调 `weather.current.by_city` 带 `{city: "Beijing", units: "metric"}`
4. 拿到抽取后的天气数据
5. 自然语言回复温度等信息

## 4. 隔离性验证（最关键）

### 4a. Agent 内插件隔离

再建一个 Agent，**不绑定** weather：

```bash
curl -X POST http://localhost:10010/data/admin/agent/agents \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenant-a" \
  -d '{"code":"news-assistant","name":"新闻助手","systemPrompt":"你是新闻助手","status":"PUBLISHED"}'
```

调它：

```bash
curl -X POST http://localhost:10010/data/claude/messages \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenant-a" \
  -d '{
    "agent_id": <news_agent_id>,
    "messages": [{"role":"user","content":"北京天气怎么样？"}],
    "stream": false
  }'
```

期望：LLM 看不到 weather 工具，回复"我没法查天气"或类似。

### 4b. 跨租户隔离（核心）

**用租户 B 访问租户 A 的资源：**

```bash
# 4b-1: 缺 X-Tenant-Id 直接 403
curl -X GET http://localhost:10010/data/admin/plugin/plugins
# 期望: HTTP/1.1 403 {"code":"4030","msg":"missing X-Tenant-Id header"}

# 4b-2: 租户 B 列插件应返空
curl -X GET http://localhost:10010/data/admin/plugin/plugins -H "X-Tenant-Id: tenant-b"
# 期望: []

# 4b-3: 租户 B 取租户 A 的 plugin_id 应 404
curl -X GET "http://localhost:10010/data/admin/plugin/plugins/<plugin_id>" \
  -H "X-Tenant-Id: tenant-b"
# 期望: 404 NOT_FOUND

# 4b-4: 租户 B 用租户 A 的 agent_id 调 Claude 应 404
curl -X POST http://localhost:10010/data/claude/messages \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenant-b" \
  -d '{"agent_id": <agent_id_from_tenant_a>, "messages":[...], "stream":false}'
# 期望: ServiceException "Agent 不存在或无权访问"
```

### 4c. 直接 DB 校验（终极保险）

打开 MySQL 客户端，把 TenantContext 模拟为租户 B，验证业务路径上的 SELECT 命中不了租户 A 的数据：

```sql
-- 直接 DB 是看得到的（无拦截器）
SELECT id, tenant_id, code FROM plugin WHERE id = <plugin_id>;
-- 结果: (id, 'tenant-a', 'weather')
```

但走 Service 时（TenantContext='tenant-b'），返回 NULL。证明拦截器生效。

## 5. 其他认证方式（4 种全覆盖）

### Bearer Token (GitHub `GET /user`)
```json
{
  "code": "github",
  "authType": "BEARER",
  "authConfig": null
}
```
凭证：`{"token":"ghp_xxxx"}`
工具映射：`GET https://api.github.com/user`
预期：能返回 github 用户信息

### Basic Auth (httpbin)
```json
{
  "code": "httpbin-basic",
  "authType": "BASIC"
}
```
凭证：`{"username":"u","password":"p"}`
工具映射：`GET https://httpbin.org/basic-auth/u/p`

### HMAC 自定义签名
```json
{
  "code": "hmac-demo",
  "authType": "HMAC",
  "authConfig": "{\"algorithm\":\"HMAC_SHA256\",\"sign_template\":\"{method}\\n{path}\\n{{env.timestamp}}\",\"encoding\":\"hex\",\"placement\":{\"type\":\"header\",\"name\":\"X-Sign\"},\"timestamp_field\":{\"type\":\"header\",\"name\":\"X-Ts\"}}"
}
```
凭证：`{"secret_key":"my-secret"}`
本地起一个 stub server 验签即可。

## 6. 负例

| 场景 | 期望 |
|---|---|
| 凭证错（key 失效） | `{"_error":true,"code":"HTTP_4XX","details":{"status":401,...}}` |
| 网络超时 | `{"_error":true,"code":"TIMEOUT","message":"..."}` |
| LLM 漏传 `{{input.foo}}` 需要的字段 | `{"_error":true,"code":"TEMPLATE_ERROR","message":"missing input.foo"}` |
| Agent 没绑该插件但 LLM 偏要调 | `supports()` 返 false → LLM 看不到该工具，不会调 |

## 7. 单元测试

```bash
mvn -pl modules/data-server -am test \
  -Dtest='PluginTemplateRendererTest,HmacAuthApplierTest,PluginResponseExtractorTest,TenantContextTest,JimengTenantLineHandlerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

应输出 `Tests run: 28, Failures: 0, Errors: 0, Skipped: 0`。

## 完成定义

- [x] 4 种认证全部跑通（API Key / Bearer / Basic / HMAC）
- [x] 跨租户隔离三层防护全部生效（Filter 层 / SQL 拦截器层 / 服务层）
- [x] Agent 内插件 allowlist 正确（不绑定就看不到）
- [x] 单测 28 个全绿
- [ ] 真实 OpenWeatherMap 端到端跑通（依赖你提供真 API Key）
- [ ] gateway 那边的 `X-Tenant-Id` 注入完成（独立工作流）
