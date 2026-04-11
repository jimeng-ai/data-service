# data-service

`data-service` 是一个基于 `Spring Boot 3 + Spring Cloud Alibaba + Nacos` 的微服务后端项目，当前核心职责是地图数据查询与 POI 分析。

项目内包含通用基础设施模块、网关模块和业务模块。其中 `data-server` 负责高德地图相关数据能力，`sys-server` 提供系统级配置与管理接口，`gateway` 负责统一路由和 JWT 鉴权。

## 项目定位

当前仓库不是纯模板，已经落了两类实际能力：

- 地图数据查询：对接高德地图开放接口，支持关键词 POI 查询和周边 POI 查询
- POI 数据分析：按 `typecode` 对 POI 分类后执行 DBSCAN 聚类，输出聚类中心、簇内点位和噪声点
- 字典数据维护：维护行政区编码字典、POI 分类字典
- 系统管理能力：提供系统环境变量、系统字典等基础接口

## 技术栈

- Java 17
- Spring Boot 3.0.2
- Spring Cloud 2022.0.0
- Spring Cloud Alibaba 2022.0.0.0
- Spring Cloud Gateway
- OpenFeign
- MyBatis-Plus
- Redis
- RabbitMQ
- Knife4j / OpenAPI 3
- OkHttp / OkHttp SSE

## 模块结构

- `common/common-core`
  - 通用配置、统一响应、统一异常、JWT 工具、Redis/Feign/Knife4j/OkHttp 配置
- `common/common-persistence`
  - 通用实体、MyBatis-Plus Mapper
- `common/common-identifier`
  - ID 生成相关能力
- `export`
  - 对外暴露的 Feign API，例如 `SysApi`
- `gateway`
  - 网关入口，处理路由转发和鉴权
- `modules/data-server`
  - 地图数据服务，核心接口包括 POI 查询、POI 聚类、周边分析
- `modules/sys-server`
  - 系统服务，提供环境变量、字典、测试接口等

## 核心业务说明

### 1. data-server

主要接口集中在 `modules/data-server`：

- `/data/gaode/get-poi-by-keyword`
  - 关键词检索高德 POI
- `/data/gaode/get-poi-by-around`
  - 按坐标和半径检索周边 POI
- `/data/gaode/get-poi-cluster`
  - 按 `typecode` 分类后执行 DBSCAN 聚类
- `/data/gaode/analysis-around-poi`
  - 先聚类，再分析聚类中心周边 POI 与写字楼分布
- `/data/adcode-citycode-dict/*`
  - 行政区编码字典查询与更新
- `/data/poi-category-dict/*`
  - POI 分类字典查询与更新

当前聚类逻辑已经在服务内实现，核心特点：

- 使用经纬度球面距离计算
- 默认 DBSCAN 参数：`eps = 3000m`、`minPoints = 3`
- 输出聚类中心点、簇内 POI、噪声点

### 2. sys-server

系统服务提供一组基础后台能力：

- 系统环境变量保存与批量保存
- 系统字典保存与批量保存
- RabbitMQ 测试接口

## 网关与鉴权

网关默认路由：

- `/data/**` -> `data-server`
- `/admin/sys/**` -> `sys-server`

鉴权方式：

- 网关统一校验 `Authorization` 请求头中的 JWT
- 白名单路径可直接放行
- 校验通过后会把 `user-id` 写入下游请求头

## 运行依赖

本地启动前至少需要准备以下基础设施：

- JDK 17+
- Maven 3.6+
- Nacos 2.x
- MySQL 8.x
- Redis
- RabbitMQ

## 配置说明

项目采用 `bootstrap.yml + Nacos` 的配置模式，服务启动时会从 Nacos 加载共享配置。

### data-server 依赖的配置

- `data-server.yml`
- `default-mysql.yml`
- `default-redis.yml`
- `default-rabbitmq.yml`
- `default-okhttp.yml`
- `knife4j.yml`

### gateway 依赖的配置

- `gateway.yml`
- `default-mysql.yml`

### sys-server 依赖的配置

- `sys-server.yml`
- `default-mysql.yml`
- `default-redis.yml`
- `default-rabbitmq.yml`
- `knife4j.yml`

注意：

- `nacos_config/` 目录里当前保留了一些历史命名文件
- 以各服务 `bootstrap.yml` 中声明的 `data-id` 为准，不要只看 `nacos_config/` 目录名

## 启动顺序

建议按下面顺序启动：

1. 启动 Nacos、MySQL、Redis、RabbitMQ
2. 在 Nacos 中准备对应 `data-id` 的配置
3. 执行 `mvn clean install`
4. 启动 `modules/sys-server`
5. 启动 `modules/data-server`
6. 启动 `gateway`

## 接口文档

项目集成了 Knife4j / OpenAPI 3，文档请求会通过网关聚合或转发。实际访问地址取决于本地 Nacos 中的端口配置。

## 当前状态

这个仓库已经不是空白模板，但整体仍然偏基础工程形态：

- 通用基础设施比较完整
- `data-server` 业务最明确，已包含实际 POI 分析逻辑
- README、Nacos 配置文件名和部分模板描述存在历史遗留，接手时建议优先以代码和 `bootstrap.yml` 为准
