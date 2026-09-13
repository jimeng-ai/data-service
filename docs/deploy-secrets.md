# 部署密钥清单（fail-closed，配错就起不来）

这套系统里有三处密钥，**全部没有默认值**。这是刻意的：曾经的形状是"配了才安全、不配就默认
不安全"，而它真的在线上出过事（见 `jm-agent-sandbox/src/auth.ts` 顶部注释）。所以现在统一
改成——**没配就拒绝启动 / 拒绝服务**，宁可显式坏掉，不要静默敞开。

> 结论先行：**上生产之前，下面 3 项必须全部落实。** 漏任何一项，对应的服务会明确地起不来或
> 拒服务，而不是带病运行。

---

## 1. `jwt.secret` —— 签发与验签 JWT

| | |
|---|---|
| 放哪 | 生产 Nacos，**`data-server.yml` 和 `gateway.yml` 各一份** |
| 约束 | **两份必须完全相同**；≥32 字符 |
| 漏配 | data-server 与 gateway **都拒绝启动** |
| 配成两个不同值 | 服务能起，但**所有人都登录不上**（签发方与验签方对不上） |

```bash
SECRET=$(openssl rand -base64 48)
# 分别写进两个 data-id 的 yml：
#   jwt:
#     secret: "<SECRET>"
```

**轮换（不踢人下线）**：gateway 另有 `jwt.additional-secrets`（逗号分隔，**仅验签**）。
1. 先把【新】密钥加进 gateway 的 `additional-secrets`（签发仍用旧的）→ 网关同时接受新旧；
2. 再把 data-server 的 `jwt.secret` 换成新密钥 → 新登录发新令牌，旧令牌仍可验；
3. 等旧令牌自然过期（12h）后，把 gateway 的 `jwt.secret` 改成新值并清空 `additional-secrets`。

不在乎踢人下线就跳过，两边直接同时换。

> ⚠️ 这个密钥**曾经硬编码在源码里**（common-core 与 gateway 各一份副本），已随 git 历史
> 永久泄露。那个旧值必须视为作废，**任何环境都不要再用**。

---

## 2. `SANDBOX_SERVICE_TOKEN` —— data-server 调边车的内部鉴权

| | |
|---|---|
| 放哪 | **两处，同值**：① GitHub 仓库 `jimeng-ai/jm-agent-sandbox` 的 Actions secret（部署时写进 launchd plist）；② 生产 Nacos `data-server.yml` 的 `agent.sandbox.service-token` |
| 漏配边车侧 | 边车 **503 拒绝服务**（不是 401——错在服务端配置） |
| 两边不一致 | 每次派发 **401**，Agent 运行/技能评测全挂 |

```bash
TOKEN=$(openssl rand -base64 36 | tr -d '\n=+/' | cut -c1-40)
gh secret set SANDBOX_SERVICE_TOKEN --repo jimeng-ai/jm-agent-sandbox --body "$TOKEN"
# 再把同一个值写进生产 Nacos data-server.yml：
#   agent:
#     sandbox:
#       service-token: <TOKEN>
```

> **必须两边一起设**。只设一边同样是坏的，只是坏法不同（503 vs 401）。
>
> 边车监听 `0.0.0.0:8088`。这台机器同时在局域网与 Tailscale 上，**无鉴权 = 任何网络可达者
> 都能用任意 tenantId、任意 LLM 端点在容器里执行代码**。本地开发也不要再用
> `SANDBOX_ALLOW_NO_AUTH=true` 绕过（`start-local.sh` 已改为从 Nacos 取同一个 token）。

---

## 3. `CONNECTION_CREDENTIAL_KEY` —— 外部连接凭据的主密钥

| | |
|---|---|
| 放哪 | Nacos `connection.credential-key`（或同名环境变量） |
| 约束 | base64，**解码后必须恰好 32 字节**（AES-256） |
| 漏配 | 连接凭据无法读写、该功能不可用；**绝不退化成明文存储** |
| 换了 key | 用旧 key 加密的历史凭据解不开（密文仍在库里，换回旧 key 可恢复），需在「外部连接」页面重新录入凭据 |

```bash
openssl rand -base64 32
```

---

## 自检

```bash
# 1) 两个应用能起来 = jwt.secret 配对成功
grep -E "JWT (签名|验签)密钥已" <各自日志>

# 2) 边车强制鉴权（而不是 AUTH DISABLED）
curl -s localhost:8088/healthz            # 期望 ok，且日志有 service-token auth: ENFORCED
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' \
     -d '{}' localhost:8088/sandbox/run   # 期望 401（不是 400/200）

# 3) 连接凭据加密已启用
grep "连接凭据加密已启用" <data-server 日志>
```

**光验"坏请求被拦住"不够**：必须同时验"好请求还能通过"。本地就踩过一次——边车 token 配成了
一段 YAML 行内注释，鉴权照常拦住匿名请求，但 data-server 的正常派发也一起 401 了，
只有跑一次真实派发才暴露出来。
