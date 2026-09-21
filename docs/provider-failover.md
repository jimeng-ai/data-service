# 对话 provider 的重试与 failover

> 适用：`data-server` 的流式对话出口（`/data/claude/messages`）。
> 配置位置：**Nacos 的 `data-server.yml`**，不是仓库里的文件。

## 它解决什么

改造前这条路上**一次重试都没有**：上游返回一个 503「Service is too busy」，整轮就报废——
哪怕模型已经跑完 5 步工具、数据全拿到了，只差最后一次总结。而且这次失败在
`ai_model_call_log` 里还被记成 `http_status=200, call_status=1`，事后连「今天失败了几次」
都答不出来。

现在是两层：

1. **同一家重试**：429 / 5xx / 连不上 → 退避重试（默认共 3 次尝试）。
2. **换一家（failover）**：本家重试用尽 → 按 `ai.chat-fallback-providers` 顺序换下一家。

两层都遵守同一条硬边界：**这一轮只要已经往前端吐过字，就既不重试也不换家**。
SSE 的 delta 发出去就收不回来，重发会让用户把同一段话看两遍——比直接报错更糟，
因为它静默地产出了错误内容。503 恰好是在上游**接收**阶段就被拒的，一个字都还没吐，
正落在可救的区间里。

用户主动点「停止」同样会触发底层的 `onFailure`，但那不是故障：不重试、不换家、不报错。

## 配置：DeepSeek 官网为主，火山引擎兜底

```yaml
ai:
  provider: deepseek            # 主 provider（决定 chat/embedding/rerank 全套）
  chat-fallback-providers:      # 对话失败时按顺序尝试；只影响 chat 流式
    - volcengine

providers:
  deepseek:
    base-url: https://api.deepseek.com
    api-key: ${DEEPSEEK_API_KEY}
    chat:
      protocol: openai          # 上游说 OpenAI 协议
      entry-protocol: anthropic # 对平台内部装成 anthropic
      model: deepseek-chat
      max-tokens: 8192

  volcengine:                   # 备用：只需要 chat 段
    base-url: https://ark.cn-beijing.volces.com/api/v3
    api-key: ${VOLC_ARK_API_KEY}
    chat:
      protocol: openai
      entry-protocol: anthropic # ★ 必须与主一致，见下
      model: <在火山方舟控制台开通的模型 ID 或接入点 ID>
      max-tokens: 8192
```

### 三条容易配错的地方

**① API key 绝不写进源码。** 用 `${VOLC_ARK_API_KEY}` 引环境变量，或直接填在 Nacos 里
（Nacos 本身不进 git）。这个仓库在 jwt.secret 上吃过一次亏：硬编码过的值**永久留在 git
历史里**，只能整个作废。key 泄露过就去控制台轮换，别想着「先用着」。

**② `entry-protocol` 必须与主 provider 一致。** 技能和内置工具的定义是在整轮开始时按
**主** provider 的协议形状注入进请求体的，换家只换「发给上游时怎么转」，不会重新注入。
协议不一致的备用换上去只会得到一个 400。不一致时启动日志会 warn 并**跳过该备用**——
跳过而不是报错，是因为备用链是可用性增强，它配错不该把本来能用的主链一起拖垮；
但也绝不静默，否则你以为有兜底，真出事才发现从来没生效。

**③ `model` 要填火山那边的名字。** 同一个 DeepSeek 在两家的叫法不一样（官网是
`deepseek-chat`，火山是 `deepseek-v3-…` 或 `ep-…` 接入点 ID）。切了地址不改名字，
换来的是一个 400 而不是一次成功的兜底。平台会在切换时自动把 `model` 换成该 target 配的值，
所以**必须**在这里填对。

备用 provider **不需要**配 `embedding` / `rerank` / `contextualization`，也不需要在
`ProviderBeansConfig` 里注册 bean——failover 直接按配置构造上游目标。
（对照：`ai.provider` 指向的**主** provider 仍然必须四件套齐全，启动时 fail-fast 校验。）

## 可调参数

| key | 默认 | 说明 |
|---|---|---|
| `ai.retry.max-attempts` | `3` | 单家的最大尝试次数（含首次）。设 `1` = 关闭重试 |
| `ai.retry.backoff-base-ms` | `1000` | 退避基数，实际等待 = base << (n-1)，即 1s / 2s |
| `ai.chat-fallback-providers` | 空 | 备用 provider 名列表，按顺序尝试 |

## 切过去之后会切回来吗

**本轮不会**，下一条用户消息会重新从主开始。

主既然刚挂，同一轮里的下一次工具轮次大概率还是挂，来回横跳只会让日志和请求体反复变形。
而下一条消息重新从主开始，意味着主恢复后**自动回归**，不需要任何人工干预，也不需要
一个「什么时候该切回去」的健康度状态机。

## 怎么确认它真的生效了

失败不再是静默的，直接查日志表：

```sql
-- 今天失败了几次、为什么、重试了几次
SELECT provider, http_status, call_status, error_code, retry_count, COUNT(*)
FROM ai_model_call_log
WHERE create_time >= CURDATE()
GROUP BY provider, http_status, call_status, error_code, retry_count;
```

- 重试的每次物理尝试**各记一行**（失败那几行有真实的 `http_status` 与 `error_code`，
  且没有 usage，不影响计费统计），`retry_count` 标记这是第几次重试。
- 切换过 provider 时，同一轮里会看到 `provider` 列出现两个不同的值。

应用日志里对应两句：

```
模型流式调用失败，1000ms 后重试（第 1/2 次）connectionId=... error=HTTP 503 - ...
provider=deepseek 已重试用尽，切换到备用 provider=volcengine connectionId=... 末次错误=...
```

## 代码位置

- `AiConversationLoop#callStreamWithRetry` — 单家的重试循环
- `AiConversationLoop#shouldRetryStream` — 什么该重试（含「已吐字就不重试」那条）
- `AiConversationLoop.StreamAttempt#canFailover` — 什么该换家
- `GenericChatClient#streamTargets` — 主 + 备的目标链，以及三条跳过规则
- `AiConversationLoopRetryTest` — 上面每条判断各有一个用例钉住
