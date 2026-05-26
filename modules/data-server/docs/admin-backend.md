# 管理后台 (data-service admin)

为前端 data-admin-front 提供的鉴权与账户接口。沿用 data-service 网关现有 JWT 流程（共享 `JWTConstant.TOKEN_SECRET`，gateway 注入 `user-id` 头），但账号体系自有，与 jm-momi 完全解耦。

## 部署清单

1. **建表** —— 在 data-service 库执行 `modules/data-server/src/main/resources/db/migration/V20260526__sys_admin.sql`（或直接看 `docs/mysql-schema.sql` 末段的 `sys_admin`）。
2. **首次启动** —— 启动 data-server，`AdminAuthInitializer` 会自动创建默认账号 `admin / admin123`（密码用 BCrypt 当场算）。**生产环境务必登录后立刻改密**。
3. **更新 Nacos `gateway.yml`** —— 把登录路径加入白名单，否则未登录的前端拿不到 token：

   ```yaml
   ignore:
     auth:
       whitesUrl:
         # ...你已有的白名单...
         - /data/admin/auth/login
   ```

   只白名单 `login`，其他 `/data/admin/auth/**`（`change-password` / `me`）继续走鉴权。

## 接口

所有路径都在网关 `/data/**` 路由下，最终落到 `data-server`。Authorization 头**裸 JWT，不带 `Bearer ` 前缀**（这是 data-service 网关 `AuthorizeFilter` 当前的契约）。

| Method | Path | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/data/admin/auth/login` | 否 | 入参 `{username, password}`；返回 `{token, expiresIn, user{id, username, displayName}}` |
| POST | `/data/admin/auth/change-password` | 是 | 入参 `{oldPassword, newPassword}` |
| GET  | `/data/admin/auth/me` | 是 | 返回当前用户 `{id, username, displayName}` |

Token 过期时间 12 小时。

## 设计要点

- **不复用 `common-core/JWTUtil.generateToken`** —— 它把 `TOKEN_EXPIRE_TIME = 3600`（声明为秒）当毫秒加进 token 过期时间，token 实际 3.6 秒就过期。`AdminAuthService.signToken` 直接用 hutool `JWTUtil.createToken` 签发，固定 12h。
- **共用 `JWTConstant.TOKEN_SECRET`** —— gateway `AuthorizeFilter` 用这把密钥校验，admin 签发的 token 能被网关直接放行。
- **依赖 `spring-security-crypto`**（仅 `BCryptPasswordEncoder`）—— 不引入完整 Spring Security，避免和现有 OkHttp / WebFlux 拦截器打架。`BCryptPasswordEncoder` 在 `AdminAuthConfig` 里单独注册。
- **首期单角色（超管）** —— `sys_admin` 表里没有 role 字段，所有行都是超管。如果后续要 RBAC，加 `sys_admin_role` / `sys_role` / `sys_role_permission` 三张表。

## 默认账号

| 字段 | 值 |
|---|---|
| username | `admin` |
| password | `admin123` |
| 创建方 | `AdminAuthInitializer`（数据库无该 username 时启动写入） |

修改方式：登录后调 `POST /data/admin/auth/change-password`，前端 data-admin-front 在登录页 / 用户菜单提供入口。
