# 2026-10-04 P0 安全止血与测量：生产操作清单

> 配套代码：data-service、jm-agent-front、jm-agent-sandbox 三个仓库的 `p0-hardening` 改动（合入 main 后以各自的版本号为准）。
> 本文只写键名和步骤，不写任何密钥值。生成密码统一用 `openssl rand -hex 24`：只含 0-9a-f，放进 YAML、plist、URL 都不用转义。
> 原则：一次只做一节，做完按"验证"确认后再做下一节；哪一步不对，就按该节的"回退"恢复。

## 0. 动手前确认（只读，什么都不改）

1. **Nacos 里的服务地址必须是服务名。** 在 prod 命名空间 `data-service-prod` 里，data-server 连各服务用的地址不能是 `host.docker.internal` 或宿主机 IP，要逐项确认：
   - `spring.datasource.url` 的主机是 `mysql`
   - `spring.data.redis.host` 是 `redis`
   - `spring.rabbitmq.host` 是 `rabbitmq`
   - `file.minio.endpoint` 的主机是 `minio`
   - Elasticsearch 地址的主机是 `elasticsearch`

   原因：第 1 节会把基础设施端口改成只绑 127.0.0.1，经宿主机映射端口去连的容器会断；走 Docker 内部网络的服务名不受影响。只要有一项不是服务名，先改过来并验证，再往下做。
2. **Nacos 里不应有 `management.*` 配置。** data-server 的健康检查端点由仓库里的 `application.yml` 决定。如果 Nacos 里有这类键，会覆盖仓库里的设置，让部署检查失效。
3. **记下三个值**，后面要用：
   - `rag.ingestion.minio-bucket`（没配就是 `rag-documents`），第 2.1 节的权限策略要用；
   - `rag.ingestion.queue`（没配就是 `rag.ingestion`），死信队列名是它加 `.dlq`；
   - `default-rabbitmq.yml` 里 `spring.rabbitmq.listener.simple.*` 的现有设置，留个底。
4. **确认能从 Docker 里下载 IK 插件。** 第 1 节会重建 Elasticsearch，并把插件装进一个新的卷（只装这一次，以后重建不再下载）。下载失败 ES 起不来，data-server 也跟着起不来：
   ```bash
   docker run --rm curlimages/curl:8.10.1 -sS -o /dev/null -w '%{http_code}\n' -L https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-8.13.4.zip
   ```
   应当返回 `200`。不是 200 就先别做第 1 节。
5. **存一份现状：**
   ```bash
   docker ps --format '{{.Names}}  {{.Ports}}' > ~/p0-before-ports.txt
   docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}' > ~/p0-before-mem.txt
   docker inspect -f '{{.Config.Hostname}}' ds-rabbitmq      # 现在是随机的容器 ID
   ```
6. **连接器的回调地址不能走公网域名。** 第 3 节会让公网入口拒绝 `/data/internal/**`，而沙箱回调连接器和语义生成走的正是这条路径。在 prod 命名空间的 `data-server.yml` 里看这两个键：
   - `connector.agent.callback-base-url`
   - `connector.semantic.agent.callback-base-url`

   值应当指向宿主机上的网关，推荐 `http://localhost:20011/data`（见 `docs/sandbox-connector-plane.md`）；为空表示这项能力没开，也没问题。如果是 `https://atlas.heartbeat.ren/data` 这类公网地址，先改掉再做第 3 节，否则这两类回调会全部被拒（403）。

## 1. 第一个维护窗口：端口只绑本机 + Redis 加密码 + RabbitMQ 换账号

这三件事必须在同一个窗口里做。新版 compose 一执行就会同时生效：
- **端口只绑 127.0.0.1。**
- **Redis 必须带密码。** 新版 compose 要求 `REDIS_PASSWORD` 非空。
- **RabbitMQ 固定 hostname。** 节点名会从随机的容器 ID 变成 `rabbitmq`，broker 换一个空的数据目录，并按 `.env` 里的账号初始化。旧的 guest 账号和队列里的消息都会消失。这一步是一次性的，固定 hostname 之后再重建就不会丢了。
- **RabbitMQ 分成两个账号。** `.env` 里的是管理账号，只用来登录管理界面；data-server 用另建的 `jm-app`，它只能收发消息，没有管理权限。Nacos 里存的是 `jm-app` 的密码，泄露了也碰不到管理接口。

**前提：** data-service 已合入本次全部 compose 改动，第 0 节都确认过。

**步骤**（中断约 10 分钟）：
1. **确认入库队列是空的。** 有未处理的消息就等它处理完；实在等不了，就记下还在"处理中"的文档，第 1 节做完后在知识库里点重试。
   ```bash
   docker exec ds-rabbitmq rabbitmqctl list_queues name messages
   ```
2. **建 `docker/.env`**（权限 600，不进仓库）：
   ```bash
   cd <data-service 目录>
   cp docker/.env.example docker/.env && chmod 600 docker/.env
   ```
   按下面填写：

   | 键 | 填什么 |
   |---|---|
   | `MYSQL_ROOT_PASSWORD` | 现有密码。第 2.3 节再换 |
   | `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | 现有账号，也就是 minioadmin。第 2.2 节再换 |
   | `REDIS_PASSWORD` | 新值 |
   | `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | 新的管理账号和密码（不要用 guest），只用来登录管理界面 |

3. **改 Nacos**（prod 命名空间）：
   - `default-redis.yml`：
     - 设 `spring.data.redis.password` 为上面的 `REDIS_PASSWORD`。Redisson 读的是同一个键。
     - 再加一行 `spring.data.redis.timeout: 2s`。默认是 60 秒，Redis 断线时所有用到 Redis 的请求都会卡这么久。代码里没有阻塞式的 Redis 命令，全局设 2 秒是安全的。
   - `default-rabbitmq.yml`：把 `spring.rabbitmq.username` 改成 `jm-app`，`spring.rabbitmq.password` 改成给它新生成的密码（`openssl rand -hex 24`）。这个密码第 5 步建账号时还要用一次。
4. **重建基础设施**：在 data-service 目录执行 `git pull`，然后执行 `./deploy.sh infra`。它先用 `scripts/check-compose.sh` 检查 compose 文件，不通过就什么都不动；通过后重建所有配置有变化的容器（数据卷保留），Elasticsearch 会在这一次把 IK 装进新卷。
5. **建 data-server 用的 RabbitMQ 账号**（只能收发消息，没有管理权限）。密码从标准输入传进去，不出现在命令行里：
   ```bash
   read -rs APP_MQ_PASS     # 输入第 3 步写进 Nacos 的那个密码
   echo "$APP_MQ_PASS" | docker exec -i ds-rabbitmq rabbitmqctl add_user jm-app
   docker exec ds-rabbitmq rabbitmqctl set_permissions -p / jm-app '.*' '.*' '.*'
   ```
6. **重启 data-server**：`docker restart ds-data-server`。网关不连 Redis 和 RabbitMQ，不用重启；如果网关日志里有连不上 Nacos 的报错，再执行 `docker restart ds-gateway`。

**验证：**
- **端口：**
  - 宿主机上：`nc -z 127.0.0.1 3306 && echo 本机可连`。
  - 局域网里另一台机器上，下面这些端口应该全部显示已关闭：
    ```bash
    for p in 8848 9848 3306 6379 5672 15672 9000 9001 9200 9300 5601; do nc -z -w 3 <生产机IP> $p && echo "$p 仍然开放" || echo "$p 已关闭"; done
    ```
  - 如果这台机器上也跑着开发那套基础设施（`docker-compose.dev.yml`），先执行 `docker compose -f docker/docker-compose.dev.yml up -d` 让它的新配置生效，再把 8849 9849 3307 6380 5673 15673 9002 9003 9201 9301 5602 也检查一遍。
- **Redis：**
  - `docker exec ds-redis sh -c 'unset REDISCLI_AUTH; redis-cli ping'` 返回 `NOAUTH`；
  - `docker exec ds-redis redis-cli ping` 返回 `PONG`（容器里已经带了 REDISCLI_AUTH）。
- **RabbitMQ：**
  - `docker inspect -f '{{.Config.Hostname}}' ds-rabbitmq` 返回 `rabbitmq`；
  - `docker exec ds-rabbitmq rabbitmqctl list_users` 里有两个账号：`.env` 里的管理账号（标签 `administrator`）和 `jm-app`（没有标签），没有 guest。
- **Elasticsearch：** `docker exec ds-elasticsearch ./bin/elasticsearch-plugin list` 里有 `analysis-ik`。
- **业务：**
  - 登录一次、发一条对话；
  - 上传一个小文档，状态能走到"完成"；
  - 跑一个带上传文件的智能体任务，确认沙箱经 localhost:9000 仍能读写 MinIO。

**回退：**
1. 执行 `git checkout <上一个版本> -- docker/docker-compose.yml`。
2. Nacos 改回原值：删掉 Redis 密码；RabbitMQ 账号改回 guest/guest。
3. 执行 `./deploy.sh infra && docker restart ds-data-server`。

注意，回退时 RabbitMQ 会再换一次空的数据目录（旧 compose 没有固定 hostname，会按 guest 初始化），所以回退前也要先确认入库队列是空的。

以后要从别的机器连这些服务（比如用客户端连生产库、看 MinIO 控制台），改走 SSH 隧道：`ssh -L 9001:127.0.0.1:9001 <生产机>`，然后在本机打开 http://127.0.0.1:9001。

## 2. 换掉其余默认凭据

### 2.1 沙箱专用的 MinIO 账号（先于换 MinIO root 密码做）

先用**现在的** root 账号建好沙箱专用账号，部署新版沙箱，再在 2.2 节换 root 密码，这样全程不停服。

这个账号只能读写两个桶：第 0 步查到的文档桶（下文写作 `rag-documents`，桶名不同请替换），以及 `jm-agent`。它没有任何管理权限。

**步骤：**
1. **准备变量：**
   ```bash
   NET=data-service-infra_default
   ROOT_USER=minioadmin
   read -rs ROOT_PASS      # 输入 MinIO 现在的 root 密码
   read -rs SBX_SECRET     # 输入给沙箱生成的新密码（openssl rand -hex 24）
   # 用函数而不是把命令存进变量：zsh 不会拆分变量里的空格，"$MC minio/mc …" 在 zsh 里会报找不到命令
   mcroot() { docker run --rm -i --network "$NET" -e "MC_HOST_jm=http://$ROOT_USER:$ROOT_PASS@minio:9000" "$@"; }
   ```
2. **写好权限策略文件。** 大于 64MB 的产物和工作区快照会走分片上传，所以策略里要带上分片上传相关的三个权限：
   ```bash
   cat > /tmp/sandbox-rw.json <<'EOF'
   {
     "Version": "2012-10-17",
     "Statement": [
       { "Effect": "Allow",
         "Action": ["s3:GetBucketLocation", "s3:ListBucket", "s3:ListBucketMultipartUploads"],
         "Resource": ["arn:aws:s3:::rag-documents", "arn:aws:s3:::jm-agent"] },
       { "Effect": "Allow",
         "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject",
                    "s3:ListMultipartUploadParts", "s3:AbortMultipartUpload"],
         "Resource": ["arn:aws:s3:::rag-documents/*", "arn:aws:s3:::jm-agent/*"] }
     ]
   }
   EOF
   ```
3. **建桶、建策略、建用户，并把策略绑到用户上：**
   ```bash
   mcroot minio/mc mb --ignore-existing jm/jm-agent
   mcroot -v /tmp/sandbox-rw.json:/p.json:ro minio/mc admin policy create jm sandbox-rw /p.json
   mcroot minio/mc admin user add jm sandbox-jm "$SBX_SECRET"
   mcroot minio/mc admin policy attach jm sandbox-rw --user sandbox-jm
   rm /tmp/sandbox-rw.json
   ```
4. **在 jm-agent-sandbox 仓库设置两个 secret：**
   ```bash
   gh secret set SANDBOX_MINIO_ACCESS_KEY -R jimeng-ai/jm-agent-sandbox --body sandbox-jm
   printf '%s' "$SBX_SECRET" | gh secret set SANDBOX_MINIO_SECRET_KEY -R jimeng-ai/jm-agent-sandbox
   ```
5. **部署** jm-agent-sandbox 包含本次改动的版本。如果 secret 没设，部署会在重启边车之前就失败，线上旧版本不受影响。

**验证：**
- `curl -s http://127.0.0.1:8088/healthz` 返回 `ok`。
- 跑一个带上传文件、会生成产物的智能体任务，正常。
- 用沙箱账号只能看到上面两个桶：下面这条命令应当只列出那两个桶。
  ```bash
  docker run --rm --network $NET -e MC_HOST_s=http://sandbox-jm:$SBX_SECRET@minio:9000 minio/mc ls s
  ```
- 用同样的方式执行 `minio/mc admin user list s`，应当报权限不足。

**回退：** 部署 jm-agent-sandbox 的上一个版本。这时 root 还没换，旧版回落到 minioadmin 照常能用。

### 2.2 MinIO root 账号

**步骤：**
1. 在 `docker/.env` 里设 `MINIO_ROOT_USER=<新用户名>`、`MINIO_ROOT_PASSWORD=<新值>`。
2. 在 Nacos（data-server 的配置）里，把 `file.minio.access-key`、`file.minio.secret-key` 改成上面的新值。
3. 执行 `./deploy.sh infra`（ds-minio 用新的 root 账号启动），然后执行 `docker restart ds-data-server`。

**验证：**
- 知识库上传、预览正常。
- 跑一个带文件的智能体任务。沙箱用的是 sandbox-jm，应当不受影响；如果失败，说明 sandbox-jm 账号没能保留下来，用新 root 账号重做一遍 2.1 第 1–3 步即可，不用重新部署沙箱。
- 旧的默认账号已失效，下面这条命令应当报错：
  ```bash
  docker run --rm --network data-service-infra_default -e MC_HOST_probe=http://minioadmin:minioadmin@minio:9000 minio/mc ls probe
  ```

**回退：** 把 `docker/.env` 和 Nacos 改回原值，再执行 `./deploy.sh infra && docker restart ds-data-server`。

**注意：** 2.2 之后不能再随意把沙箱回退到改造前的版本。旧版部署流程不注入 `MINIO_*`，会回落到 minioadmin，而这个账号已经失效。确实要用旧版时，部署完先手动在 `~/Library/LaunchAgents/com.jimeng.jm-agent-sandbox.plist` 的 `EnvironmentVariables` 里补上 `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY`（填 sandbox-jm 账号），再重启：
```bash
launchctl bootout gui/$(id -u)/com.jimeng.jm-agent-sandbox
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.jimeng.jm-agent-sandbox.plist
```

### 2.3 MySQL root

**步骤：**
1. 先在 MySQL 里改密码：
   ```bash
   docker exec -it ds-mysql mysql -uroot -p     # 输入旧密码
   ```
   在 mysql 提示符下执行：
   ```sql
   ALTER USER 'root'@'%' IDENTIFIED BY '<新值>';
   ALTER USER 'root'@'localhost' IDENTIFIED BY '<新值>';
   ```
2. 在 Nacos `default-mysql.yml` 里改 `spring.datasource.password`，然后执行 `docker restart ds-data-server`。网关不连数据库，不用重启。
3. 在 `docker/.env` 里把 `MYSQL_ROOT_PASSWORD` 也改成新值。不需要重建 ds-mysql：已有数据的库不读这个变量；它的健康检查用的 `mysqladmin ping` 在密码不对时也返回正常，本来就不校验密码。

**验证：** data-server 登录正常。

**回退：** 用 `ALTER USER` 改回旧密码，Nacos 和 `docker/.env` 也都改回原值。

### 2.4 运营账号和开发期账号

- 在运营后台（内网 :10014）登录，然后修改密码。新密码可以用 `openssl rand -base64 18` 生成。
- 如果生产库里还有开发时建的 `test` 企业和它的 `admin` 账号（开发文档里的默认密码是 admin123），把密码改掉，或者直接停用。

## 3. 公网入口拦掉运营接口和内部接口

**部署前：** 确认第 0 节第 6 条：连接器回调地址不走公网域名。

**前提：** jm-agent-front 已合入改动并部署。部署流程会先用真实的 nginx 跑一遍拦截测试。

**验证**（在外网执行）：
```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://atlas.heartbeat.ren/data/admin/operator/auth/login             # 403
curl -s -o /dev/null -w '%{http_code}\n' -X POST 'https://atlas.heartbeat.ren/data/admin/operator;x=1/auth/login'       # 403
curl -s -o /dev/null -w '%{http_code}\n' -X POST 'https://atlas.heartbeat.ren/data/admin;x=1/operator/auth/login'       # 403
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://atlas.heartbeat.ren/data/internal/connector-agent/conn_list    # 403
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://atlas.heartbeat.ren/data/admin/auth/login \
  -H 'Content-Type: application/json' -d '{}'                                                                          # 200（业务错误：用户名或密码不能为空）
```
再从内网 :10014 登录运营后台，确认正常。

**回退：** 部署 jm-agent-front 的上一个版本。

## 4. 登录限流

**前提：** data-service 已合入改动并部署。

**规则：**
- 同一个账号从同一个 IP 失败 5 次，锁 15 分钟；同一个账号累计失败 20 次，锁 1 小时。都从最后那次失败算起。
- 账号按库里认定的那一行计数：ádmin、ＡＤＭＩＮ 和 admin 算同一个账号。
- Redis 不可用时自动放行，登录照常；30 秒后再试着恢复限流。

**验证：**
- 用一个不存在的用户名连续登录 6 次，第 6 次提示"登录失败次数过多，请 N 分钟后再试"。
- 换一个正确的账号登录，不受影响。

**手动解锁**（误锁时）：
- key 的格式：
  - 账号总数那本账是 `login:fail:<enterprise|operator>:u:<哈希>`；
  - 账号+IP 那本账是 `login:fail:<enterprise|operator>:ip:<哈希>:<IP>`。
- 哈希的算法：先确定账号标识。库里查得到的账号是 `id:<用户 id>`，查不到的是 `name:<小写、去首尾空格的用户名>`。再对这个标识做 SHA-256。
- 按账号精确解锁：
  ```bash
  read -r UID_IN_DB          # 输入要解锁的用户 id（sys_user.id，运营账号是 sys_operator.id）
  H=$(printf 'id:%s' "$UID_IN_DB" | shasum -a 256 | cut -d' ' -f1)
  docker exec ds-redis redis-cli --scan --pattern "login:fail:*:$H*"                                   # 先看看
  docker exec ds-redis redis-cli --scan --pattern "login:fail:*:$H*" | xargs -r docker exec -i ds-redis redis-cli del
  ```
- 不想算哈希，也可以等窗口到期。

## 5. 内存上限

### 5.1 两个应用容器（data-server、网关）

**步骤：**
1. 正常运行一天之后，记下 ds-data-server 和 ds-gateway 的 MEM USAGE 峰值。也可以直接看每次部署日志最后一步 "Memory snapshot"。
2. 打开仓库 jimeng-ai/data-service → Settings → Secrets and variables → Actions → Variables，新建两个变量：
   - `DS_DATA_SERVER_MEMORY`：取峰值 × 1.5，再向上取整到 256m 的倍数。比如峰值是 1.4G，就填 `2304m`。
   - `DS_GATEWAY_MEMORY`：算法同上。
   - 格式必须是"数字加 m 或 g"，而且至少 512m。写错了，部署会在动旧容器之前就失败，线上不受影响。
3. 重新部署当前版本号。

**验证：**
- `docker inspect ds-data-server --format '{{.HostConfig.Memory}}'` 的结果不为 0。
- `docker stats --no-stream` 的 LIMIT 列显示出上限。
- 之后一周留意下面这条命令，看有没有被 OOM 杀掉或频繁重启：
  ```bash
  docker inspect ds-data-server --format '{{.State.OOMKilled}} {{.RestartCount}}'
  ```

**回退：** 删掉这两个变量，重新部署。

### 5.2 基础设施容器

compose 里每个服务都有内存上限的配置项，值来自 `docker/.env` 里的 `<服务名>_MEMORY`，不设就是不限。

**步骤：**
1. 正常运行一天之后，用 `docker stats --no-stream` 记下 ds-nacos、ds-mysql、ds-redis、ds-rabbitmq、ds-minio、ds-elasticsearch、ds-filebeat、ds-kibana 的 MEM USAGE 峰值。
2. 在 `docker/.env` 里设 `NACOS_MEMORY`、`MYSQL_MEMORY` 等变量，取峰值 × 1.5，再向上取整到 256m 的倍数。两个 JVM 服务的堆是固定的，上限要比堆大得多：
   - Nacos 的堆是 512m（`JVM_XMX`），上限至少 `1g`；
   - Elasticsearch 的堆是 512m（`ES_JAVA_OPTS`），上限至少 `1536m`，因为它还要用堆外内存。
3. 在维护窗口里执行 `./deploy.sh infra`。设了值的容器会重建；MySQL、Redis、RabbitMQ 重建期间，data-server 会短暂报错。

**验证：**
- `docker stats --no-stream` 的 LIMIT 列显示出上限。
- RabbitMQ 要能认出这个上限，否则它不会在接近上限时自己限流，而是直接被杀掉。执行 `docker exec ds-rabbitmq rabbitmq-diagnostics -q status`，看 "Memory high watermark" 那一段算出来的值，它应当小于上限。如果没有变小，把 `RABBITMQ_MEMORY` 改回空，再执行一次 `./deploy.sh infra`。
- 之后一周，按 5.1 的方法留意有没有被 OOM 杀掉的容器。

**回退：** 把 `docker/.env` 里对应的 `*_MEMORY` 清空，再执行 `./deploy.sh infra`。

### 5.3 前端和沙箱的容器

- 三个前端容器（10012、10013、10014）固定 256m：nginx 只托管静态文件和转发请求，上传的文件写在磁盘上，不占内存。
- 沙箱的 egress 代理容器默认 512m，可以用环境变量 `EGRESS_MEMORY` 改。
- 沙箱每个任务的一次性容器本来就有上限（1g）。

这些随各自仓库的下一次部署生效，不用单独操作。用 `docker stats --no-stream` 的 LIMIT 列确认。

## 6. 入库失败不再无限重试

**前提：** data-service 已合入改动并部署。

**上线后的行为：**
- 入库失败的文档跟以前一样标为失败，用户可以在知识库里点重试。
- 对应的消息进死信队列，不再回到主队列反复重跑、反复计费。死信队列名是第 0 步记下的 `rag.ingestion.queue` 加 `.dlq`，默认是 `rag.ingestion.dlq`。

**查看和清理死信队列：**
- 查看消息数：`docker exec ds-rabbitmq rabbitmqctl list_queues name messages`。
- 死信里的消息不需要处理，文档状态以数据库为准，定期清空即可：`docker exec ds-rabbitmq rabbitmqctl purge_queue rag.ingestion.dlq`。

## 7. 部署检查

**前提：** data-service 已合入改动。

**第一次上线这个版本之前，先在本地验证一次健康检查。** 部署流程会先删掉旧容器再起新容器，新版如果起不来就是线上停服，所以要先在本地确认它能起来：
1. 本地开发基础设施要先起来。
2. 打包并启动：
   ```bash
   cd <data-service 目录>
   mvn -q -DskipTests -pl modules/data-server -am package
   NACOS_SERVER_ADDR=localhost:8849 java -jar modules/data-server/target/data-server-1.0-SNAPSHOT.jar
   ```
3. 另开一个终端检查：
   ```bash
   curl -i http://127.0.0.1:8021/actuator/health/readiness     # 200，{"status":"UP"}
   docker stop dev-redis
   curl -i http://127.0.0.1:8021/actuator/health/readiness     # 503
   docker start dev-redis
   curl -i http://127.0.0.1:<主端口>/actuator/health            # 404：主端口上没有 actuator（主端口见 Nacos 的 server.port）
   ```

**上线后的部署行为：**
- 新版本必须在 180 秒内报告就绪（数据库和 Redis 都连得上），然后网关要在 60 秒内对未登录请求返回 401。任何一条不满足，部署都判为失败，发版脚本也不会写上线记录。
- 就绪检查限时 8 分钟，整个部署限时 45 分钟。Docker 卡住时会到点判失败，不会占着五个仓库共用的 runner 一直不放。
- 部署失败时，线上跑的是新版本的容器，可能没起来。回滚办法不变：部署上一个版本号。
- 第一次部署会拉取 `curlimages/curl:8.10.1` 镜像，需要外网。

## 回退总表

| 节 | 回退方式 |
|---|---|
| 1 | compose 和 Nacos 改回原值，执行 `./deploy.sh infra`，重启 data-server（先确认入库队列为空） |
| 2.1 | 部署 jm-agent-sandbox 的上一个版本 |
| 2.2、2.3 | 见各小节 |
| 3 | 部署 jm-agent-front 的上一个版本 |
| 4、6、7 | 部署 data-service 的上一个版本 |
| 5.1 | 删掉仓库变量，重新部署 |
| 5.2 | `docker/.env` 里对应的 `*_MEMORY` 清空，执行 `./deploy.sh infra` |
| 5.3 | 部署对应仓库的上一个版本 |
