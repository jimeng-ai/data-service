# 插件「服务端换 token + 缓存」鉴权设计（OAUTH2 / TOKEN_FETCH）

- **日期**：2026-06-10
- **状态**：已评审通过（待转实现计划）
- **涉及仓库**：`data-service`（后端，主）、`jm-agent-front`（前端，凭证/认证方式 UI）
- **作者**：jerry + Claude（brainstorming）

---

## 1. 背景与目标

现状：插件鉴权支持 `NONE / BEARER / BASIC / API_KEY / HMAC`。其中 BEARER 的 `{{secrets.token}}` 是用户在「凭证」Tab **手动粘贴的静态值**，运行时由 `BearerAuthApplier` 注入 `Authorization: Bearer <token>`。

痛点：很多接口（尤其内网/企业 API）需要**先调一个获取 token 的接口拿到 token，再把 token 放进 Authorization**。这类 token 会过期，静态粘贴无法长期可用。

目标：新增「服务端自动换 token + 缓存 + 过期/401 兜底」的鉴权能力，对 LLM 透明、安全（token 不经过 LLM 上下文）。覆盖两种形态：

- **标准 OAuth2 client_credentials**（规范、字段固定）
- **通用「登录换 token」**（请求形态、响应取值、注入位置全可配，覆盖各种内网接口）

技术上「通用模式」是「OAuth2」的超集（OAuth2 = 固定请求形态 + 固定取值的特例），故底层共用一套引擎。

### 既有可复用资产

- **扩展点**：`PluginAuthApplier` 接口（`authType()` + `apply(request, credentials, authConfig)`），Spring 自动收集成 `appliersByType` map；`PluginHttpInvoker` 第 4 步调用。
- **模板引擎**：`PluginTemplateRenderer`，支持 `{{input/secrets/env/meta.x}}` 占位符，`renderString` 已是 public。
- **JSONPath 子集**：`PluginResponseExtractor.applyPath`（`$.a.b` / `$.a[0]` / `$.a[*]`），目前 private，需抽公共。
- **缓存基建**：`RedissonClient`（RBucket / RLock）已在 `RunEventTee` 等处使用。
- **数据模型**：`Plugin.auth_type`(自由 String) + `Plugin.auth_config`(非密 JSON 串)；`PluginCredential.credential_data`(密钥 JSON 串，明文，租户内每插件一份)。

---

## 2. 范围外 / YAGNI

| 项 | 决定 |
|---|---|
| 凭证加密 | **维持明文**（与现有 BEARER token / BASIC 密码同级）；加密走既有 `encryption_version` 预留 seam，本特性不做。已与用户确认。 |
| refresh_token 轮换 | 不做。统一「过期 / 401 → 重新 fetch」。client_credentials 本无 refresh token。 |
| 绝对到期时间戳 | v1 把 `expire_path` 值当**相对时长**（`expires_in` 风格）；`expire_mode: absolute` 留扩展位。 |
| 「HTTP 200 + 业务码表示过期」 | v1 被动重试仅认 **HTTP 401**；200 体内业务码（如 `{code:"TOKEN_EXPIRED"}`）触发重取留后续（可配 `reauth_on`）。 |
| OAuth2 authorization_code / 用户授权流 | 不做（需浏览器交互，超纲）。 |
| auth_config 结构化表单 | v1 用 JSON 文本框（与 HMAC 一致）；结构化表单为后续打磨。已与用户确认。 |

---

## 3. 数据模型

两张表零结构改动，仅写入新 JSON 内容。`auth_type` 新增两个枚举值：`OAUTH2`、`TOKEN_FETCH`。密/非密拆分沿用 API_KEY/HMAC 约定：**非密 → `auth_config`，密钥 → `credential_data`**。

### 3.1 OAUTH2

```jsonc
// auth_config（非密）
{
  "token_url": "https://auth.example.com/oauth/token",
  "scope": "read write",            // 可空
  "client_auth": "body",            // body | basic，默认 body
  "expire_path": "$.expires_in",    // 默认 $.expires_in
  "default_ttl_sec": 3600,          // 响应没给过期时用
  "safety_margin_sec": 60           // 默认 60
}
// credential_data（密，凭证 Tab）
{ "client_id": "xxx", "client_secret": "yyy" }
```

固定形态：`POST token_url`，`application/x-www-form-urlencoded`，body 含 `grant_type=client_credentials` + client_id/secret/scope（`client_auth=basic` 时改走 `Authorization: Basic base64(client_id:client_secret)`，body 仅 grant_type/scope）；token 取 `$.access_token`；注入 `Authorization: Bearer <token>`。

### 3.2 TOKEN_FETCH

```jsonc
// auth_config（非密）
{
  "token_request": {
    "method": "POST",
    "url": "https://auth.example.com/api/login",          // 绝对 URL，与插件 baseUrl 无关
    "content_type": "application/json",
    "headers": { "Content-Type": "application/json" },
    "body": "{\"appKey\":\"{{secrets.appKey}}\",\"appSecret\":\"{{secrets.appSecret}}\"}"
  },
  "token_path": "$.data.token",
  "expire_path": "$.data.expire",   // 可空 → 用 default_ttl_sec
  "expire_unit": "sec",             // sec | ms，默认 sec
  "default_ttl_sec": 3600,
  "safety_margin_sec": 60,
  "inject": { "location": "header", "name": "Authorization", "prefix": "Bearer " }
}
// credential_data（密，键由用户自定义，body 模板里 {{secrets.x}} 引用）
{ "appKey": "xxx", "appSecret": "yyy" }
```

token 获取请求的 `url / headers / body` 都是模板，走现有 `{{secrets.x}}`（也可用 `{{env.timestamp}}` / `{{env.nonce}}` 应付签名式登录）；token 与过期用 JSONPath 子集取；注入位置/头名/前缀可配（默认 `Authorization: Bearer `，也可改 `X-Token`、无前缀、或 query）。

---

## 4. 组件与接口

### 4.1 `JsonPathUtil`（抽公共，重构）
把 `PluginResponseExtractor` 私有的 `applyPath`（`$.a.b` / `$.a[0]` / `$.a[*]` 子集）抽成共享静态工具。`PluginResponseExtractor` 与 `PluginTokenProvider` 共用。行为不变，回归测试保证。

### 4.2 `TokenFetchSpec`（DTO）
描述一次 token 获取：`method / url / contentType / headers / bodyTemplate / tokenPath / expirePath / expireUnit / defaultTtlSec / safetyMarginSec / inject(location,name,prefix)`。OAuth2 applier 拼固定值，Generic applier 从 auth_config 解析。

### 4.3 `PluginTokenProvider`（新 service，核心）
依赖：`@Qualifier("pluginHttpClient") OkHttpClient`、`PluginTemplateRenderer`、`RedissonClient`、`JsonPathUtil`。

```java
// 取缓存或加锁 fetch；ctx 提供 secrets/env 渲染 token 请求模板
String resolveToken(TokenFetchSpec spec, PluginExecutionContext ctx, String cacheKey);
// 401 兜底：删缓存桶
void invalidate(String cacheKey);
// 缓存键（含 authConfig + 凭证哈希）
String cacheKey(String tenantId, Long pluginId, Map<String,Object> authConfig, Map<String,Object> secrets);
```

`resolveToken` 内部：见 §5 子时序。fetch/extract 失败抛 `TokenFetchException`。

### 4.4 `TokenCachingAuthApplier`（子接口，不动现有 4 个 applier）
```java
public interface TokenCachingAuthApplier extends PluginAuthApplier {
    void applyWithContext(RenderedRequest req, PluginExecutionContext ctx,
                          Long pluginId, Map<String,Object> authConfig);
    void invalidate(PluginExecutionContext ctx, Long pluginId, Map<String,Object> authConfig);
}
```
基类 `apply(request, credentials, authConfig)` 对这两个 applier 抛 `UnsupportedOperationException`（指向 `applyWithContext`），因为它们需要 tenantId/pluginId 算缓存键。

### 4.5 两个 applier
- **`OAuth2ClientCredentialsAuthApplier`**（authType `OAUTH2`）：从 auth_config + 凭证构造固定 `TokenFetchSpec`（form body / basic 头二选一）→ `provider.resolveToken` → 注入 `Authorization: Bearer`。
- **`GenericTokenAuthApplier`**（authType `TOKEN_FETCH`）：从 auth_config 的 `token_request` 模板构造 `TokenFetchSpec` → `provider.resolveToken` → 按 `inject` 配置注入。

### 4.6 `PluginHttpInvoker` 改动
- 第 4 步认证：`applier instanceof TokenCachingAuthApplier` → 调 `applyWithContext`，否则维持旧 `apply`。
- 第 5 步之后：业务接口返 401 且 applier 是 token-caching 且未重试过 → `invalidate` → **重新渲染整条业务请求** → 重新 `applyWithContext`（缓存已空 → 加锁重取新 token）→ 重打一次（`retried=true`）。

> 现有 4 个 applier（ApiKey/Basic/Bearer/Hmac）一行不改。

---

## 5. 数据流（调用时序 + 401 重试）

`PluginHttpInvoker.doInvoke` 做成最多 2 次尝试的循环：

```
attempt 1:
  1. 解 secrets（PluginCredentialService）
  2. ctx = PluginExecutionContext{tenantId, input, secrets, env, meta}
  3. 渲染业务请求 → RenderedRequest req（resolveUrl 套 baseUrl）
  4. 认证：applyWithContext(req, ctx, pluginId, authConfig)
       spec     = TokenFetchSpec（OAuth2 固定 / Generic 从 authConfig）
       cacheKey = provider.cacheKey(tenantId, pluginId, authConfig, secrets)
       token    = provider.resolveToken(spec, ctx, cacheKey)
       注入 req.addHeader(inject.name, inject.prefix + token)（或 addQuery）
  5. OkHttp 打业务接口 → status

  若 status == 401 且 token-caching 且 未重试：
attempt 2:
  6. invalidate(ctx, pluginId, authConfig)            // 删缓存桶
  7. 重新渲染 req（旧 req body 已被消费、header 挂旧 token，必须重建）
  8. 重新 applyWithContext（缓存空 → 加锁重 fetch → 注入新 token）
  9. 重打一次；retried = true

  10. status < 400 → responseExtractor 抽取；≥400 → PluginError
```

`provider.resolveToken` 子时序：

```
bucket = redisson.getBucket(cacheKey); v = bucket.get()
v 命中               → 返回 v
未命中 → tryLock(cacheKey:lock, waitTime):
   双重检查 bucket（别的线程可能刚填好）→ 命中即返回
   渲染 token 请求模板（templateRenderer.renderString，ctx 提供 secrets/env）
   OkHttp 打 token 接口（pluginHttpClient + 超时）
   status≥400              → throw TokenFetchException(status+片段)
   JSONPath 取 token_path  → 空/缺 → throw TokenFetchException("token 提取失败")
   JSONPath 取 expire_path → 算 TTL（§6）
   bucket.set(token, ttl); 返回
   finally unlock
tryLock 超时（极端拥塞）→ 降级为不加锁直接 fetch（宁可多打一次，不阻塞业务）
```

---

## 6. 缓存键 / TTL / 并发

### 6.1 缓存键
```
plugin:auth:token:{tenantId}:{pluginId}:{hash8}
  hash8 = sha256( canonicalJson(authConfig) + "|" + canonicalJson(credentialData) ) 前 8 位
锁键   = {cacheKey}:lock
```
`tenant+plugin` 划范围；`hash8` 让改 auth_config 或换凭证后立刻得到新 key，旧 token 无人引用、自然 TTL 过期——**无需 hook `PluginCredentialService.save` 做显式驱逐**（可选「保存时顺手删旧桶」当兜底，非必需）。

### 6.2 TTL
- `expire_path` 命中 → `ttl = 归一到秒(expireValue) − safety_margin_sec`（默认 60）；`expire_unit=ms` 先 /1000；算出 `< 5s` 视为异常 → 回退 `default_ttl_sec`。
- `expire_path` 未配/未命中 → `ttl = default_ttl_sec`（默认 3600）。

### 6.3 并发（防 thundering herd）
- `RLock(cacheKey:lock)`：首个线程取锁→fetch→写缓存；其余 `tryLock(waitTime)` 等它，拿锁后二次确认缓存命中即返回，不重复打 token 接口。
- 锁 `leaseTime` = token-fetch 超时 + buffer，holder 崩溃自动释放（Redisson 看门狗），不死锁。
- `tryLock` 等不到 → 降级不加锁直接 fetch，保可用性。

---

## 7. 错误处理

- **新增错误码 `CODE_TOKEN_FETCH_FAILED = "TOKEN_FETCH_FAILED"`**，`details` 带 `{stage, status, body片段, path}`。

| 失败点 | 错误码 |
|---|---|
| auth_config 缺必填 / JSON 解析失败 | `CONFIG_INVALID` |
| 凭证缺字段 / 模板引用的 secrets.x 不存在 | `CREDENTIAL_MISSING` / `TEMPLATE_ERROR` |
| token 接口网络错/超时 | `TOKEN_FETCH_FAILED`（stage=network/timeout） |
| token 接口返回 ≥400 | `TOKEN_FETCH_FAILED`（status + body 片段） |
| token_path 取不到 | `TOKEN_FETCH_FAILED`（path） |
| 注入成功、业务接口重试一次后仍 401 | `HTTP_4XX` |

- 永不抛异常出 invoker，全部包成 `PluginError` 返给 LLM（沿用现有契约）。
- 重试硬上限 1 次，attempt 2 仍 401 直接 `HTTP_4XX`，杜绝循环。
- Trace 联动：`TOKEN_FETCH_FAILED` 落到现有调用日志，一眼区分「换 token 挂了」还是「业务接口拒了」。

---

## 8. 前端改动（`jm-agent-front`）

1. **`api/types.ts`**：`PluginAuthType` 加 `'OAUTH2' | 'TOKEN_FETCH'`。
2. **`PluginEditorPage.AUTH_TYPE_OPTIONS`**：加 `OAuth2 (client_credentials)` / `通用 Token 获取` 两项。
3. **auth_config 编辑**：把现有「API_KEY/HMAC 才显示 auth_config JSON 文本框」的条件扩到 `OAUTH2`/`TOKEN_FETCH`，`extra` 给示例 JSON，复用已有 JSON 合法性校验。**v1 用 JSON 文本框**。
4. **`CredentialPanel.FIELDS_BY_TYPE`**：
   - `OAUTH2`：固定 `client_id`（普通）、`client_secret`（密）。
   - `TOKEN_FETCH`：密钥键名用户自定义 → **自由 key-value 行编辑器**（加/删行、值用密码框），存成 `{key:value}` JSON。

---

## 9. 测试计划

后端 JUnit5 + OkHttp `MockWebServer`（同时模拟 token 接口与业务接口）：

- **`PluginTokenProvider`**：缓存命中不发请求；未命中 fetch+写缓存；TTL（expire_path 命中/缺失、`ms` 单位、−60s 余量、<5s 下限回退）；JSONPath 抽取；并发双重检查（两线程仅一次 fetch）；`invalidate` 删桶。
- **`OAuth2ClientCredentialsAuthApplier`**：form body 正确（grant_type/client_id/secret/scope）；`client_auth=basic` 走 Basic 头；注入 Bearer。
- **`GenericTokenAuthApplier`**：token_request 模板 `{{secrets.x}}` 渲染正确；token_path/expire_path 取值；按 inject 配置注入（含 `X-Token`/无前缀）。
- **`PluginHttpInvoker` 401 重试**：首次 401 → 重取重试 → 第二次 200；非 token-caching 不触发；第二次仍 401 → `HTTP_4XX`（不死循环）；token fetch 失败 → `TOKEN_FETCH_FAILED`。
- **`JsonPathUtil`**：回归 `PluginResponseExtractor` 行为不变。

---

## 10. 决议记录

| # | 决定 | 备注 |
|---|---|---|
| 1 | 同时支持标准 OAuth2 + 通用 TOKEN_FETCH | 通用是超集，底层共用引擎 |
| 2 | 缓存策略：主动 TTL + 401 被动兜底 + 分布式锁 | TTL 优先用响应过期字段、留 60s 余量、有下限 |
| 3 | 通用模式配置：复用模板 `{{secrets.x}}` + JSONPath 取值 + 可配注入 | 与现有体系一致 |
| 4 | 集成架构：方案 A（共享 `PluginTokenProvider` + 两薄 applier + 子接口 + invoker 重试） | 现有 4 个 applier 不改 |
| 5 | 凭证维持明文 | 与现有存储同级；加密单列 |
| 6 | TOKEN_FETCH 凭证用自由 key-value 编辑器 | v1 够用 |
| 7 | auth_config 用 JSON 文本框 | v1；结构化表单后续 |
