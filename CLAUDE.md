# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build, run, test

```bash
mvn clean install -DskipTests      # build all modules (or: ./deploy.sh build)
./deploy.sh infra                  # start Nacos/MySQL/Redis/RabbitMQ/Elasticsearch via docker/docker-compose.yml
./deploy.sh down                   # stop infra containers (keeps volumes)

# Java tests live under modules/data-server (JUnit 5 + jqwik property tests) and gateway (auth filter, JWT verification)
mvn -pl modules/data-server test                          # all data-server tests
mvn -pl gateway test                                      # gateway tests
# deploy / infra scripts have their own shell tests: scripts/*-test.sh (release.json runs the ones that need no PyYAML)
mvn -pl modules/data-server test -Dtest=JwtSecretProviderTest          # single class
mvn -pl modules/data-server test -Dtest=AgentSkillVisibilityTest#emptyMeansNothingBound   # single method
```

**Tags are automatic, deploys are manual.** Every push to `main` gets the next `vX.Y.Z` tag from `.github/workflows/tag.yml`; nothing deploys on its own. Deploy only when the user says so: Actions → Deploy → Run workflow with the tag picked in "Use workflow from", or `node scripts/release.mjs deploy [vX.Y.Z]` with `HEAD` at that tag on a clean tree — it runs the gate in `release.json` (`mvn -o` install, the gateway + data-server tests, and the infra/deploy script tests), dispatches the deploy, waits for it and writes a GitHub Release as the go-live record. Roll back by deploying the previous tag the same way. When a deploy needs DDL or Nacos changes, run them first and pass the notes with `--notes-file`.

**Run `mvn install -DskipTests` before `mvn -pl ... test`** when you changed `common/*`: `-pl` without `-am` resolves the commons from the local repo, so a stale jar silently hides your change.

Two runnable Spring Boot apps: `GatewayApplication` (gateway) and `DataServerApplication` (modules/data-server). **Note:** the README still describes a separate `sys-server` module — it does not exist. All business + admin + AI logic now lives inside `data-server`; `sys-server` capabilities were absorbed into its `admin` package. Trust the code over the README.

Config is loaded from **Nacos** (not files in the repo). `bootstrap.yml` declares the `data-id`s to pull (e.g. `data-server.yml`, `default-mysql.yml`, `knife4j.yml`) under namespace `fe9e39ae-...`. To change runtime config you edit Nacos, not the repo. Java 17; `maven.compiler.parameters=true` is set deliberately (Spring param-name resolution) — keep it.

### Secrets that are fail-closed (set them, or the thing refuses to run)

None of these have a default. That is deliberate: a "configure it or you silently get the insecure mode" switch has bitten this system repeatedly, so the missing-config path is now *refuse to start* / *refuse to serve*.

| Key | Where | Missing ⇒ |
|---|---|---|
| `jwt.secret` | Nacos **both** `data-server.yml` (signs) **and** `gateway.yml` (verifies) — **must be the same value** | Both apps **refuse to start**. Different values ⇒ every login fails. Generate with `openssl rand -base64 48`. Rotate without logging everyone out via the gateway's `jwt.additional-secrets` (verify-only list). |
| `CONNECTION_CREDENTIAL_KEY` | env of data-server | Connections can't be saved (never falls back to plaintext) |
| `SANDBOX_SERVICE_TOKEN` | env of the sandbox + Nacos for the caller | Sandbox rejects every call (503) |

> The JWT secret used to be hardcoded in source (two copies). It is in git history forever — treat that old value as permanently burned and never reuse it.

## Local infrastructure (docker/docker-compose.yml)

All infra runs in Docker (`./deploy.sh infra` brings it up). **Credentials come from `docker/.env`** (git-ignored; copy `docker/.env.example`) — `deploy.sh` refuses to run without it, and the compose file has no defaults. **Every published port is bound to `127.0.0.1`**: this host is on the LAN and Tailscale, and binding to all interfaces handed Nacos (which holds `jwt.secret`) to anyone who could reach it. From another machine use an SSH tunnel (`ssh -L 9001:127.0.0.1:9001 <host>`). `scripts/check-compose.sh` enforces both rules (plus a memory-limit knob on every service: `mem_limit: ${<SERVICE>_MEMORY:-0}`, unset = no limit), and `./deploy.sh infra` refuses to start anything when it fails. Rotating credentials on existing volumes and setting the memory limits: `docs/config-changes/2026-10-04-p0-hardening.md`.

| Service | Host:Port | Credentials / Notes |
|---|---|---|
| Nacos | `127.0.0.1:8848` (gRPC `9848`) | auth disabled (localhost-only; slated for removal); console `http://127.0.0.1:8848/nacos`. Namespace `fe9e39ae-06af-49c3-9c5b-6060df2cf93e`, group `DEFAULT_GROUP`. All runtime config lives here as `*.yml` data-ids. |
| MySQL | `127.0.0.1:3306` | user `root` / `MYSQL_ROOT_PASSWORD` from `docker/.env` (only applied when the volume is first initialized), tz `Asia/Shanghai`, utf8mb4. |
| Redis | `127.0.0.1:6379` | `--requirepass $REDIS_PASSWORD`, AOF on. `REDISCLI_AUTH` is set inside the container, so `docker exec ds-redis redis-cli …` just works. |
| RabbitMQ | `127.0.0.1:5672` (UI `15672`) | `RABBITMQ_USER` / `RABBITMQ_PASSWORD` is the admin account for the UI (first init only; existing brokers change via `rabbitmqctl`). data-server uses its own `jm-app` account with no management tag (change doc §1). Loopback restriction lifted so host apps can connect. |
| MinIO | `127.0.0.1:9000` (S3), `127.0.0.1:9001` (console) | `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`. The sandbox uses its own restricted account, not root. |
| Elasticsearch | `localhost:9200` (`9300`) | security disabled, single-node, ES 8.13.4. Auto-installs the `analysis-ik` Chinese tokenizer plugin on first boot — data-server **hard-validates** its presence at startup. |
| Kibana | `localhost:5601` | for browsing logs/ES; zh-CN locale. |
| Filebeat | — | ships `~/logs/data-server/*.log` → ES index `data-server-yyyy.MM.dd` (config: `modules/data-server/docker/filebeat.yml`). Grep app logs in Kibana by `connectionId` / trace-id. |

Container names are prefixed `ds-` (e.g. `ds-mysql`, `ds-nacos`) — handy for `docker logs ds-nacos` / `docker exec -it ds-mysql mysql -uroot -p` (prompts for the password). Data persists in named volumes; `./deploy.sh down` stops containers but keeps volumes.

## Module layout

- `common/common-core` — cross-cutting infra: unified response/exception handling, JWT, Redis/Redisson, OkHttp, Knife4j, **tenant** package, MyBatis-Plus config, SSE util.
- `common/common-persistence` — all MyBatis-Plus entities + mappers (shared by every service). `BaseEntity` + `MyMetaObjectHandler` auto-fill.
- `common/common-identifier` — Snowflake ID service.
- `export` — outward-facing Feign API interfaces (e.g. `SysApi`).
- `gateway` — Spring Cloud Gateway: routing + JWT auth filter.
- `modules/data-server` — the only business module. Packages: `gaode` (AMap POI query + DBSCAN clustering), `admin` (auth/RBAC/multi-tenant operator console), `ai` (the bulk of the system).

## Request flow & multi-tenancy (critical invariants)

1. **Gateway `AuthorizeFilter`** (order 99) verifies the `Authorization` JWT, then injects downstream headers: `user-id` (from `id` claim), `X-Tenant-Id` (from `tenant_id` claim), `x-trace-id`. It **strips any client-supplied `X-Tenant-Id`** — tenant identity comes only from the signed token. This is the core isolation invariant; do not weaken it. Whitelisted paths skip auth but still get tenant-header stripping + trace-id.
2. **`TenantContextFilter`** (in common-core, runs in data-server) reads `X-Tenant-Id`, validates it, and sets `TenantContext` (a ThreadLocal), clearing it in `finally`. Missing/invalid header → 403. Login paths (`/**/auth/login`) and infra paths are whitelisted.
3. **`JimengTenantLineHandler`** (MyBatis-Plus) auto-injects `WHERE tenant_id = ?` using a **positive whitelist** (`TENANT_AWARE_TABLES`). When you add a tenant-scoped table you **must** add its name there (or via `tenant.extra-tenant-tables`), otherwise rows leak across tenants. If `TenantContext` is missing on a tenant table it falls back to a sentinel `__no_tenant__` so queries match nothing.
4. For legitimate cross-tenant/system queries (startup cache loads, operator admin), wrap calls in `TenantContext.runAsSystem(...)` — this makes `ignoreTable` return true and skips tenant filtering.

**Admin model is two-tier:** the *operator* tier (`SysOperator`) manages enterprises (`SysEnterprise` = tenants); the *enterprise* tier (`SysUser`, super-admin vs member) uses RBAC where roles grant `SysRoleResource` over resource types `MENU / AGENT / KNOWLEDGE_BASE / SKILL` (`PLUGIN` was removed with the plugin subsystem; legacy `PLUGIN` rows are ignored by `PermissionResolver.parseType`). `PermissionResolver` resolves effective permissions **live per request** (permissions are intentionally not baked into the JWT). `AdminRequestContext` reads the gateway-injected `user-id`/`X-Tenant-Id` so service methods don't thread them through signatures.

## AI subsystem (`modules/data-server/.../ai`)

This is the heart of the project: a provider-agnostic LLM gateway with tool-calling, skills, and RAG.

> **The plugin subsystem is gone.** `ai/plugin` + `ai/plugingen`, the `Plugin*` entities/mappers and the 5 `plugin*` tables were removed; skills (`ai/skill`) plus the connection registry (`ai/connection`) took over. If you find docs, comments, enum values or frontend code still referring to plugins, they are stale.

- **Provider abstraction** (`ai/provider`): `ProviderRegistry` + SPI interfaces (`ChatClient`, `EmbeddingClient`, `RerankClient`, `ContextualizationClient`). The active provider is chosen by config (`ai.provider`); `@PostConstruct` does **fail-fast** validation that the active provider has all four client beans and required yml fields, or startup aborts.
- **Protocol adapters** (`ai/protocol`): `AiProtocolAdapter` with `ClaudeProtocolAdapter` (Anthropic) and `OpenAiProtocolAdapter` impls. They normalize the differences in tool definitions, tool_use extraction, multi-turn message building, usage parsing, and stream framing between the two API shapes.
- **`AiConversationLoop`** (`ai/conversation`): the multi-turn tool-calling loop, both blocking (`runBlocking`) and streaming (`runStream`). It drives skill/tool rounds (capped by `skill.max-tool-rounds`), accumulates token usage, emits SSE events (`progress` / `tool_result` / `summary` / `error`), and records every model call. Adapter-driven so it's protocol-agnostic.
- **Skills** (`ai/skill`): a `ToolPackage` is either a **platform skill** (disk-based, `tenantId == null`, always visible) or a **tenant skill** (an `ai_skill` row, `tenantId != null`). Both go through **discovery → `activate_skills` → inject** so tool defs don't flood the context.
  - Per-Agent scoping is by **`agent_skill` binding, matched on `ai_skill.id`** — see `SkillRuntimeService.isVisibleToAgent`. Two traps it guards, both **silent** when wrong: (a) discriminate on `getTenantId()==null`, *not* on `getKind()` — disk platform skills are also `kind==SKILL` but can never be bound; (b) `allowedSkillIds` is **three-state** (`null` = old snapshot with no binding info → don't filter; empty = bound to nothing; non-empty = filter). Treating `null` as empty makes every published agent silently lose all tenant skills.
  - `AgentContext` (ThreadLocal of the current `AgentRuntimeView`) carries the binding info into the runtime.
- **Connections** (`ai/connection`): the registry of "who may an agent call, with what identity, how hard" — `connection` + `agent_connection`. Credentials are AES-GCM encrypted (`CredentialCipher`, key from `CONNECTION_CREDENTIAL_KEY`, **fail-closed** — never silently stores plaintext) and are **write-only** over the API. Plaintext never enters the sandbox: the egress proxy injects it by source IP; the container only ever sees `$JM_CONN_BASE/<name>/`.
- **Skill eval** (`ai/skill/eval`): runs a **published** skill's `evals/evals.json` cases in the real sandbox, then has a model grade the transcript. `RECALL` mode uses the user's own words and **never names the skill** (it tests whether the model reaches for it at all — the main silent failure); `CAPABILITY` names it. `evals.json` accepts skill-creator's snake_case keys. There is no publish gate any more (`SkillEvalGate` was removed with the old builder; `skill.eval.gate` is no longer read).
- **Skill builder** (`ai/skill/builder`): the "AI 生成 Skill" feature runs **Anthropic skill-creator verbatim inside the sandbox** (jm-agent-sandbox `runProfile=skill-builder`). A session = a conversation on the hidden `__skill_builder__` agent + a MinIO workspace prefix (`skill_builder_session`); all skill-creator state (draft, evals, `iteration-N/`, review page, `feedback.json`) lives in that workspace, read back by `SkillWorkspaceReader` per `SkillWorkspaceLayout` — **the same directory contract the sidecar's `skillBuilderContract.ts` gives the model; change both together**. Each turn runs on the dedicated `skillBuilderExecutor` with `skill.builder.*` limits. Publishing validates with skill-creator's quick_validate rules (`SkillFrontmatter`), copies the skill dir verbatim to `skills/{id}/{version}/` and records `ai_skill_version`. PROMPT-vs-DOER is inferred by `SkillBundleRules` (must match the sidecar's `run_eval.py`); root `evals/` is stored in bundles but never shipped to a run. Members can use it: `ChatConversationService.assertAgentAccess` exempts only `__skill_builder__`. Rollout notes: `docs/config-changes/2026-09-27-skill-builder-skill-creator.md`.
- **RAG** (`ai/rag`): ingest pipeline is async via RabbitMQ (`IngestionQueueProducer` → `IngestionQueueConsumer`): parse (`Tika` / `PDFBox` / docx / markdown registry) → `HierarchicalChunker` → `ContextualizationService` → `EmbeddingService` → Elasticsearch index. Query path is `HybridSearchService` + `RerankService` → `RagAnswerService`. The ES client auto-config is **excluded** in `DataServerApplication`; a custom `ElasticsearchConfig` wires the client.

## Streaming / async gotcha

Streaming endpoints (Claude/OpenAI `/messages`, RAG answer) return an `SseEmitter` and run the work on a separate `streamExecutor` thread. Request-scoped ThreadLocals (`RequestContextHolder`, `TenantContext`, MDC, `AdminRequestContext` user-id) **do not propagate automatically** to that thread. `MdcAsyncSupport.wrap(...)` captures them on the request thread and re-establishes them on the executor thread. When adding async/streaming work, wrap the task the same way or tenant filtering and user resolution will fail (`无请求上下文` / wrong-tenant data).

## Conventions

- Controllers return raw payloads; `GlobalResponseHandler` wraps them in `CommonResponse`, and `GlobalExceptionHandler` maps exceptions. Throw `ServiceException(ExceptionCode.X, msg)` for business errors — codes are defined in `ExceptionCode`.
- IDs are Snowflake-generated; entities extend `BaseEntity` and rely on `MyMetaObjectHandler` for create/update auditing fields.
- AI model calls are persisted by `AiModelCallRecordService` into `ai_model_call_log` (+ large bodies in `ai_model_call_content`) for replay/observability — keyed by `connectionId` / trace-id, greppable in logs.
