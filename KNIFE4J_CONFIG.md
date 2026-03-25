# Knife4j (Swagger) 集成说明

## 已完成的配置

### 1. 依赖配置

#### 父 pom.xml
- 添加了 Knife4j 版本：`4.3.0`
- 添加了依赖管理：`knife4j-openapi3-jakarta-spring-boot-starter`

#### common-core 模块
- 添加了 Knife4j 依赖（供业务服务使用）

#### gateway 模块
- Gateway 基于 WebFlux，通过路由转发访问各微服务文档（无需额外依赖）

### 2. 配置类

#### common-core 模块
- `Knife4jConfiguration.java` - 统一的 Swagger 配置类
  - 配置了 API 基本信息（标题、版本、描述等）
  - 配置了三个 API 分组：系统管理、业务模块、全部接口

#### gateway 模块
- 通过 Gateway 路由配置访问各微服务文档（见下方说明）

### 3. Controller 注解示例

已为 `SysEnvController` 添加了完整的 Swagger 注解：
- `@Tag` - 标记 Controller
- `@Operation` - 标记方法
- `@Parameter` - 标记参数
- `@Schema` - 标记实体字段

## 访问地址

### 方式一：直接访问微服务文档（推荐）
```
http://localhost:{服务端口}/doc.html
```

例如 sys-server（假设端口 8081）：
```
http://localhost:8081/doc.html
```

### 方式二：通过网关访问

**步骤1：配置文档路由**

在 Gateway 的 Nacos 配置（gateway.yml）中添加文档路由：

```yaml
spring:
  cloud:
    gateway:
      routes:
        # sys-server 文档路由
        - id: sys-server-doc
          uri: lb://sys-server
          predicates:
            - Path=/sys-server/doc.html,/sys-server/v3/api-docs/**,/sys-server/swagger-resources/**,/sys-server/webjars/**
          filters:
            - StripPrefix=1
```

**步骤2：配置白名单（重要！）**

将文档相关路径添加到认证白名单（gateway.yml）：

```yaml
ignore:
  auth:
    whitesUrl:
      - /**/doc.html
      - /**/v3/api-docs/**
      - /**/swagger-resources/**
      - /**/webjars/**
      - /**/favicon.ico
```

> ⚠️ **注意**：如果不配置白名单，访问文档会返回 401 错误！
>
> 详细配置说明请参考：[GATEWAY_KNIFE4J_WHITELIST.md](./GATEWAY_KNIFE4J_WHITELIST.md)

**步骤3：访问文档**

```
http://localhost:{网关端口}/sys-server/doc.html
```

## 需要在 Nacos 中添加的配置

### 微服务配置（如 sys-server.yml）

```yaml
# Knife4j 配置
knife4j:
  enable: true
  setting:
    language: zh_cn
    enable-version: true
    enable-swagger-models: true
    enable-document-manage: true
    swagger-model-name: 实体类
    enable-footer: false
    enable-footer-custom: true
    footer-custom-content: Copyright © 2024 基础模板系统

# SpringDoc 配置
springdoc:
  swagger-ui:
    enabled: true
    path: /swagger-ui.html
  api-docs:
    enabled: true
    path: /v3/api-docs
  group-configs:
    - group: default
      paths-to-match: /**
```

### 或者创建单独的配置文件 knife4j.yml

```yaml
knife4j:
  enable: true
  setting:
    language: zh_cn
    enable-version: true
    enable-swagger-models: true
    enable-document-manage: true
    swagger-model-name: 实体类
    enable-footer: false
    enable-footer-custom: true
    footer-custom-content: Copyright © 2024 基础模板系统
    enable-search: true
    enable-dynamic-parameter: true
  cors: true
  production: false

springdoc:
  swagger-ui:
    enabled: true
    path: /swagger-ui.html
    tags-sorter: alpha
    operations-sorter: alpha
  api-docs:
    enabled: true
    path: /v3/api-docs
  group-configs:
    - group: default
      paths-to-match: /**
```

然后在各服务的 bootstrap.yml 中引用：
```yaml
spring:
  cloud:
    nacos:
      config:
        shared-configs:
          - data-id: knife4j.yml
            refresh: true
```

## 常用注解说明

### Controller 层注解

```java
@Tag(name = "模块名称", description = "模块描述")
public class XxxController {

    @Operation(summary = "接口简述", description = "接口详细描述")
    @PostMapping("/save")
    public Result save(@Parameter(description = "参数描述") @RequestBody XxxDTO dto) {
        return Result.success();
    }
}
```

### 实体类注解

```java
@Schema(description = "用户实体")
public class User {

    @Schema(description = "用户ID", example = "1")
    private Long id;

    @Schema(description = "用户名", required = true, minLength = 2, maxLength = 20)
    private String username;

    @Schema(description = "邮箱", format = "email")
    private String email;
}
```

## 常见问题

### 1. 访问 /doc.html 返回 404
- 检查依赖是否正确引入
- 检查 Nacos 配置是否生效
- 检查服务是否正确启动

### 2. 文档不显示接口
- 检查 Controller 是否添加了 `@RestController` 或 `@Controller`
- 检查接口路径是否匹配配置的扫描规则
- 检查是否添加了 `@Tag` 或 `@Operation` 注解

### 3. 通过网关访问文档失败
- 检查网关路由配置是否正确
- 检查路径匹配规则，确保包含 `/doc.html`、`/v3/api-docs/**` 等
- 检查 StripPrefix 过滤器配置

## 后续优化建议

1. **统一响应格式**：创建统一的 Result 类
2. **全局异常处理**：使用 `@RestControllerAdvice`
3. **权限控制**：为敏感接口添加认证
4. **分组优化**：根据业务模块细化 API 分组
5. **生产环境**：设置 `knife4j.production=true` 关闭文档
