#!/usr/bin/env bash
# 静态检查 docker/docker-compose.yml——生产基础设施（./deploy.sh infra）用的就是这份文件。
#
# 规则 1：所有发布端口只绑 127.0.0.1。这台机器同时在局域网和 Tailscale 上，端口绑在所有网卡上，
#         等于把 Nacos（存着 jwt.secret）、MySQL、Redis、MinIO 交给任何能连到这台机器的人。
#         应用容器走 Docker 内部网络（服务名）互访，沙箱是宿主机进程、走 127.0.0.1，都不受影响。
# 规则 2：compose 里不写死凭据，统一从 docker/.env 读（${VAR:?} 写法，缺了 compose 直接报错）。
# 规则 3：Redis 必须带密码启动。
#
# 用法：bash scripts/check-compose.sh [compose 文件]。有问题逐条打印，并以非 0 退出。
set -euo pipefail
FILE="${1:-$(cd "$(dirname "$0")/.." && pwd)/docker/docker-compose.yml}"
fail=0

# 端口项：- "8848:8848"、- "127.0.0.1:8848:8848"、- 0.0.0.0:9000:9000（可带引号和行尾注释）
ports="$(grep -nE '^[[:space:]]*-[[:space:]]*"?([0-9.]+:)?[0-9]+:[0-9]+(/(tcp|udp))?"?[[:space:]]*(#.*)?$' "$FILE" || true)"
bad="$(printf '%s\n' "$ports" | grep -vE '^[0-9]+:[[:space:]]*-[[:space:]]*"?127\.0\.0\.1:' | grep -v '^$' || true)"
if [ -n "$bad" ]; then
  echo "以下端口没有只绑 127.0.0.1："
  printf '%s\n' "$bad"
  fail=1
fi

# 规则 2：compose 里不许写死凭据。一律写成 ${VAR:?}，从 docker/.env 读（deploy.sh 没有这个文件就拒绝启动）。
# 必须锚定在行首：${MYSQL_ROOT_PASSWORD:?…} 里本身就有「MYSQL_ROOT_PASSWORD:」，不锚定会误报。
for pat in '^[[:space:]]*MYSQL_ROOT_PASSWORD:[[:space:]]*[^$[:space:]]' \
           '^[[:space:]]*RABBITMQ_DEFAULT_(USER|PASS):[[:space:]]*[^$[:space:]]' \
           '^[[:space:]]*MINIO_ROOT_(USER|PASSWORD):[[:space:]]*[^$[:space:]]' \
           '-p123456' 'minioadmin'; do
  hits="$(grep -nE -- "$pat" "$FILE" || true)"
  if [ -n "$hits" ]; then
    echo "compose 里写死了凭据（匹配 ${pat}），改成从 docker/.env 读取："
    printf '%s\n' "$hits"
    fail=1
  fi
done

# 规则 3：Redis 必须带密码启动。
if ! grep -q -- '--requirepass' "$FILE"; then
  echo "redis 没有 --requirepass"
  fail=1
fi

exit "$fail"
