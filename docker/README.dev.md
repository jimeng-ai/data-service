# 本地开发基础设施(与生产隔离)

`docker-compose.dev.yml` 起一套**独立**的 Nacos/MySQL/Redis/RabbitMQ/ES/MinIO/Kibana,专供本地 IDE 调试,**与生产(`data-service-infra` / `ds-*`)物理隔离**:独立 docker 网络、独立数据卷(`dev_*`)、端口全部错开。生产那套一概不碰。

## 端口对照

| 服务 | 生产(ds-*) | **本地(dev-*)** |
|---|---|---|
| Nacos 控制台/HTTP | 8848 | **8849** |
| Nacos gRPC | 9848 | **9849** |
| MySQL | 3306 | **3307** |
| Redis | 6379 | **6380** |
| RabbitMQ / UI | 5672 / 15672 | **5673 / 15673** |
| Elasticsearch | 9200 / 9300 | **9201 / 9301** |
| MinIO S3 / 控制台 | 9000 / 9001 | **9002 / 9003** |
| Kibana | 5601 | **5602** |

凭据与生产一致:MySQL `root/123456`、Redis 无密码、RabbitMQ `guest/guest`、MinIO `minioadmin/minioadmin`、Nacos 免认证。

## 启停

```bash
docker compose -f docker/docker-compose.dev.yml up -d      # 起
docker compose -f docker/docker-compose.dev.yml down       # 停(保留数据)
docker compose -f docker/docker-compose.dev.yml down -v    # 彻底重置(连数据卷一起删)
```

## IDE 里跑 data-server / gateway

两个应用的 Run Configuration 各加一个环境变量,指向本地 Nacos:

```
NACOS_SERVER_ADDR=localhost:8849
```

namespace 用默认值 `fe9e39ae-...` 即可(本地 Nacos 已建好同名命名空间,配置项已指向上面那些 dev 端口)。**不要**设 `NACOS_NAMESPACE=data-service-prod` —— 那会连回生产。

## 种子账号(库默认只有这一份数据)

| 端 | 登录入口 | 账号 | 密码 |
|---|---|---|---|
| 企业端(jm-agent-front / jm-admin) | `/data/admin/auth/login` | `admin`(SUPER_ADMIN,租户 `test`) | `admin123` |
| 运营端(jm-operator) | 运营登录接口 | `admin` | `admin123` |

其余表全空,自己造数据。MinIO 桶 `rag-documents` 由应用首次用到时自动创建,无需手动建。

## 确认本地连的是 dev 这套(没串到生产)

```bash
# data-server 起来后,应注册到【本地】Nacos 的 fe9e39ae 命名空间;生产命名空间不受影响
curl -s "http://localhost:8849/nacos/v1/ns/instance/list?serviceName=data-server&namespaceId=fe9e39ae-06af-49c3-9c5b-6060df2cf93e" | python3 -m json.tool
```

## ⚠️ 仍共享的依赖(本次未隔离)

- **Agent 沙箱** `localhost:8088`(`data-server.yml` 里 `base-url`)—— 本地仍会连到那个生产沙箱进程。
- **AI provider key**(302.ai / openrouter)—— 真实计费,本地共用同一套 key。

需要的话可再单独隔离。
