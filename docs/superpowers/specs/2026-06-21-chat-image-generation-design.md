# 对话 Agent 生图能力接入 — 设计文档

- 日期：2026-06-21
- 分支：`feat/chat-image-gen`（data-service）、待建 `feat/chat-image-gen`（jm-agent-front）
- 状态：设计已与用户确认，待写实现计划

## 背景与问题

产品里的对话助手（如「全能助手」）被要求生图时回复"我没有图像生成能力"。调查发现：**生图能力代码层面是完整实现的，但只接到了「代码执行 Agent」路径，没接到「对话 Agent」路径。**

两条路径架构不同：

| 路径 | 入口 | 工具执行方式 | 生图 |
|---|---|---|---|
| 代码执行 Agent | `AgentExecService.streamExec()` | 转发给 sidecar(:8088) 的 Claude Agent SDK，MCP 工具 | ✅ 有（`AgentExecService.java:186-195` 把 `agent.sandbox.image-gen` 配置塞进 `SidecarRunPayload.ImageGen`，sidecar `src/mcp/imageGenTool.ts` 暴露 `generate_image` MCP 工具） |
| 对话 Agent | `AiConversationLoop.runBlocking/runStream` | **纯 Java 工具循环**，直接调上游 LLM（`AiConversationLoop.java:116` POST），工具在 Java 进程内执行 | ❌ 无 |

对话路径的工具清单只来自三处：平台内置 Skill（`rag-knowledge`/`gaode-poi`/`design-system`）、开关型内置工具（`web_search`/`web_fetch`/`skill_search`，`AiConversationLoop.injectBuiltinTools` `:70-83`）、Agent 绑定的 Plugin。`generate_image` 既不是 Skill 也不是 Plugin，所以任何对话 Agent 都拿不到它，LLM 请求体里没这个工具 → 模型如实回答"没有"。

**缺口定位：对话路径缺一个生图工具。** 不是配置漏了，是架构上对话路径没打通。

## 目标

让所有对话 Agent 默认具备文生图能力：模型可在对话中自主调用 `generate_image`，生成的图片以独立卡片展示在对话 UI 里。

## 非目标（YAGNI，v1 不做）

- 图生图 / 图片编辑（带入参图片）
- kling-o3 异步任务制 provider（当前 Nacos 配的是 seedream 同步）
- 按 Agent 单独勾选生图开关（沿用"配齐即全局开启"）
- 图片内容审核
- 把对话路径整体迁到 sidecar（改动过大，明确不选）

## 总体方案

在对话路径（纯 Java 工具循环）里**新增一个内置工具 `generate_image`**，实现方式与 `RagSkillToolExecutor` 完全同构。**不碰 sidecar、不改对话核心循环、不改对话消息模型。**

选这个方案而非"把对话接进 sidecar"的理由：对话路径已经是全 Java 的工具循环，新增一个 `SkillToolExecutor` 是最小改动、与现有架构完全一致；接 sidecar 要把整个工具循环搬到 TS 侧并重写租户/插件权限逻辑，成本和故障面都大得多。

## 组件设计

### 后端（data-service / modules/data-server）

新增 3 个文件 + 2 处接线：

1. **`GenerateImageToolExecutor`**（新，`ai/image/` 包）
   - 实现 `SkillToolExecutor` 接口，处理工具名 `generate_image`，仿 `RagSkillToolExecutor.java:64-122`。
   - 流程：解析入参 → 调 `ImageGenClient` 生图 → 下载图片字节 → 存 MinIO → 组装 output `{urls:[...], model, size, count}` 返回。
   - 注册进 `SkillToolExecutorRegistryService`（`:24-59` 的 `executeAll` 会按工具名路由到本执行器）。

2. **`ImageGenToolDefinitions`**（新）
   - 提供 `generate_image` 的工具 schema，仿 `WebToolDefinitions.java:21-59` 的 `SkillToolDefinition` 模型，由 adapter `convertToolDef`（`OpenAiProtocolAdapter.java:64-66` → `toOpenAiTool`，Claude 侧对应实现）转成协议格式。
   - schema：
     ```json
     {
       "name": "generate_image",
       "description": "根据文字描述生成图片。当用户要求画图/生成图片/出图时调用。",
       "parameters": {
         "type": "object",
         "properties": {
           "prompt": {"type": "string", "description": "图片内容的文字描述"},
           "count": {"type": "integer", "minimum": 1, "maximum": 4, "default": 1},
           "size": {"type": "string", "enum": ["1024x1024","1024x1536","1536x1024"], "default": "1024x1024"}
         },
         "required": ["prompt"]
       }
     }
     ```

3. **`ImageGenClient`**（新）
   - provider 策略接口。v1 实现 **seedream**（当前 Nacos 配置）+ **openai**（同步兜底）；结构上预留 kling-o3 异步，但 v1 不实现。
   - seedream/openai 都是同步 REST：POST 到上游 → 解析返回的图片 URL 列表。调用细节对齐 sidecar `src/mcp/imageGenTool.ts`（seedream `:143-209`，openai `:219-294`），保证与 exec 路径出图一致。

**接线①** `AiConversationLoop.injectBuiltinTools`（`:70-83`）：在注入 web/skill 工具的同款位置，按 imageGen 配置完整性注入 `generate_image` 定义。

**接线②** `SkillToolExecutorRegistryService`：注册 `GenerateImageToolExecutor`。

### 配置复用

直接注入现有 `AgentSandboxProperties` bean（`ai/agent/exec/config/AgentSandboxProperties.java`），读其中的 `ImageGen`（`:48-58`：`provider/baseUrl/authToken/model/authScheme/batchConcurrency`）。

- **一处密钥**：对话与代码执行共用 `agent.sandbox.image-gen`，换 provider/换 key 只改 Nacos 一处。
- **闸门**：沿用现有"配齐即启用"约定（与 web 工具同款）——`baseUrl`+`authToken`+`model` 三者齐全 → 对话工具自动对所有 Agent 开启；任一为空 → 不注入 `generate_image`，不新增独立开关。
- 当前 dev-nacos 实配：`provider: seedream`、`base-url: https://api.302.ai/v1`、`model: doubao-seedream-5-0-260128`。

### 图片存储

上游（302.ai）返回的图片 URL 会过期，聊天记录里的图不能因此损坏。

- **下载图片字节 → 复用 `RagMinioStorageService`（`:70-87` upload）存进 MinIO → 返回长期可访问 URL**（presigned 或固定对象路径，与 exec 产物一致）。
- 注意二进制下载/上传不要被 `GlobalResponseHandler` 包成 JSON（见仓库既有踩坑约定）。这里是服务端内部 upload，不经 controller 出参，无此风险；但若后续加"原图下载"端点，必须 `void`+`OutputStream` 写出。

### 前端展示（jm-agent-front，独立图片卡片）

- 后端把图片 URL 放进 `tool_result` 事件 output（对话循环本就给每个工具发 `tool_result`，`AiConversationLoop.java:405-423`）。
- 前端识别 `tool_result.name == "generate_image"` 的事件 → 渲染**专门的图片卡片**（缩略图、点开看大图、下载），而非默认的"工具调用"折叠块。
- 前端改动：新增一种 `tool_result` 渲染分支；其余对话消息模型不变。
- 验证：前端必须在 docker `:8082` 重建后访问验证（jerry 实际走 `:8082`，非 vite `:5173`）。

### 计费与 Trace

- 每次生图记一笔 `ai_model_call_log`，`biz_type` 新增枚举值 `image_gen`（按「模型×功能」记账，复用 `BizTypeContext` ThreadLocal）。运营平台模型花费页可据此看生图用量。
- 在 `ai_trace_step` 埋一步（工具类型），记 prompt / 模型 / 张数 / 耗时（复用 `TraceRecorder`）。
- 流式场景下生图发生在工具循环线程内，上下文已由 `MdcAsyncSupport.wrap` 在该线程建立，租户/用户/trace 正常；执行器内不再单独处理。

## 数据流

```
用户:"画一只胖橘猫"
  → AiConversationLoop 把 generate_image 定义随请求体发给 LLM
  → LLM 决定调用 generate_image{prompt:"胖橘猫...", count:1, size:"1024x1024"}
  → AiConversationLoop 提取 tool_use → SkillToolExecutorRegistryService 路由
  → GenerateImageToolExecutor:
       ImageGenClient.seedream(prompt,...) → 302.ai → 图片URL
       下载字节 → RagMinioStorageService.upload → minioUrl
       记 ai_model_call_log(biz_type=image_gen) + ai_trace_step
       返回 output {urls:[minioUrl], model, size, count}
  → AiConversationLoop 发 tool_result 事件 {name:"generate_image", output:{urls:[...]}}
  → 前端渲染图片卡片
  → tool_result 追加回 messages，LLM 生成最终文字回复（"给你画好了~"）
```

## 错误处理

- 上游生图失败 / 超时：执行器返回 `status=error` + 原因，`tool_result` 携带错误，LLM 据此向用户说明（"生图失败，请重试"），不中断整轮对话。
- imageGen 配置缺失：工具根本不注入，模型不会调用（行为同当前，回答没有该能力）。
- MinIO 上传失败：降级返回上游原始 URL（标注可能过期），并记 warn 日志。

## 测试

- 单测：`ImageGenClient` 的 seedream/openai 请求拼装与响应解析（mock 上游）；`GenerateImageToolExecutor` 的入参校验与 output 组装。
- 端到端（jerry 硬性要求，必须实跑+查库）：
  1. 起本地栈，对话端发"帮我生成一个胖橘猫"，观察 Redis `chat:run:{runId}` 写入与 `tool_result` 事件。
  2. 查 MinIO 有对象、查 `ai_model_call_log` 有 `biz_type=image_gen` 记录、查 `ai_trace_step` 有埋点。
  3. `:8082` docker 重建后，浏览器看到图片卡片正常渲染、点开大图、下载可用。
  - 本地 302.ai DNS 污染：sandbox 走 4780 代理；但本执行器是 data-server 直连 302.ai，需确认 data-server 出口能到 302.ai（必要时同样配 `-Dhttps.proxyHost`），否则本地生图会超时——这是本地网络绕行，生产直连正常。

## 涉及文件清单（参考锚点）

- 新增：`ai/image/GenerateImageToolExecutor.java`、`ai/image/ImageGenToolDefinitions.java`、`ai/image/ImageGenClient.java`(+ provider 实现)
- 改：`AiConversationLoop.java`（`injectBuiltinTools` `:70-83`）、`SkillToolExecutorRegistryService.java`（`:24-59`）、`biz_type` 枚举
- 复用：`AgentSandboxProperties.java`(`ImageGen` `:48-58`)、`RagMinioStorageService.java`(`:70-87`)、`BizTypeContext`、`TraceRecorder`
- 参考同构件：`RagSkillToolExecutor.java`(`:64-122`)、`WebToolDefinitions.java`(`:21-59`)、`HttpPluginToolExecutor.java`(`:44-66`)、sidecar `src/mcp/imageGenTool.ts`
- 前端：jm-agent-front 对话 `tool_result` 渲染新增 `generate_image` 图片卡片分支
