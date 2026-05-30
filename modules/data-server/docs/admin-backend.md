# 管理后台 + 多租户 RBAC (data-service)

为 **jm-admin**（管理后台）与 **jm-agent-front**（agent 平台）提供的鉴权、企业开通与企业内 RBAC 接口。
沿用 data-service 网关现有 JWT 流程（共享 `JWTConstant.TOKEN_SECRET`，gateway 据 `tenant_id` claim 注入 `X-Tenant-Id`、据 `id` 注入 `user-id`），账号体系自有，与 jm-momi 解耦。

## 模型

- **企业 = 租户**。一个企业对应一个 `tenant_id`，业务数据（agent / plugin / knowledge_base / chat）按 `tenant_id` 隔离。
- **两套账号、两个登录域**：
  | 角色 | 表 | 登录端点 | JWT | 用途 |
  |---|---|---|---|---|
  | 平台运营 | `sys_operator` | `POST /data/admin/operator/auth/login` | `tenant_id="platform"`, `realm="OPERATOR"` | jm-admin 运营门户：建企业 + 建企业超管 |
  | 企业超管 | `sys_user`(SUPER_ADMIN) | `POST /data/admin/auth/login` | `tenant_id=<企业>`, `realm="ENTERPRISE"`, `user_type` | jm-admin 企业门户（角色/成员/授权）+ jm-agent-front |
  | 企业成员 | `sys_user`(MEMBER) | `POST /data/admin/auth/login` | 同上(MEMBER) | jm-agent-front（仅见被授权资源） |
- **企业内 RBAC**：超管建自定义角色 → 给角色授权「模块（菜单）+ 实例（具体智能体/知识库/插件）」→ 建成员并分配角色。成员登录 jm-agent-front 只看得到被授权资源；超管在租户内看到全部。
- Token 全部裸 JWT（**无 `Bearer` 前缀**，data-service 网关 `AuthorizeFilter` 的契约），12 小时，支持滑动续期。

## 部署清单

1. **建表 / 迁移** —— 在 data-service 库执行
   `modules/data-server/src/main/resources/db/migration/V20260601__rbac_multitenant.sql`
   （新建 `sys_operator` / `sys_enterprise` / `sys_user` / `sys_role` / `sys_role_resource` / `sys_user_role`，并给 `knowledge_base` 补 `tenant_id`）。
   ⚠ 迁移内含历史 `knowledge_base` 回填 `'default'` 租户，须在启动新代码前完成（新代码已把 `knowledge_base` 纳入租户隔离）。
2. **首次启动** —— `OperatorAuthInitializer` 自动创建默认运营账号 `admin / admin123`（BCrypt 当场算）。**生产务必登录后立即改密。**
3. **更新 Nacos `gateway.yml`** —— 把**两个登录端点**加入白名单（其余 `/data/admin/**` 继续走鉴权）：
   ```yaml
   ignore:
     auth:
       whitesUrl:
         # ...你已有的白名单...
         - /data/admin/auth/login            # 企业登录（sys_user）
         - /data/admin/operator/auth/login   # 运营登录（sys_operator）
   ```

## 接口总览

所有路径在网关 `/data/**` 路由下落到 `data-server`。Authorization 头**裸 JWT**。

### 运营门户（需运营 token）
| Method | Path | 说明 |
|---|---|---|
| POST | `/data/admin/operator/auth/login` | 运营登录（白名单） |
| POST | `/data/admin/operator/auth/change-password` / `refresh`，GET `/me` | 运营自助 |
| POST | `/data/admin/operator/enterprises` | 建企业（入参含超管账号），返回企业 + 超管登录名 |
| GET | `/data/admin/operator/enterprises` `/{id}` | 企业列表 / 详情 |
| POST | `/data/admin/operator/enterprises/{id}/enable` `/disable` | 启停企业（停用即封禁该租户全员登录） |
| POST | `/data/admin/operator/enterprises/{id}/super-admin/reset-password` | 重置超管密码 |

### 企业超管门户（需超管 token）
| Method | Path | 说明 |
|---|---|---|
| POST | `/data/admin/auth/login` | 企业账号登录（白名单，超管/成员共用） |
| GET/POST/PUT/DELETE | `/data/admin/rbac/roles` `/{id}` | 角色 CRUD |
| GET/PUT | `/data/admin/rbac/roles/{roleId}/grants` | 读取 / 整体覆盖角色授权 `{modules, agents, knowledgeBases, plugins}` |
| GET | `/data/admin/rbac/grantable/{agents\|knowledge-bases\|plugins\|modules}` | 授权 UI 候选数据 |
| POST/PUT/GET | `/data/admin/rbac/members` `/{id}` | 成员 CRUD |
| POST | `/data/admin/rbac/members/{id}/enable` `/disable` `/reset-password` | 成员启停 / 重置密码 |
| PUT | `/data/admin/rbac/members/{id}/roles` | 分配角色（整体覆盖） |

### 通用（企业账号）
| Method | Path | 说明 |
|---|---|---|
| GET | `/data/admin/me/permissions` | 当前账号有效权限 `{superAdmin, modules, agentIds, knowledgeBaseIds, pluginIds}`，前端据此过滤菜单 |

权限**强制执行**（成员过滤、超管放行）落在：`AgentAdminController` list/get、`KnowledgeBaseController` list/get、`PluginAdminController` list/get，以及运行时 `AgentRuntimeService.byId`（挡住用已知 id 直连未授权 agent 对话）。

## 设计要点

- **运营如何过 `TenantContextFilter`** —— 运营 token 带保留租户 `tenant_id="platform"`；运营/管理类 `sys_*` 表**不**纳入 `JimengTenantLineHandler.TENANT_AWARE_TABLES`，查询不会被注入 `tenant_id`。运营侧写入用 `TenantContext.runAsSystem`。
- **登录名全局唯一** —— `sys_user.username` 唯一键 `(username, deleted)`；jm-agent-front 登录页无租户选择器，必须按 username 全局解析。建议用邮箱或 `<租户>.<名>` 避免撞名。
- **不复用 `common-core/JWTUtil.generateToken`** —— 旧 bug 会让 token 3.6 秒过期；这里用 hutool `JWTUtil.createToken` 固定 12h。
- **仅 `BCryptPasswordEncoder`**（`AdminAuthConfig` 单独注册），不引入完整 Spring Security。
- **旧 `sys_admin`** —— 企业登录已重指 `sys_user`，`sys_admin` 表保留但不再使用（其 `AdminAuthInitializer` 已删除）。

## 默认账号

| 字段 | 值 |
|---|---|
| 端点 | `POST /data/admin/operator/auth/login` |
| username | `admin` |
| password | `admin123` |
| 创建方 | `OperatorAuthInitializer`（`sys_operator` 无该 username 时启动写入） |

企业账号无默认值，由运营在 jm-admin 里创建。
