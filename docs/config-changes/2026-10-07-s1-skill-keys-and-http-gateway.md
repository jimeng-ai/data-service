# 一切皆可 skills · S1：统一解析器、技能里的 key、通用 HTTP 网关

- 依据：`jm/docs/superpowers/specs/2026-10-05-everything-is-skills-design.md`（S1），实施计划 `jm/docs/superpowers/plans/2026-10-07-everything-is-skills-s1-keys-and-http-gateway.md`。
- 版本：S1 在 data-service、jm-agent-sandbox、jm-agent-front 的 es-s1 分支已实现；Task13 合并与 tag 后回填各仓库版本。尚未上线。
- 改动一句话：skill 可以带 key 和要访问的域名；key 发布时被提取、加密存 `skill_secret`；模型在对话平面和沙箱里用
  `http_request`、沙箱脚本用 `$JM_HTTP`，三条路都只经过 data-service 的同一个网关。

## 一、上线前：生产库（ds-mysql），先于 data-service 上线

按顺序执行这两份 DDL（仓库 `modules/data-server/src/main/resources/db/migration/`）：

1. `V20261007__skill_manifest.sql`：`ai_skill`、`ai_skill_version` 各加一列 `manifest`。
2. `V20261007b__skill_secret.sql`：新建 `skill_secret` 表。

★ 必须先执行再上线 data-service：实体已经映射了 `manifest` 列，列不存在时 `ai_skill` 的每一次查询都会报错，所有企业技能会从对话里消失。

自检：

```sql
SHOW COLUMNS FROM ai_skill LIKE 'manifest';
SHOW COLUMNS FROM ai_skill_version LIKE 'manifest';
SHOW CREATE TABLE skill_secret;
```

## 二、生产 Nacos `data-server.yml`（新键，只写键名）

```yaml
http-gateway:
  callback-base-url: <边车宿主可达的生产网关根地址，含 /data>
  # allow-private:                              # SaaS 不配；私有化部署才填客户内网的 CIDR、单个 IP 或域名
```

- 没配 `callback-base-url` 时：对话平面照常可用；沙箱里不下发 `http_request` 和 `$JM_HTTP`，日志里有一行 debug「没配 http-gateway.callback-base-url」。
- 其余项都有代码默认值，不用配：连接超时 10 秒、最多 3 跳、工具回包 2 MiB、脚本回包 50 MiB；请求体工具 1 MiB、脚本 20 MiB。代理接收请求的 50 MiB 安全上限不代表全链路支持 50 MiB 上传。

## 三、核对（已有配置，确认一下）

- data-server 的 `connection.credential-key`（生产由环境变量 `CONNECTION_CREDENTIAL_KEY` 提供，见 `docs/deploy-secrets.md`）。缺了它，带 `secrets.json` 的技能发布会失败，报「平台没有配置加密密钥」。
- 生产 `gateway.yml` 里没有 `spring.cloud.gateway.httpclient.response-timeout`，或者它不小于 200 秒。否则长请求会被网关截断：技能最长可以申请 180 秒。
- 回调前缀 `/data/internal/http-gateway/**` **不能**加进 `gateway.yml` 的 `ignore.auth.whitesUrl`，也不能加进 `tenant.skip-paths`。理由与连接器回调相同：加了就不注入身份头，每个回调都会 403。

## 四、沙箱部署

- 出网代理新增环境变量 `EGRESS_HTTP_GATEWAY_URL`。部署流程的默认值是 `http://host.docker.internal:20011/data/internal/http-gateway/script`，可用仓库变量 `EGRESS_HTTP_GATEWAY_URL` 覆盖。
- 自检：`docker inspect jm-egress-proxy --format '{{range .Config.Env}}{{println .}}{{end}}' | grep EGRESS_HTTP_GATEWAY_URL` 有值。
- 代理从这一版起 `/llm` 只放行 `LLM_ROUTES` 指定的 `POST /llm/v1/messages` 和 `POST /llm/v1/messages/count_tokens`；CONNECT 仍只放本次模型域名。上线后跑一次带附件的对话（走沙箱），确认模型照常回答。

## 五、上线顺序

DDL → data-service → jm-agent-sandbox → jm-agent-front，一次一个仓库。

## 六、上线后验证

1. 在构建器里做一个带 key 的技能并发布。详情页应显示「含加密保存的 key：名字 → 域名」；`skill_secret` 有对应行；MinIO 的 bundle 里没有 `secrets.json`，文件里的 key 原文都成了 `{{名字}}`。S1 自动推断中，只有 SKILL.md（可加 secrets.json / 根 evals）的包为 PROMPT；references 等伴随文件仍会推断为 DOER，脚本专属推断属于 S2a。
2. 把它绑到一个 Agent 上对话，让它调一次接口。客户系统收到真值。以下位置都 grep 不到 key 原文：
   - 对话记录；
   - `ai_model_call_content`；
   - ES `data-server-*`。
3. ES 里能查到审计日志：`logger_name: "jm.http-gateway.audit"`，字段都以 `hgw_` 开头。

## 七、回滚

- data-service 回滚到上一个 tag。新增的 `skill_secret` 表，以及现有 `ai_skill` / `ai_skill_version` 表各自新增的 `manifest` 列可以留着，老代码不读它们。
- 沙箱回滚到上一个 tag。老代理不认 `EGRESS_HTTP_GATEWAY_URL`，会忽略它。
- 回滚之后，已经发布的带 key 的技能在对话平面里不再有 `http_request`。key 仍加密留在 `skill_secret`，重新上线后恢复。

## 八、本地验证配置与复现

仅本地开发 Nacos（localhost:8849、开发 namespace、DEFAULT_GROUP 的 data-server.yml）已按备份 → 最小追加 → YAML 解析 → 发布 → 读回逐字比对保留：

```yaml
http-gateway:
  callback-base-url: http://localhost:10011/data
  allow-private:
    - 192.168.10.70/32
```

本地代理设置 `EGRESS_HTTP_GATEWAY_URL=http://host.docker.internal:10011/data/internal/http-gateway/script`。LAN 地址变化时重查 en0 并更新 /32；loopback / link-local 始终拒绝，不能用回环代替真实目标。生产配置仍只按前文键名由运维填写。

两项回调配置分别供宿主与代理容器使用：Nacos callback-base-url 缺失时不派发 httpContext，沙箱没有新工具与 JM_HTTP；代理 EGRESS_HTTP_GATEWAY_URL 缺失时，带 httpContext 的 MCP 工具仍可工作，但脚本 /http/* 会失败。

回调的宿主超时为技能总超时加 15 秒；最长技能 180 秒，网关/代理运维预算至少 200 秒。工具文本由网关先进行 HTML/UTF8 转换再最终抹值；后续调用层不得再次转换。脚本回包保持字节内容。

本地证据在实施计划的 Task12 ledger / report。真实配置模型与真实 CLI 验证和确定性补充检查须分开记录；标准镜像构建若被网络阻断，本地已有基础镜像加当前代码的验证不等同于全新基础镜像构建。

私网白名单中的 IPv4-mapped 地址请使用 IPv4 CIDR 写法（例如 `10.1.0.0/16`）；不支持 mapped IPv6 `/120` 写法，非法输入会拒绝启动。固定云元数据地址 `fd00:ec2::254`、`fd20:ce::254`、`100.100.100.200` 及支持的内嵌形式永不受私网白名单放行。

最终安全修复边界：模型正文和审计同时检查实际 JSON 表示及后续截断，存在已知 key 重建风险时扣留该字段或回包。脚本正文仍保留原始二进制语义。

每次工具/脚本回调除校验 JWT 和 Redis grant 外，还读取 `agent_exec_run`，只接受同租户、同用户、同 Agent、同执行 ID 的 `RUNNING` 行。执行结束先确认 `SUCCESS` / `FAILED` 落库，再删 Redis grant。清理失败保留重试（每秒最多 50 项，直到确认或原凭证 TTL 到期），终态行在所有实例拒绝旧凭证。数据库终态写入与 Redis 删除同时失败时，结束尚未确认；恢复前本实例拒绝，其他实例仍以其读取到的持久化状态为准。该双存储写失败窗口不能宣称已全局即时吊销，以重试或原 JWT 到期收敛。

YAML 主解析和宽松重试都限制源文本 512 Ki UTF-16 单位、节点 16384、全部别名 256、嵌套 50、单次解码输出 1 MiB；collection 别名仍保留库的 50 次限制。重复/merge 覆盖前的源节点及重试原标量均核对当前和上一版 key，语法错误不包含原 YAML。
