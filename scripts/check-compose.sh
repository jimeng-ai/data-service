#!/usr/bin/env bash
# 静态检查基础设施的 compose 文件。
#   docker/docker-compose.yml      ——生产基础设施（./deploy.sh infra）用的就是它，规则 1–6 全部适用；
#                                    deploy.sh 起基础设施之前先跑这个检查，不过就不起。
#   docker/docker-compose.dev.yml  ——本地开发那套，和生产共用同一台 Docker 主机，只查规则 1（凭据可以保留开发默认值）。
#
# 规则 1：所有发布端口只绑 127.0.0.1。这台机器同时在局域网和 Tailscale 上，端口绑在所有网卡上，
#         等于把 Nacos（存着 jwt.secret）、MySQL、Redis、MinIO 交给任何能连到这台机器的人。
#         应用容器走 Docker 内部网络（服务名）互访，沙箱是宿主机进程、走 127.0.0.1，都不受影响。
#         短写法要以 127.0.0.1: 开头，长写法要有 host_ip: 127.0.0.1。只写容器端口（"8848"）、没绑本机的端口范围、
#         network_mode: host 都会把端口开到所有网卡上，一律拦。
# 规则 2：compose 里不写死凭据，统一从 docker/.env 读（${VAR:?} 写法，缺了 compose 直接报错）。
#         键值写法（KEY: value）和列表写法（- KEY=value）都查。
# 规则 3：Redis 必须带密码启动，密码也从 docker/.env 读。
# 规则 4：rabbitmq 必须固定 hostname。节点名是 rabbit@<hostname>，不固定的话每次重建容器 hostname 都变，
#         broker 会换一个空的数据目录：队列里的消息、用户和权限全部丢失。
# 规则 5：elasticsearch 的插件目录必须挂卷。否则每次重建容器都要从外网重新下载 IK 插件，下载失败 ES 就起不来，
#         data-server 也跟着起不来（EsIndexInitializer 启动时强校验 IK）。
# 规则 6：每个服务都有内存上限的配置项：mem_limit 和 memswap_limit 写成同一个值（不给 swap），
#         一般写 ${XXX_MEMORY:-0}——不设 = 不限，量过之后在 docker/.env 里设（变更文档第 5 节）。防的是新加服务时漏掉。
#
# 用法：bash scripts/check-compose.sh            —— 检查上面两个文件
#       bash scripts/check-compose.sh <文件>     —— 只按生产规则检查指定文件
# 有问题逐条打印，并以非 0 退出。测试：bash scripts/check-compose-test.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROD="${1:-$ROOT/docker/docker-compose.yml}"
DEV="$ROOT/docker/docker-compose.dev.yml"
fail=0

# 规则 1。逐个取出发布端口，没有只绑 127.0.0.1 的按「行号: 内容」打印出来。
check_ports() {
  local file=$1 bad
  bad="$(awk -v q="'" '
    function indent(s) { match(s, /^ */); return RLENGTH }
    function strip(s) {
      sub(/[[:space:]]+#.*$/, "", s)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", s)
      gsub("^[\"" q "]|[\"" q "]$", "", s)
      return s
    }
    function short_ok(s) { return s ~ /^127\.0\.0\.1:/ }
    function flush_long() {
      if (long_line && long_host != "127.0.0.1") print long_line ": " long_desc "（长写法要有 host_ip: 127.0.0.1）"
      long_line = 0
    }
    /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
    {
      ind = indent($0)
      if (in_ports && long_line && ind <= item_ind) flush_long()
      if (in_ports && (ind < ports_ind || (ind == ports_ind && $0 !~ /^ *- /))) in_ports = 0
    }
    /^[[:space:]]*network_mode:[[:space:]]*/ {
      v = $0; sub(/^[[:space:]]*network_mode:/, "", v)
      if (strip(v) == "host") print NR ": network_mode: host（直接用宿主机网络，端口开在所有网卡上）"
      next
    }
    /^[[:space:]]*ports:[[:space:]]*\[/ {
      s = $0; sub(/^[^\[]*\[/, "", s); sub(/\].*$/, "", s)
      n = split(s, items, ",")
      for (i = 1; i <= n; i++) { it = strip(items[i]); if (!short_ok(it)) print NR ": " it }
      next
    }
    /^[[:space:]]*ports:[[:space:]]*(#.*)?$/ { in_ports = 1; ports_ind = ind; next }
    in_ports && /^ *- / {
      item_ind = ind; s = $0; sub(/^ *- */, "", s)
      if (s ~ /^[A-Za-z_]+:/) {
        long_line = NR; long_host = ""; long_desc = strip(s)
        if (s ~ /^host_ip:/) { sub(/^host_ip:/, "", s); long_host = strip(s) }
      } else {
        it = strip(s); if (!short_ok(it)) print NR ": " it
      }
      next
    }
    in_ports && long_line {
      s = $0; gsub(/^[[:space:]]+/, "", s)
      if (s ~ /^host_ip:/) { sub(/^host_ip:/, "", s); long_host = strip(s) }
      next
    }
    END { flush_long() }
  ' "$file")"
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
    inside && /^  [A-Za-z0-9_.-]+:[[:space:]]*$/ { inside = 0 }
    inside && /^[^[:space:]]/ { inside = 0 }
    inside && $0 ~ pat { found = 1 }
    END { exit found ? 0 : 1 }' "$file"
}

# 某个服务块里某个键（四格缩进）的值，去掉行尾注释和首尾空白；没有这个键就输出空
service_value() {
  local file=$1 service=$2 key=$3
  awk -v svc="  ${service}:" -v key="    ${key}:" '
    $0 == svc { inside = 1; next }
    inside && /^  [A-Za-z0-9_.-]+:[[:space:]]*$/ { inside = 0 }
    inside && /^[^[:space:]]/ { inside = 0 }
    inside && index($0, key) == 1 {
      v = substr($0, length(key) + 1)
      sub(/[[:space:]]+#.*$/, "", v); gsub(/^[[:space:]]+|[[:space:]]+$/, "", v)
      print v; exit
    }' "$file"
}

check_ports "$PROD"
if [ $# -eq 0 ] && [ -f "$DEV" ]; then
  check_ports "$DEV"
fi

# 规则 2：compose 里不许写死凭据。一律写成 ${VAR:?}，从 docker/.env 读（deploy.sh 没有这个文件就拒绝启动）。
# 必须锚定在行首：${MYSQL_ROOT_PASSWORD:?…} 里本身就有「MYSQL_ROOT_PASSWORD:」，不锚定会误报。
# 值可以带引号（"${VAR:?}" 合规，"secret" 不合规），所以引号之后第一个字符不是 $ 才算写死。
CRED_KEYS='MYSQL_ROOT_PASSWORD|RABBITMQ_DEFAULT_(USER|PASS)|MINIO_ROOT_(USER|PASSWORD)'
for pat in "^[[:space:]]*(${CRED_KEYS}):[[:space:]]*[\"']?[^\$\"'[:space:]]" \
           "^[[:space:]]*-[[:space:]]*[\"']?(${CRED_KEYS})=[^\$]" \
           '-p123456' 'minioadmin'; do
  hits="$(grep -nE -- "$pat" "$PROD" || true)"
  if [ -n "$hits" ]; then
    echo "compose 里写死了凭据（匹配 ${pat}），改成从 docker/.env 读取："
    printf '%s\n' "$hits"
    fail=1
  fi
done

# 规则 3：Redis 必须带密码启动，--requirepass 后面紧跟的是 ${…}。
if ! grep -qE -- '--requirepass[",[:space:]]+\$\{' "$PROD"; then
  echo "redis 没有用 docker/.env 里的密码启动：--requirepass 后面要跟 \${REDIS_PASSWORD:?…}"
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

# 规则 6：每个服务都有内存上限的配置项，swap 与内存同值。
services="$(awk '
  /^[^[:space:]#]/ { in_services = ($0 ~ /^services:[[:space:]]*$/); next }
  in_services && /^  [A-Za-z0-9_.-]+:[[:space:]]*$/ { s = $0; gsub(/[[:space:]:]/, "", s); print s }
' "$PROD")"
for svc in $services; do
  mem="$(service_value "$PROD" "$svc" mem_limit)"
  swap="$(service_value "$PROD" "$svc" memswap_limit)"
  if [ -z "$mem" ]; then
    echo "服务 ${svc} 没有 mem_limit：每个服务都要有内存上限的配置项，例如 mem_limit: \${$(echo "$svc" | tr 'a-z-' 'A-Z_')_MEMORY:-0}"
    fail=1
  elif [ "$swap" != "$mem" ]; then
    echo "服务 ${svc} 的 memswap_limit（${swap:-没写}）要和 mem_limit（${mem}）写成同一个值，不给 swap"
    fail=1
  fi
done

exit "$fail"
