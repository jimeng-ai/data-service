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
3. **记下 `default-rabbitmq.yml` 里 `spring.rabbitmq.listener.simple.*` 的现有设置**，比如有没有 retry、有没有 `default-requeue-rejected`，留个底。第 6 节上线后，入库失败的消息一律进死信队列，和这些设置不冲突。
4. **记下 `rag.ingestion.minio-bucket` 的值**（没配就是 `rag-documents`），第 2.4 节要用。
5. **存一份现状：**
   ```bash
   docker ps --format '{{.Names}}  {{.Ports}}' > ~/p0-before-ports.txt
   docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}' > ~/p0-before-mem.txt
   ```

## 1. 基础设施端口只绑本机

**前提：** data-service 已合入"端口只绑 127.0.0.1"。如果"凭据从 docker/.env 读"也已合入，新版 compose 要求 Redis 必须有密码，而现在的 Redis 没有密码。这种情况下，本节要和 2.1 节在同一个维护窗口里做：先按第 2 节开头准备好 `docker/.env`、完成 2.1 的 Nacos 改动，再执行下面的步骤。

**步骤**（维护窗口内，中断约 5 分钟）：
1. 在生产机的 data-service 目录执行 `git pull`。只需要新的 compose 文件，不需要部署应用。
2. 执行 `./deploy.sh infra`。compose 会重建端口有变化的容器，数据卷保留。
3. 执行 `docker restart ds-data-server ds-gateway`，让应用重新连接。

**验证：**
- 宿主机上：`nc -z 127.0.0.1 3306 && echo 本机可连`。
- 局域网里另一台机器上，对生产机 IP 逐个检查这些端口，应该全部显示已关闭：8848 9848 3306 6379 5672 15672 9000 9001 9200 9300 5601。
  ```bash
  for p in 8848 9848 3306 6379 5672 15672 9000 9001 9200 9300 5601; do nc -z -w 3 <生产机IP> $p && echo "$p 仍然开放" || echo "$p 已关闭"; done
  ```
- 登录一次、发一条对话，再跑一个带上传文件的智能体任务（确认沙箱经 localhost:9000 仍能读写 MinIO）。

**回退：** `git checkout <上一个版本> -- docker/docker-compose.yml`，然后 `./deploy.sh infra`。

以后要从别的机器连这些服务（比如用客户端连生产库、看 MinIO 控制台），改走 SSH 隧道：`ssh -L 9001:127.0.0.1:9001 <生产机>`，然后在本机打开 http://127.0.0.1:9001。

## 2. 换掉默认凭据

**前提：** data-service 已合入"凭据从 docker/.env 读"。合入后，compose 不再写死密码，`deploy.sh` 没有 `docker/.env` 就拒绝启动。

**先建 `docker/.env`**（权限 600，不进仓库）：
```bash
cd <data-service 目录>
cp docker/.env.example docker/.env && chmod 600 docker/.env
```
1. 第一次填写时，MySQL、RabbitMQ、MinIO 先填**现有**的值。Redis 现在没有密码，而新版 compose 要求必须有，所以 `REDIS_PASSWORD` 直接填新值，并且在第一次执行 `./deploy.sh infra` 之前，先完成 2.1 第 2 步的 Nacos 改动。
2. 然后按下面的顺序，逐项把其余服务换成新值。每一项的流程都是：改 `docker/.env` → 改对应服务 → 改 Nacos → 重启 data-server → 验证。

### 2.1 Redis（新增密码）

**步骤：**
1. 在 `docker/.env` 里设 `REDIS_PASSWORD=<新值>`。
2. 在 Nacos `default-redis.yml` 里设 `spring.data.redis.password`。Redisson 读的是同一个键（见 common-core 的 RedissonConfig）。
3. 执行 `./deploy.sh infra`（重建 ds-redis，这次带 `--requirepass`），紧接着执行 `docker restart ds-data-server`。这中间约 1 分钟，data-server 连不上 Redis。

**验证：**
- `docker exec ds-redis sh -c 'unset REDISCLI_AUTH; redis-cli ping'` 返回 `NOAUTH`。
- `docker exec ds-redis redis-cli ping` 返回 `PONG`（容器里已带 REDISCLI_AUTH）。
- data-server 日志里没有 `NOAUTH`。
- 发一条对话，正常。

**回退：** 删掉 Nacos 里的密码键；把 compose 回退到上一个版本（新版 compose 要求 REDIS_PASSWORD 非空）。
```bash
git checkout <上一个版本> -- docker/docker-compose.yml && ./deploy.sh infra && docker restart ds-data-server
```

### 2.2 RabbitMQ（换账号，删除 guest）

已经有数据的 broker 不会读 `RABBITMQ_DEFAULT_USER/PASS`，要用 rabbitmqctl 改。

**步骤：**
1. 新建账号：
   ```bash
   read -rs RMQ_PASS   # 输入新密码，不回显，也不进 shell 历史
   docker exec ds-rabbitmq rabbitmqctl add_user jm "$RMQ_PASS"
   docker exec ds-rabbitmq rabbitmqctl set_user_tags jm administrator
   docker exec ds-rabbitmq rabbitmqctl set_permissions -p / jm ".*" ".*" ".*"
   ```
2. 在 Nacos `default-rabbitmq.yml` 里，把 `spring.rabbitmq.username` / `spring.rabbitmq.password` 改成新账号，然后执行 `docker restart ds-data-server`。
3. 上传一个小文档，确认状态能走到"完成"。
4. 删除 guest：`docker exec ds-rabbitmq rabbitmqctl delete_user guest`。
5. 在 `docker/.env` 里把 `RABBITMQ_USER` / `RABBITMQ_PASSWORD` 也写成新账号。这两项只对全新安装生效，写上是为了保持一致。

**验证：** `docker exec ds-rabbitmq rabbitmqctl list_users` 的结果里没有 guest。

**回退：**
- 在第 2 步之前出问题：不改 Nacos 即可。
- 在第 2 步之后出问题：把 Nacos 改回 guest（第 4 步之前 guest 还在）。

### 2.3 MinIO root 账号

**步骤：**
1. 在 `docker/.env` 里设 `MINIO_ROOT_USER=<新用户名>`、`MINIO_ROOT_PASSWORD=<新值>`。
2. 在 Nacos（data-server 的配置）里，把 `file.minio.access-key`、`file.minio.secret-key` 改成上面的新值。
3. 执行 `./deploy.sh infra`（ds-minio 用新的 root 账号启动），然后执行 `docker restart ds-data-server`。

从这一刻起，沙箱仍在用 minioadmin，带文件的智能体任务会失败。所以紧接着做 2.4。

**验证：**
- 知识库上传、预览正常。
- 旧的默认账号已失效，下面这条命令应当报错：
  ```bash
  docker run --rm --network data-service-infra_default -e MC_HOST_probe=http://minioadmin:minioadmin@minio:9000 minio/mc ls probe
  ```

**回退：** 把 `docker/.env` 和 Nacos 改回原值，再执行 `./deploy.sh infra && docker restart ds-data-server`。

### 2.4 沙箱专用的 MinIO 账号

这个账号只能读写两个桶：第 0 步查到的文档桶（下文写作 `rag-documents`，如果你查到的桶名不同，请替换），以及 `jm-agent`。它没有任何管理权限。

**步骤：**

1. 准备变量：
   ```bash
   NET=data-service-infra_default
   read -rs ROOT_PASS      # 输入 MinIO root 密码（2.3 的新值）
   read -rs SBX_SECRET     # 输入给沙箱生成的新密码（openssl rand -hex 24）
   ROOT_USER=<2.3 的 root 用户名>
   # 用函数而不是把命令存进变量：zsh 不会拆分变量里的空格，"$MC minio/mc …" 在 zsh 里会报找不到命令
   mcroot() { docker run --rm -i --network "$NET" -e "MC_HOST_jm=http://$ROOT_USER:$ROOT_PASS@minio:9000" "$@"; }
   ```
2. 写好权限策略文件：
   ```bash
   cat > /tmp/sandbox-rw.json <<'EOF'
   {
     "Version": "2012-10-17",
     "Statement": [
       { "Effect": "Allow", "Action": ["s3:GetBucketLocation", "s3:ListBucket"],
         "Resource": ["arn:aws:s3:::rag-documents", "arn:aws:s3:::jm-agent"] },
       { "Effect": "Allow", "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
         "Resource": ["arn:aws:s3:::rag-documents/*", "arn:aws:s3:::jm-agent/*"] }
     ]
   }
   EOF
   ```
3. 建桶、建策略、建用户，并把策略绑到用户上：
   ```bash
   mcroot minio/mc mb --ignore-existing jm/jm-agent
   mcroot -v /tmp/sandbox-rw.json:/p.json:ro minio/mc admin policy create jm sandbox-rw /p.json
   mcroot minio/mc admin user add jm sandbox-jm "$SBX_SECRET"
   mcroot minio/mc admin policy attach jm sandbox-rw --user sandbox-jm
   rm /tmp/sandbox-rw.json
   ```
4. 在 jm-agent-sandbox 仓库设置两个 secret：
   ```bash
   gh secret set SANDBOX_MINIO_ACCESS_KEY -R jimeng-ai/jm-agent-sandbox --body sandbox-jm
   printf '%s' "$SBX_SECRET" | gh secret set SANDBOX_MINIO_SECRET_KEY -R jimeng-ai/jm-agent-sandbox
   ```
5. 部署 jm-agent-sandbox 包含本次改动的版本。如果 secret 没设，部署会在重启边车之前失败，线上旧版本不受影响。

**验证：**
- `curl -s http://127.0.0.1:8088/healthz` 返回 `ok`。
- 跑一个带上传文件、会生成产物的智能体任务，正常。
- 用沙箱账号只能看到上面两个桶，而且没有管理权限：
  ```bash
  docker run --rm --network $NET -e MC_HOST_s=http://sandbox-jm:$SBX_SECRET@minio:9000 minio/mc ls s
  ```
  这条命令只列出那两个桶。
- 再执行 `... minio/mc admin user list s`，应当报权限不足。

**回退：** 注意，2.3 之后不能简单地部署 jm-agent-sandbox 的上一个版本：旧版的部署流程根本不注入 `MINIO_*`，边车会回落到 minioadmin，而这个账号在 2.3 已经失效，回退后带文件的任务会全部失败。
- 新版出问题，优先修复后重新部署新版。
- 确实要用旧版时，部署完先手动在 `~/Library/LaunchAgents/com.jimeng.jm-agent-sandbox.plist` 的 `EnvironmentVariables` 里补上 `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY`（填 sandbox-jm 账号），再重启：
  ```bash
  launchctl bootout gui/$(id -u)/com.jimeng.jm-agent-sandbox
  launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.jimeng.jm-agent-sandbox.plist
  ```

### 2.5 MySQL root

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
2. 在 Nacos `default-mysql.yml` 里改 `spring.datasource.password`。网关也加载了这个文件，所以两个应用都要重启：`docker restart ds-data-server ds-gateway`。
3. 在 `docker/.env` 里设 `MYSQL_ROOT_PASSWORD=<新值>`，再执行 `./deploy.sh infra`。这一步是为了重建 ds-mysql，让它的健康检查用上新密码；数据卷保留。

**验证：**
- data-server 登录正常。
- `docker ps` 里 ds-mysql 显示为 healthy。

**回退：** 用 `ALTER USER` 改回旧密码，并把 Nacos 改回原值。

### 2.6 运营账号和开发期账号

- 在运营后台（内网 :10014）登录，然后修改密码。新密码可以用 `openssl rand -base64 18` 生成。
- 如果生产库里还有开发时建的 `test` 企业和它的 `admin` 账号（开发文档里的默认密码是 admin123），把密码改掉，或者直接停用。

## 3. 公网入口拦掉运营接口和内部接口

**前提：** jm-agent-front 已合入改动并部署。部署流程会先用真实 nginx 跑一遍拦截测试。

**验证**（在外网执行）：
```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://atlas.heartbeat.ren/data/admin/operator/auth/login             # 403
curl -s -o /dev/null -w '%{http_code}\n' -X POST 'https://atlas.heartbeat.ren/data/admin/operator;x=1/auth/login'       # 403
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://atlas.heartbeat.ren/data/internal/connector-agent/conn_list    # 403
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://atlas.heartbeat.ren/data/admin/auth/login \
  -H 'Content-Type: application/json' -d '{}'                                                                          # 200（业务错误：用户名或密码不能为空）
```
再从内网 :10014 登录运营后台，确认正常。

**回退：** 部署 jm-agent-front 的上一个版本。

## 4. 登录限流

**前提：** data-service 已合入改动并部署。

**验证：**
- 用一个不存在的用户名连续登录 6 次，第 6 次提示"登录失败次数过多，请 N 分钟后再试"。
- 换一个正确的账号登录，不受影响。

**手动解锁**（误锁时）：
```bash
docker exec ds-redis redis-cli --scan --pattern 'login:fail:*'     # 查看有哪些计数
docker exec ds-redis redis-cli del <上面列出的 key>
```
key 里的账号名是 SHA-256 哈希，看不出是谁。要解锁某个账号，就把列出的 key 全部删掉，或者等窗口到期（单个 IP 15 分钟，单个账号 1 小时）。

## 5. 内存上限

**步骤：**
1. 正常运行一天之后，记下 ds-data-server 和 ds-gateway 的 MEM USAGE 峰值。也可以直接看每次部署日志最后一步 "Memory snapshot"。
2. 打开仓库 jimeng-ai/data-service → Settings → Secrets and variables → Actions → Variables，新建两个变量：
   - `DS_DATA_SERVER_MEMORY`：取峰值 × 1.5，再向上取整到 256m 的倍数。比如峰值是 1.4G，就填 `2304m`。
   - `DS_GATEWAY_MEMORY`：算法同上。
3. 重新部署当前版本号。

**验证：**
- `docker inspect ds-data-server --format '{{.HostConfig.Memory}}'` 的结果不为 0。
- `docker stats --no-stream` 的 LIMIT 列显示出上限。
- 之后一周留意下面这条命令的输出，看有没有被 OOM 杀掉或频繁重启：
  ```bash
  docker inspect ds-data-server --format '{{.State.OOMKilled}} {{.RestartCount}}'
  ```

**回退：** 删掉这两个变量，重新部署。

## 6. 入库失败不再无限重试

**前提：** data-service 已合入改动并部署。

**上线后的行为：**
- 入库失败的文档跟以前一样标为失败，用户可以在知识库里点重试。
- 对应的消息进死信队列 `rag.ingestion.dlq`，不再回到主队列反复重跑、反复计费。

**查看和清理死信队列：**
- 查看消息数：`docker exec ds-rabbitmq rabbitmqctl list_queues name messages`。
- 死信里的消息不需要处理，文档状态以数据库为准；定期清空即可：`docker exec ds-rabbitmq rabbitmqctl purge_queue rag.ingestion.dlq`。

## 7. 部署检查

**前提：** data-service 已合入改动。

**上线后的部署行为：**
- 新版本必须在 180 秒内报告就绪（数据库和 Redis 都连得上），而且网关要对未登录请求返回 401。任何一条不满足，部署都判为失败，发版脚本也不会写上线记录。
- 部署失败时，线上跑的是新版本的容器，可能没起来。回滚办法不变：部署上一个版本号。
- 第一次部署会拉取 `curlimages/curl:8.10.1` 镜像，需要外网。

## 回退总表

| 节 | 回退方式 |
|---|---|
| 1 | compose 回退到上一个版本，再执行 `./deploy.sh infra` |
| 2.1–2.5 | 见各小节 |
| 3 | 部署 jm-agent-front 的上一个版本 |
| 4、6、7 | 部署 data-service 的上一个版本 |
| 5 | 删掉仓库变量，重新部署 |
