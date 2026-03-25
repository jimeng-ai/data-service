# 修复 Knife4j 文档 404 错误

## 问题描述

访问 `http://localhost:10010/sys-server/doc.html` 时，文档页面加载成功，但页面内部请求 API 文档数据时报 404：

```
GET http://localhost:10010/v3/api-docs/业务模块  -> 404
GET http://localhost:10010/v3/api-docs/系统管理  -> 404
```

## 根本原因

Knife4j 文档页面在请求 API 文档数据时，使用的是**根路径**而非**带服务名前缀的路径**：
- ❌ 请求：`/v3/api-docs/业务模块`
- ✅ 应该：`/sys-server/v3/api-docs/业务模块`

网关路由配置中没有匹配 `/v3/api-docs/**` 根路径的路由规则，所以返回 404。

## 解决方案（三选一）

### 方案一：添加兜底路由（推荐）

在网关配置中添加一个兜底路由，将根路径的文档请求转发到默认服务。

**修改位置：Nacos 配置中心 → gateway.yml 或本地 bootstrap.yml**

```yaml
spring:
  cloud:
    gateway:
      routes:
        # sys-server 业务路由
        - id: sys-server
          uri: lb://sys-server
          predicates:
            - Path=/admin/sys/**

        # sys-server 文档路由（带服务名前缀）
        - id: sys-server-doc
          uri: lb://sys-server
          predicates:
            - Path=/sys-server/doc.html,/sys-server/v3/api-docs/**,/sys-server/swagger-resources/**,/sys-server/webjars/**
          filters:
            - StripPrefix=1

        # 通用文档路由（兜底）★ 新增这个路由
        - id: gateway-doc-fallback
          uri: lb://sys-server
          predicates:
            - Path=/v3/api-docs/**,/swagger-resources/**,/webjars/**
          order: 9999  # 低优先级，作为兜底路由
```

**优点：**
- 简单直接，一次配置解决问题
- 支持直接访问 `http://localhost:10010/doc.html`

**缺点：**
- 如果有多个服务，兜底路由只会转发到一个服务（本例中是 sys-server）

---

### 方案二：配置 Knife4j 的服务器地址

在微服务的 Nacos 配置中指定 Knife4j 的访问路径前缀。

**修改位置：Nacos 配置中心 → sys-server.yml**

```yaml
springdoc:
  api-docs:
    enabled: true
    path: /v3/api-docs
  swagger-ui:
    enabled: true
    # 配置文档访问路径
    path: /swagger-ui.html
    # 配置 API 文档的前缀路径
    url: /sys-server/v3/api-docs
```

**优点：**
- 每个服务独立配置，互不影响

**缺点：**
- 需要为每个服务单独配置

---

### 方案三：直接访问微服务（开发环境推荐）

不通过网关，直接访问微服务的文档地址。

```
http://localhost:8081/doc.html  (sys-server 直接访问)
```

**优点：**
- 无需配置网关路由
- 访问速度更快
- 避免网关认证问题

**缺点：**
- 需要知道每个服务的端口号
- 无法体验网关统一入口

## 推荐配置（方案一）

### 1. 修改 bootstrap.yml

已经为你修改了本地的 `gateway/src/main/resources/bootstrap.yml`，添加了兜底路由。

### 2. 同步到 Nacos（重要！）

如果你使用了 Nacos 配置中心，需要将上述配置同步到 Nacos 的 `gateway.yml`：

1. 登录 Nacos 控制台：`http://localhost:8848/nacos`
2. 找到 `gateway.yml` 配置
3. 添加 `gateway-doc-fallback` 路由配置
4. 点击"发布"

### 3. 重启网关服务

```bash
# 重启网关服务以加载新配置
```

### 4. 验证修复

访问以下地址，应该都能正常工作：

```bash
# 文档首页
http://localhost:10010/sys-server/doc.html

# API 文档数据（分组）
http://localhost:10010/v3/api-docs/业务模块
http://localhost:10010/v3/api-docs/系统管理
http://localhost:10010/v3/api-docs/全部接口
```

## 调试技巧

### 1. 查看网关日志

观察请求是否被正确路由：
```
Mapped [GET /v3/api-docs/业务模块] to gateway-doc-fallback
```

### 2. 测试路由匹配

```bash
# 测试根路径的文档请求
curl http://localhost:10010/v3/api-docs

# 测试带服务名的文档请求
curl http://localhost:10010/sys-server/v3/api-docs
```

### 3. 检查服务注册

确保 sys-server 已在 Nacos 中注册：
```
http://localhost:8848/nacos
```

## 多服务场景

如果你有多个微服务（如 sys-server、order-server、user-server），可以为每个服务配置独立的路由：

```yaml
spring:
  cloud:
    gateway:
      routes:
        # sys-server 文档
        - id: sys-server-doc
          uri: lb://sys-server
          predicates:
            - Path=/sys-server/doc.html,/sys-server/v3/api-docs/**
          filters:
            - StripPrefix=1

        # order-server 文档
        - id: order-server-doc
          uri: lb://order-server
          predicates:
            - Path=/order-server/doc.html,/order-server/v3/api-docs/**
          filters:
            - StripPrefix=1

        # 兜底路由（默认转发到主服务）
        - id: gateway-doc-fallback
          uri: lb://sys-server
          predicates:
            - Path=/v3/api-docs/**
          order: 9999
```

## 总结

最简单的解决方法：

1. ✅ 在 Nacos 的 `gateway.yml` 中添加兜底路由 `gateway-doc-fallback`
2. ✅ 配置认证白名单（如前文所述）
3. ✅ 重启网关服务
4. ✅ 访问 `http://localhost:10010/sys-server/doc.html`

配置完成后，文档应该能够正常加载和展示所有接口了！
