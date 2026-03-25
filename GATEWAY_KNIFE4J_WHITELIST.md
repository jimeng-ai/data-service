# Gateway 网关访问 Knife4j 文档配置

## 问题说明
通过网关访问微服务的 Knife4j 文档时，会被网关的认证过滤器拦截，返回 401 错误。

## 解决方案
需要在 Nacos 配置中心的 `gateway.yml` 中添加 Knife4j 相关路径到认证白名单。

## 配置步骤

### 1. 在 Nacos 中打开 gateway.yml 配置文件

### 2. 添加白名单配置

```yaml
# 认证白名单配置
ignore:
  auth:
    whitesUrl:
      # 原有的白名单路径...
      # - /api/login
      # - /api/register

      # Knife4j 文档相关路径（添加以下配置）
      - /**/doc.html                    # 文档页面
      - /**/v3/api-docs/**              # OpenAPI 3 文档数据
      - /**/swagger-resources/**        # Swagger 资源
      - /**/webjars/**                  # 前端静态资源
      - /**/favicon.ico                 # 网站图标
      - /**/swagger-ui.html             # Swagger UI (如果使用)
      - /**/swagger-ui/**               # Swagger UI 资源
```

### 3. 完整示例配置

```yaml
spring:
  cloud:
    gateway:
      routes:
        # 业务路由
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

        # 通用文档路由（兜底）- 处理根路径的文档请求
        - id: gateway-doc-fallback
          uri: lb://sys-server
          predicates:
            - Path=/v3/api-docs/**,/swagger-resources/**,/webjars/**
          order: 9999

# 认证白名单配置
ignore:
  auth:
    whitesUrl:
      # 文档相关路径白名单
      - /**/doc.html
      - /**/v3/api-docs/**
      - /**/swagger-resources/**
      - /**/webjars/**
      - /**/favicon.ico
      - /**/swagger-ui.html
      - /**/swagger-ui/**
```

**路由说明：**
- `sys-server-doc`：处理带服务名前缀的文档请求（推荐方式）
- `gateway-doc-fallback`：兜底路由，处理根路径的文档 API 请求（解决 404 问题）

### 4. 路径说明

| 路径模式 | 说明 | 示例 |
|---------|------|------|
| `/**/doc.html` | Knife4j 文档主页 | `/sys-server/doc.html` |
| `/**/v3/api-docs/**` | OpenAPI 3.0 文档数据接口 | `/sys-server/v3/api-docs` |
| `/**/swagger-resources/**` | Swagger 配置资源 | `/sys-server/swagger-resources/configuration/ui` |
| `/**/webjars/**` | 前端静态资源（CSS/JS） | `/sys-server/webjars/swagger-ui/index.html` |

## 访问方式

### 通过网关访问（需要配置白名单）
```
http://localhost:{网关端口}/sys-server/doc.html
```

### 直接访问微服务（不需要白名单）
```
http://localhost:{服务端口}/doc.html
```

## 安全建议

### 生产环境配置

**方式一：完全禁用文档（推荐）**
```yaml
knife4j:
  production: true  # 生产环境关闭文档
```

**方式二：文档需要认证（推荐）**
不将文档路径加入白名单，访问文档需要提供有效的 token：
```
Authorization: Bearer <token>
```

**方式三：限制 IP 访问**
使用网关的 IP 过滤功能，只允许特定 IP 访问文档。

### 开发/测试环境
可以将文档路径加入白名单，方便开发调试。

## 验证配置

### 1. 修改配置后，观察网关日志
```
网关白名单：[/**/doc.html, /**/v3/api-docs/**, ...]
```

### 2. 测试访问
```bash
# 访问文档首页
curl http://localhost:网关端口/sys-server/doc.html

# 访问 API 文档数据
curl http://localhost:网关端口/sys-server/v3/api-docs
```

### 3. 检查响应
- ✅ 200 OK - 配置成功
- ❌ 401 Unauthorized - 白名单未生效，检查配置是否正确

## 常见问题

### 1. 配置了白名单但仍然 401
**原因：**
- Nacos 配置未刷新
- 路径匹配规则不正确
- 网关服务未重启

**解决：**
```bash
# 1. 在 Nacos 控制台检查配置是否正确
# 2. 点击"发布"按钮确保配置生效
# 3. 观察网关日志中的白名单输出
# 4. 如需要，重启网关服务
```

### 2. 静态资源加载失败（404）
**原因：**
- 路由配置中缺少 `/webjars/**` 路径
- StripPrefix 配置不正确

**解决：**
确保路由配置包含所有必要的路径：
```yaml
predicates:
  - Path=/sys-server/doc.html,/sys-server/v3/api-docs/**,/sys-server/swagger-resources/**,/sys-server/webjars/**
```

### 3. 文档页面空白
**原因：**
- 微服务的 Knife4j 配置未生效
- 接口没有添加 Swagger 注解

**解决：**
- 检查微服务是否引入了 Knife4j 依赖
- 检查 Nacos 中微服务的 Knife4j 配置
- 直接访问微服务的文档地址进行排查

## 相关文件

- `AuthorizeFilter.java` - 网关认证过滤器
- `AuthConfiguration.java` - 白名单配置类
- `KNIFE4J_CONFIG.md` - Knife4j 完整配置说明
