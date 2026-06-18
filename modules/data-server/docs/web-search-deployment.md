# 联网检索能力 — 生产部署说明

> 目的:把本次新增的「Agent 联网搜索 + 网页抓取(AnySearch)」能力部署到生产。
> 本文档自包含,面向**无上下文的执行者(人或 AI)**。日期:2026-06-18。

---

## 0. TL;DR(给部署执行者)

本次新增**两个相互独立的能力**,共用同一个搜索后端 AnySearch:

| # | 能力 | 作用通道 | 涉及仓库 | 触发条件 |
|---|---|---|---|---|
| **A** | 对话内置 web 工具 | **普通对话**(纯文字,每个 Agent) | `data-service` | 配了 `ai.web-search` 即全局对所有 Agent 生效 |
| **B** | 沙箱 web 工具 | **代码/文件 Agent**(带附件的对话,走沙箱) | `data-service` + `jm-agent-sandbox` | 配了 `agent.sandbox.web-search` 即对走沙箱的 run 生效 |

要让两者都生效,需要:**部署 `data-service` 新代码 + 部署 `jm-agent-sandbox` 新代码 + 在 Nacos 加两段配置 + 重启**。只要 A 的话,只需 `data-service` + `ai.web-search`。

部署后**默认行为**:配置缺省时一切与现状一致(能力关闭),所以可灰度、可回退。

---

## 1. 前置条件

1. **AnySearch 生产 API key**:`anysearch.com/console/api-keys` 申请。**不要**直接用开发用的 key,生产单独配。占位写作 `<ANYSEARCH_API_KEY>`(形如 `as_sk_...`)。
2. **出网连通性(关键)**:
   - 能力 A:**`data-service` 进程**需能出站访问 `https://api.anysearch.com`(search)+ **任意公网**(fetch 抓网页;内网地址由 SSRF 闸拦截)。
   - 能力 B:**`jm-agent-sandbox` 边车宿主进程**需能出站访问 `https://api.anysearch.com`(以及 fetch 的公网)。注意:web 工具跑在**边车宿主进程**,不在隔离容器里——容器仍保持气隙,无需给容器开任何出网口子。
3. **构建环境**:`data-service` 用 Maven + JDK 17;`jm-agent-sandbox` 用 **Node ≥ 22.18**(原生 TS type-stripping;Node 20/18 跑不了)。

---

## 2. 代码改动清单(均为本次新增/修改,需随版本一起上线)

> 当前两个仓库的改动**尚未提交**。部署前需先在各自仓库提交并按你们的发布流程上线。
> - `jm-agent-sandbox` 当前分支:`main`
> - `data-service` 当前分支:`feat/kb-upload-confirm`(改动文件与该分支其它在途改动不重叠,见下;**建议按你们规范挑出本功能相关文件单独成一个发布单元**)

### 2.1 `data-service`(能力 A + B 的服务端)

**新增(能力 A — 对话内置 web 工具):**
```
modules/data-server/src/main/java/com/jimeng/dataserver/ai/web/
  WebSearchProperties.java     # @ConfigurationProperties("ai.web-search")
  WebSearchService.java        # 调 AnySearch POST /v1/search
  WebFetchService.java         # 宿主侧抓取 + 抽正文(java.net.http.HttpClient)
  WebSsrfGuard.java            # fetch 的 SSRF 防护(拒私网/loopback/元数据/IP字面量)
  WebToolExecutor.java         # 实现 SkillToolExecutor,进现有派发注册表
  WebToolDefinitions.java      # web_search / web_fetch 工具定义
modules/data-server/src/test/java/com/jimeng/dataserver/ai/web/
  WebSsrfGuardTest.java        # 单测
  WebToolParseTest.java        # 单测
```

**修改(能力 A):**
```
modules/data-server/.../ai/conversation/AiConversationLoop.java
  - 注入 WebSearchProperties;runBlocking + runStream 各加:配了即对所有 Agent 注入
    web_search/web_fetch 工具,并把「裸 Agent(无 skill/plugin)」的工具循环短路闸打开。
modules/data-server/.../ai/conversation/AiConversationLoopTest.java  (测试构造器补参)
```

**修改(能力 B — 把沙箱 web 配置下发给边车):**
```
modules/data-server/.../ai/agent/exec/config/AgentSandboxProperties.java   # 加 webSearch 块
modules/data-server/.../ai/agent/exec/dto/SidecarRunPayload.java           # 加 WebSearch 字段
modules/data-server/.../ai/agent/exec/service/AgentExecService.java        # 配齐则随 payload 下发
```

### 2.2 `jm-agent-sandbox`(能力 B 的执行端)

**新增:**
```
src/web/ssrfGuard.ts          # SSRF 安全抓取 + HTML→文本(复用 docker/egress-proxy.mjs 的校验)
src/mcp/webTool.ts            # mcp__web__search + mcp__web__fetch 宿主侧 MCP 工具
docker/egress-proxy.d.mts     # 给复用的 resolveAndValidate 补类型(.mjs 仍零依赖)
test/ssrf-guard-test.mjs      # 单测
test/web-tool-test.mjs        # 单测
```
**修改:**
```
src/types.ts                  # WebSearchConfig + RunRequest.webSearch
src/sdkOptions.ts             # req.webSearch 存在时挂上两个工具
```

---

## 3. Nacos 配置改动(data-id: `data-server.yml`)

> 生产 Nacos 的命名空间/分组按生产环境填;以下是要**新增**的两段。**api-key 用生产 key,勿用开发 key。**

### 3.1 能力 A — 顶层 `ai:` 块下新增 `web-search`
```yaml
ai:
  # ...(已有 provider / system-prompt / 等保持不动)
  web-search:
    provider: anysearch
    base-url: https://api.anysearch.com
    api-key: <ANYSEARCH_API_KEY>
    max-results: 5
```

### 3.2 能力 B — `agent.sandbox:` 块下新增 `web-search`
```yaml
agent:
  sandbox:
    # ...(已有 base-url / llm / image-gen 等保持不动)
    web-search:
      provider: anysearch
      base-url: https://api.anysearch.com
      auth-token: <ANYSEARCH_API_KEY>
      max-results: 5
      auth-scheme: bearer
```

> 注意字段名差异:A 用 `api-key`,B 用 `auth-token`(各自映射不同的属性类,均已与代码对齐,照抄即可)。
> 缺省任一段 = 对应能力不启用,行为同现状。

---

## 4. 部署步骤

### 4.1 `data-service`
```bash
# 1) 校验(可选,建议)
mvn -pl modules/data-server -am test -Dtest='WebSsrfGuardTest,WebToolParseTest,AiConversationLoopTest'
# 2) 构建产物(Spring Boot fat jar)
mvn -pl modules/data-server -am clean package -DskipTests
#    产物:modules/data-server/target/data-server-1.0-SNAPSHOT.jar
# 3) 按生产发布流程替换 jar 并重启 data-server 进程
#    (启动需指向生产 Nacos;启动日志出现 "Started DataServerApplication" 即成功)
```

### 4.2 `jm-agent-sandbox`(仅能力 B 需要)
```bash
# 边车以源码方式运行(node src/server.ts),无需打包;部署 = 更新源码 + 重启进程。
# 1) 校验
npm run typecheck
node test/ssrf-guard-test.mjs && node test/web-tool-test.mjs   # 需 Node>=22.18
# 2) 按生产流程更新代码并重启边车进程(需 Node>=22.18 运行时)
#    若生产用容器镜像跑边车:重建镜像(注意单阶段 npm ci,保留 claude-agent-sdk 的 linux 平台二进制)。
```

### 4.3 Nacos
先发布 §3 两段配置(用生产 key),再重启上面两个服务,使其加载新代码 + 新配置。

---

## 5. 部署后验证

1. **能力 A(对话)**:前端对**任意 Agent**(含未绑 skill/plugin 的裸 Agent)纯文字提问一个需实时信息的问题(如「XX 公司最新融资」)。预期:模型调用 `web_search`(必要时 `web_fetch`),调用日志/Trace 出现 `web_search`/`web_fetch` 的 `TOOL_CALL` 步骤,回答引用联网结果。
2. **能力 B(沙箱)**:在对话里**带一个附件**触发沙箱 run,问需要联网的问题。预期:沙箱 SSE 出现 `mcp__web__search`/`mcp__web__fetch` 工具调用。
3. **SSRF 负向**:让模型尝试 `web_fetch http://169.254.169.254/latest/meta-data`(云元数据)。预期:返回 `blocked: ...`,不泄露内网、run 不崩。
4. **直连冒烟(可选,不经前端)**:从 data-service 主机
   ```bash
   curl -s -X POST https://api.anysearch.com/v1/search \
     -H "Authorization: Bearer <ANYSEARCH_API_KEY>" -H "Content-Type: application/json" \
     -d '{"query":"test","max_results":3}'
   ```
   返回 `{"code":0,...,"data":{"results":[...]}}` 即出网 + key 正常。

---

## 6. 回滚

- **最快回滚(关能力,不回退代码)**:删掉 Nacos 的 `ai.web-search`(关 A)和/或 `agent.sandbox.web-search`(关 B),重启对应服务 → 行为恢复到上线前,代码无副作用。
- **完整回滚**:回退两个仓库的本次提交,重新构建部署。

---

## 7. 生产注意事项 / 风险

1. **能力 A 是全局的**:配了 `ai.web-search`,**所有** Agent(乃至不带 agent_id 的裸 `/data/claude/messages` 调用)都会在每次对话注入 `web_search`/`web_fetch` 两个工具(略增 token、模型可能更频繁联网)。这是「每个 Agent 都支持」的预期取舍;若只想部分 Agent 用,需另做按 Agent 开关(本期未做)。
2. **SSRF 边界**:`WebSsrfGuard` 拦截 loopback/私网(10/172.16-31/192.168)/link-local/169.254 元数据/CGNAT/IP 字面量。**若你们生产内网服务使用公网段 IP**,这些不在拦截范围,需按需在 `WebSsrfGuard` 增补黑名单,或在网络层限制 data-service 的出站。
3. **fetch 的已知残留**:校验通过后由 HttpClient 自行再解析连接,存在窄 DNS-rebinding 窗口(沙箱侧用 IP-pin 关掉了,Java 侧 v1 未做)。可接受则上线,后续可补 IP-pin。
4. **密钥管理**:AnySearch key 在 Nacos 明文(与现有 302.ai key 同),按你们密钥规范处理;勿把 key 写进代码或提交到仓库。
5. **AnySearch 计费**:按次计费,本期未接 token/次数计费统计;关注用量。
6. **沙箱镜像构建坑(仅 B,若用容器跑边车)**:`docker/Dockerfile` 必须单阶段 `npm ci`,多阶段 `COPY node_modules` 会丢掉 `@anthropic-ai/claude-agent-sdk-linux-*` 平台二进制,导致 CLI 静默失败。

---

## 8. 设计/实现参考(本仓库内)

- `docs/superpowers/specs/2026-06-18-chat-web-tools-design.md` — 能力 A 设计
- `docs/superpowers/specs/2026-06-18-sandbox-web-search-design.md` — 能力 B 设计
- `docs/superpowers/plans/2026-06-18-sandbox-web-search.md` — 能力 B 实现计划
