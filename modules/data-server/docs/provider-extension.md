# AI Provider 切换与扩展

`data-service` 的 AI 调用链（聊天 / 嵌入 / Rerank / 上下文化）通过 `ai.provider` 顶层开关在多家代理商之间切换。本文给出**切换流程**、**新增 provider 步骤**与**Nacos 迁移说明**。

## 一、当前已接入的 provider

| provider 名 (yml key) | chat 协议 | rerank 协议 | 备注 |
|---|---|---|---|
| `openrouter` | anthropic | cohere | 默认；`/v1/messages` + `cohere/rerank-4-pro` |
| `302ai` | anthropic | qwen3 | `/v1/messages` + `/v1/reranks`（带 `instruct`） |

切换：把 `ai.provider` 改成对应 key，重启 data-server。

## 二、切换 provider 的 checklist

1. **改配置**：Nacos 上的 `data-server.yml` 把 `ai.provider` 设为目标值（如 `302ai`）。
2. **检查 embedding.dims**：若新 provider 的 `providers.<name>.embedding.dims` 与当前 ES 索引维度**不同**，必须先 reindex（见第四节）。
3. **检查 chat.protocol**：新 provider 的 `chat.protocol` 决定保留 `/data/claude/messages` 还是 `/data/openai/chat/completions` 入口可用：
   - `protocol=anthropic` → `/data/claude/messages` 可用；打到 `/data/openai/...` 会 400。
   - `protocol=openai` → `/data/openai/chat/completions` 可用；打到 `/data/claude/...` 会 400。
4. **重启 data-server**。启动日志会打印当前激活的 provider 与各能力模型，确认对得上。
5. **冒烟**：聊天 / RAG 上传一份小文档 / 检索 → 看日志里 HTTP target 是否指向新 provider 的 base-url。

## 三、新增一个 provider（5 步）

以接入虚构的 `siliconflow` 为例。

### 1. 在 yml 加配置块

`bootstrap.yml`（或 Nacos `data-server.yml`）的 `providers:` 下追加：

```yaml
providers:
  siliconflow:
    base-url: https://api.siliconflow.cn/v1
    api-key: ${SILICONFLOW_KEY}
    timeout: 30s
    chat:
      protocol: openai                 # anthropic | openai
      model: deepseek-ai/DeepSeek-V3
      max-tokens: 8192
    embedding:
      protocol: openai
      model: BAAI/bge-large-zh-v1.5
      dims: 1024                       # 与 ES 索引维度一致
    rerank:
      protocol: cohere                 # 若响应是 cohere 风格：results:[{index, relevance_score}]
      model: BAAI/bge-reranker-v2-m3
      endpoint-path: /rerank
    contextualization:
      text-model: deepseek-ai/DeepSeek-V3
      image-model: deepseek-ai/DeepSeek-VL
      max-output-tokens: 200
      use-prompt-cache: false          # protocol=openai 时无效
```

### 2. 判断 rerank 协议

读一遍 provider 文档的 rerank 响应示例：

- 顶层 `results: [{index, relevance_score}]` → 用 `cohere`，**零代码**。
- DashScope/Qwen 风格（`output.results: [...]` 或字段名 `score`）→ 用 `qwen3`，**零代码**。
- 都不像 → 新建 `XxxRerankClient implements RerankClient`，参考现有 `CohereRerankClient` / `Qwen3RerankClient`；然后在 `ProviderBeansConfig.newRerankByProtocol` 的 switch 里加一个 case。

### 3. 加 @Bean 注册

`ProviderBeansConfig.java` 加四个方法：

```java
@Bean(name = "siliconflow-chat")
public ChatClient siliconflowChat(...) {
    return newChat("siliconflow", ...);
}
@Bean(name = "siliconflow-embedding") public EmbeddingClient ... { return newEmbedding("siliconflow", ...); }
@Bean(name = "siliconflow-rerank")    public RerankClient    ... { return newRerankByProtocol("siliconflow", ...); }
@Bean(name = "siliconflow-contextualization") public ContextualizationClient ... { return newContextualization("siliconflow", ...); }
```

参数签名照着 `openrouter` 的方法复制即可。

### 4. 切总开关

`ai.provider: siliconflow`

### 5. 若 embedding.dims 变了，先 reindex（见下节），再重启

启动期 `ProviderRegistry.validate()` 会 fail-fast 校验 `providers.siliconflow.*` 必填字段；缺什么报什么。

## 四、维度迁移（必做）

切 provider 时若 `embedding.dims` 与当前不同（如 1536↔1024）必须 reindex，否则向量字段维度不匹配会查询失败。

1. **暂停 ingestion**：让 RabbitMQ `rag.ingestion` 队列停止消费（停 data-server 或取消订阅）。
2. **删除索引**：
   ```bash
   curl -X DELETE "http://localhost:9200/kb_chunks"
   ```
3. **改配置 + 重启**：把 `ai.provider` 切到目标值，启动 data-server。`EsIndexInitializer` 在 `@PostConstruct` 阶段读 `providerRegistry.embedding().dims()` 重新建索引。
4. **回灌**：从 MinIO `rag-documents` 桶里把所有文档重新触发 ingestion（走 `IngestionQueueProducer`）。Embedding cache key 已带 provider + model 前缀，新 provider 的 embedding 不会命中老缓存。

## 五、Nacos `data-server.yml` 迁移对照表

旧 key 已全部删除；如 Nacos 上还有，请同步改。

| 旧 key | 新 key |
|---|---|
| `ai-api.claude.base-Url` | `providers.openrouter.base-url` |
| `ai-api.claude.api-key` | `providers.openrouter.api-key` |
| `ai-api.claude.model` | `providers.openrouter.chat.model` |
| `ai-api.claude.max-tokens` | `providers.openrouter.chat.max-tokens` |
| `ai-api.openai.base-Url` | `providers.openrouter.base-url`（若仍用 OpenRouter）|
| `ai-api.openai.model` | `providers.openrouter.chat.model`（chat.protocol=openai 时）|
| `rag.openrouter.base-url` | `providers.openrouter.base-url` |
| `rag.openrouter.api-key` | `providers.openrouter.api-key` |
| `rag.openrouter.embedding-model` | `providers.openrouter.embedding.model` |
| `rag.openrouter.embedding-dims` | `providers.openrouter.embedding.dims` |
| `rag.openrouter.rerank-model` | `providers.openrouter.rerank.model` |
| `rag.contextualization.text-model` | `providers.openrouter.contextualization.text-model` |
| `rag.contextualization.image-model` | `providers.openrouter.contextualization.image-model` |
| `rag.contextualization.max-output-tokens` | `providers.openrouter.contextualization.max-output-tokens` |

`rag.contextualization.enabled` 与 `rag.contextualization.prompt-cache-ttl-seconds` 保留在 `rag.*` 下不变。
