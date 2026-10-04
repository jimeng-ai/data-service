#!/usr/bin/env bash
# 静态检查基础设施的 compose 文件。
#   docker/docker-compose.yml      ——生产基础设施（./deploy.sh infra）用的就是它，规则 1–5 全部适用；
#   docker/docker-compose.dev.yml  ——本地开发那套，和生产共用同一台 Docker 主机，只查规则 1（凭据可以保留开发默认值）。
#
# 规则 1：所有发布端口只绑 127.0.0.1。这台机器同时在局域网和 Tailscale 上，端口绑在所有网卡上，
#         等于把 Nacos（存着 jwt.secret）、MySQL、Redis、MinIO 交给任何能连到这台机器的人。
#         应用容器走 Docker 内部网络（服务名）互访，沙箱是宿主机进程、走 127.0.0.1，都不受影响。
# 规则 2：compose 里不写死凭据，统一从 docker/.env 读（${VAR:?} 写法，缺了 compose 直接报错）。
# 规则 3：Redis 必须带密码启动。
# 规则 4：rabbitmq 必须固定 hostname。节点名是 rabbit@<hostname>，不固定的话每次重建容器 hostname 都变，
#         broker 会换一个空的数据目录：队列里的消息、用户和权限全部丢失。
# 规则 5：elasticsearch 的插件目录必须挂卷。否则每次重建容器都要从外网重新下载 IK 插件，下载失败 ES 就起不来，
#         data-server 也跟着起不来（EsIndexInitializer 启动时强校验 IK）。
#
# 用法：bash scripts/check-compose.sh            —— 检查上面两个文件
#       bash scripts/check-compose.sh <文件>     —— 只按生产规则检查指定文件
# 有问题逐条打印，并以非 0 退出。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROD="${1:-$ROOT/docker/docker-compose.yml}"
DEV="$ROOT/docker/docker-compose.dev.yml"
fail=0

# 规则 1。端口项：- "8848:8848"、- "127.0.0.1:8848:8848"、- 0.0.0.0:9000:9000（可带引号和行尾注释）
check_ports() {
  local file=$1 ports bad
  ports="$(grep -nE '^[[:space:]]*-[[:space:]]*"?([0-9.]+:)?[0-9]+:[0-9]+(/(tcp|udp))?"?[[:space:]]*(#.*)?$' "$file" || true)"
  bad="$(printf '%s\n' "$ports" | grep -vE '^[0-9]+:[[:space:]]*-[[:space:]]*"?127\.0\.0\.1:' | grep -v '^$' || true)"
  if [ -n "$bad" ]; then
    echo "${file}：以下端口没有只绑 127.0.0.1："
    printf '%s\n' "$bad"
    fail=1
  fi
}

# 在某个服务的配置块里找一行（服务名是两格缩进的 "  name:"，块内是更深的缩进）
service_has() {
  local file=$1 service=$2 pattern=$3
  awk -v svc="  ${service}:" -v pat="$pattern" '
    $0 == svc { inside = 1; next }
    inside && /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { inside = 0 }
    inside && $0 ~ pat { found = 1 }
    END { exit found ? 0 : 1 }' "$file"
}

check_ports "$PROD"
if [ $# -eq 0 ] && [ -f "$DEV" ]; then
  check_ports "$DEV"
fi

# 规则 2：compose 里不许写死凭据。一律写成 ${VAR:?}，从 docker/.env 读（deploy.sh 没有这个文件就拒绝启动）。
# 必须锚定在行首：${MYSQL_ROOT_PASSWORD:?…} 里本身就有「MYSQL_ROOT_PASSWORD:」，不锚定会误报。
for pat in '^[[:space:]]*MYSQL_ROOT_PASSWORD:[[:space:]]*[^$[:space:]]' \
           '^[[:space:]]*RABBITMQ_DEFAULT_(USER|PASS):[[:space:]]*[^$[:space:]]' \
           '^[[:space:]]*MINIO_ROOT_(USER|PASSWORD):[[:space:]]*[^$[:space:]]' \
           '-p123456' 'minioadmin'; do
  hits="$(grep -nE -- "$pat" "$PROD" || true)"
  if [ -n "$hits" ]; then
    echo "compose 里写死了凭据（匹配 ${pat}），改成从 docker/.env 读取："
    printf '%s\n' "$hits"
    fail=1
  fi
done

# 规则 3：Redis 必须带密码启动。
if ! grep -q -- '--requirepass' "$PROD"; then
  echo "redis 没有 --requirepass"
  fail=1
fi

# 规则 4：rabbitmq 固定 hostname。
if ! service_has "$PROD" rabbitmq '^    hostname:[[:space:]]*[^[:space:]]'; then
  echo "rabbitmq 没有固定 hostname：每次重建容器 broker 都会换一个空的数据目录"
  fail=1
fi

# 规则 5：elasticsearch 的插件目录挂卷。
if ! service_has "$PROD" elasticsearch ':/usr/share/elasticsearch/plugins'; then
  echo "elasticsearch 的插件目录没有挂卷：每次重建容器都要从外网重新下载 IK"
  fail=1
fi

exit "$fail"
