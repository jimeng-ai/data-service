# 2026-09-27 AI 生成 Skill 改为「在沙箱里原样跑 skill-creator」

> 本目录的约定见 `2026-09-21-enable-connector-agent-plane.md` 开头：改了数据库结构或运行时配置就留一份记录，
> 只写键名、语义与回滚办法，绝不写任何真实密钥值。设计见工作区
> `docs/superpowers/specs/2026-09-27-skill-builder-skill-creator-design.md`。

## 一、数据库（已在 dev 执行；生产未执行）

| 文件 | 内容 | 漏跑时的症状 |
|---|---|---|
| `db/migration/V20260927__skill_builder_session.sql` | 新表 `skill_builder_session`（会话 → 对话 / 工作区前缀 / 目标 skill）、`ai_skill_version`（每个发布版本的 bundle） | 应用照常 `Started`；**构建器的所有接口 500**（`Table ... doesn't exist`），发布拿不到版本记录 |

两张表都是租户隔离表，已登记进 `JimengTenantLineHandler.TENANT_AWARE_TABLES`（代码里，不需要配置）。
执行方式同其它 DDL：`docker exec -i <mysql容器> mysql --default-character-set=utf8mb4 -uroot -p... data-server < <文件>`。
`CREATE TABLE IF NOT EXISTS`，重复执行无害。

## 二、Nacos（`data-server.yml`）

**没有必须新增的键**：`skill.builder.*` 全部有代码默认值（`SkillBuilderProperties`）。需要调时再加：

| 键 | 默认 | 说明 |
|---|---|---|
| `skill.builder.wall-clock-sec` | 3600 | 一轮的墙钟上限；边车按它 docker kill，data-service 的等待闩锁 = 它 + 60s |
| `skill.builder.max-turns` | 400 | 一轮 CLI 轮数上限（skill-creator 一次完整迭代轻松过百） |
| `skill.builder.max-budget-usd` | 20 | 一轮 CLI 预算上限。**管不到**容器里的 `claude -p` 子进程与触发测试的直连调用 |
| `skill.builder.model` | 空 = `agent.sandbox.llm.model` | 构建器模型 |
| `skill.builder.trigger-model` | 空 = 构建器模型 | PROMPT 类触发测试用的模型——应当配成租户 Agent 在对话平面实际用的模型，且必须是沙箱 LLM 出口认得的名字 |
| `skill.builder.trigger-candidates-max` | 29 | 触发测试里与被测 skill 竞争的候选上限 |
| `skill.builder.cli-max-workers` | 3 | DOER 类触发测试（每条一个 `claude -p` 进程）的并发上限 |
| `skill.builder.workspace-root` | `skill-builder` | 工作区 MinIO 前缀根：`{root}/{tenantId}/{sessionId}/ws/` |
| `skill.builder.finished-retention-days` | 7 | 已发布 / 已放弃会话的工作区保留天数 |
| `skill.builder.idle-retention-days` | 30 | 进行中但多久没动的会话连同 DRAFT 一起清理 |
| `skill.builder.eval-materialization-retention-days` | 3 | `skills/eval/`、`skills/draft/` 临时物化前缀的保留天数 |
| `skill.builder.janitor-cron` | `0 23 4 * * *` | 上面三项清理的执行时间 |

**不再读取的键**（可以从 Nacos 删掉，留着也无害）：`skill.eval.gate`、`skill.eval.min-pass-rate`——
`SkillEvalGate`（按构建器草稿的 RECALL 评测结果拦发布）已随旧构建器删除，发布关口改为 skill-creator 的
quick_validate 规则 + 人工评审。

## 三、边车（jm-agent-sandbox）

- **部署必须同步 `vendor/`**：`deploy.yml` 的 rsync 已加上；手工部署同理。缺它时 skill-builder 的每一轮都以
  `skill-creator is not deployed with the sidecar` 失败（fail-closed）。
- `GET /sandbox/capabilities` 新增 `skillBuilderVersion: 1`；data-service 派发前核对，版本不认识就不派发。
- 可选 env（都有默认值）：`SKILL_BUILDER_MEMORY`（2g）、`SKILL_BUILDER_PIDS_LIMIT`（512）、
  `SKILL_BUILDER_MAX_CONCURRENCY`（1）、`SKILL_BUILDER_MAX_QUEUE`（2）、`WORKSPACE_SNAPSHOT_MAX_FILE_BYTES`（25MB）、
  `WORKSPACE_SNAPSHOT_MAX_TOTAL_BYTES`（300MB）、`WORKSPACE_SNAPSHOT_MAX_FILES`（5000）。
  skill-builder 的并发池独立于 default / semantic-layer 共用的那几个槽位；池上限 × memory 是这类 run 最多占用的 Docker VM 内存。

## 四、回滚

- 边车：部署目录旁留有 `app.bak-<日期>`，`rsync -a --delete` 回去再 `launchctl kickstart -k` 即可；老边车不认
  `runProfile=skill-builder`，data-service 探测到版本 0 会直接报「沙箱版本不支持 Skill 构建器」，不会乱跑。
- data-service：回滚代码即可；两张新表是纯新增，留着不影响旧代码。已发布的 skill 数据结构不变
  （`ai_skill.bundle_key` 仍指向当前版本，旧代码照常读）。
- 前端：回滚代码即可。

## 五、在 dev 上验证过的（2026-09-27）

普通成员账号经网关走完：建会话 → 一轮只写草稿（skill-creator 被 Skill 工具加载、按约定写到 `/work/skill/<name>/`）
→ 第二轮工作区恢复、只写回改动的文件 → 发布 v1（bundle 原样、版本记录）→ 从已发布 skill 开「改进」会话 →
一轮完整的对照评测（两个子 agent 带 / 不带 skill、打分、benchmark、`--static` 评审页）→ 真实浏览器里在评审页提交
反馈（沙箱 iframe + CSP 零拦截，`feedback.json` 落在该轮目录）→ 下一轮 skill-creator 读到反馈并据此改 skill →
发布 v2（v1 bundle 保留）→ PROMPT 类触发测试在容器里走产品发现机制（输出 `trigger_mechanism: product-discovery`）。
