#!/usr/bin/env bash
# 静态检查基础设施的 compose 文件。
#   docker/docker-compose.yml      ——生产基础设施（./deploy.sh infra）用的就是它，规则 1–6 全部适用；
#                                    deploy.sh 起基础设施之前先跑这个检查，不过就不起。
#   docker/docker-compose.dev.yml  ——本地开发那套，和生产共用同一台 Docker 主机，查规则 1 和规则 6
#                                    （凭据可以保留开发默认值）。
#
# 规则 1：所有发布端口只绑 127.0.0.1。这台机器同时在局域网和 Tailscale 上，端口绑在所有网卡上，
#         等于把 Nacos（存着 jwt.secret）、MySQL、Redis、MinIO 交给任何能连到这台机器的人。
#         应用容器走 Docker 内部网络（服务名）互访，沙箱是宿主机进程、走 127.0.0.1，都不受影响。
#         短写法要以 127.0.0.1: 开头，长写法要有 host_ip: 127.0.0.1。只写容器端口（"8848"）、没绑本机的端口范围、
#         network_mode: host 都会把端口开到所有网卡上，一律拦；看不懂的写法（跨行的行内列表、YAML 别名）也拦。
# 规则 2：compose 里不写死凭据，一律写成 ${VAR:?…}：从 docker/.env 读，缺了 compose 直接报错。
#         写死的值、${VAR:-默认值}（等于把默认密码写进仓库）、${VAR}（缺了就是空密码）都不行。
#         键值写法（KEY: value）和列表写法（- KEY=value）都查。
# 规则 3：Redis 必须带密码启动，--requirepass 后面同样是 ${VAR:?…}（同一行，或者多行列表的下一项）。
# 规则 4：rabbitmq 必须固定 hostname。节点名是 rabbit@<hostname>，不固定的话每次重建容器 hostname 都变，
#         broker 会换一个空的数据目录：队列里的消息、用户和权限全部丢失。
# 规则 5：elasticsearch 的插件目录必须挂卷。否则每次重建容器都要从外网重新下载 IK 插件，下载失败 ES 就起不来，
#         data-server 也跟着起不来（EsIndexInitializer 启动时强校验 IK）。
# 规则 6：每个服务都有内存上限的配置项：mem_limit 和 memswap_limit 写成同一个值（不给 swap），
#         一般写 ${XXX_MEMORY:-0}——不设 = 不限，量过之后再设（变更文档第 5 节）。防的是新加服务时漏掉。
#
# 用法：bash scripts/check-compose.sh                —— 检查上面两个文件
#       bash scripts/check-compose.sh <文件>         —— 按生产规则检查指定文件
#       bash scripts/check-compose.sh --dev <文件>   —— 按开发规则检查指定文件
# 有问题逐条打印，并以非 0 退出。测试：bash scripts/check-compose-test.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0

# 服务名那一行：两格缩进的 "  name:"，后面可以跟锚点和注释
SERVICE_LINE='^  [A-Za-z0-9_.-]+:[[:space:]]*(&[^[:space:]#]+)?[[:space:]]*(#.*)?$'

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
    /^[[:space:]]*ports:/ {
      v = $0; sub(/^[[:space:]]*ports:/, "", v); sub(/[[:space:]]+#.*$/, "", v); gsub(/^[[:space:]]+|[[:space:]]+$/, "", v)
      if (v == "") { in_ports = 1; ports_ind = ind; next }
      if (v ~ /^\[.*\]$/) {
        sub(/^\[/, "", v); sub(/\]$/, "", v)
        n = split(v, items, ",")
        for (i = 1; i <= n; i++) { it = strip(items[i]); if (!short_ok(it)) print NR ": " it }
        next
      }
      print NR ": ports: " v "（看不懂的写法，按没绑本机处理：请逐行写成短写法或长写法）"
      next
    }
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

# 在某个服务的配置块里找一行（块内是比服务名更深的缩进）
service_has() {
  local file=$1 service=$2 pattern=$3
  awk -v svc="$service" -v pat="$pattern" -v svc_line="$SERVICE_LINE" '
    BEGIN { gsub(/\./, "\\.", svc); me = "^  " svc ":[[:space:]]*(&[^[:space:]#]+)?[[:space:]]*(#.*)?$" }
    $0 ~ me { inside = 1; next }
    inside && ($0 ~ svc_line || /^[^[:space:]#]/) { inside = 0 }
    inside && $0 ~ pat { found = 1 }
    END { exit found ? 0 : 1 }' "$file"
}

# 某个服务块里某个键（四格缩进）的值，去掉行尾注释和首尾空白；没有这个键就输出空
service_value() {
  local file=$1 service=$2 key=$3
  awk -v svc="$service" -v key="    ${key}:" -v svc_line="$SERVICE_LINE" '
    BEGIN { gsub(/\./, "\\.", svc); me = "^  " svc ":[[:space:]]*(&[^[:space:]#]+)?[[:space:]]*(#.*)?$" }
    $0 ~ me { inside = 1; next }
    inside && ($0 ~ svc_line || /^[^[:space:]#]/) { inside = 0 }
    inside && index($0, key) == 1 {
      v = substr($0, length(key) + 1)
      sub(/[[:space:]]+#.*$/, "", v); gsub(/^[[:space:]]+|[[:space:]]+$/, "", v)
      print v; exit
    }' "$file"
}

# 规则 6：每个服务都有内存上限的配置项，swap 与内存同值。$2 是建议的变量名前缀（生产为空，开发为 DEV_）
check_memory() {
  local file=$1 prefix=$2 services svc mem swap
  services="$(awk -v svc_line="$SERVICE_LINE" '
    /^[^[:space:]#]/ { in_services = ($0 ~ /^services:[[:space:]]*(#.*)?$/); next }
    in_services && $0 ~ svc_line { s = $0; sub(/:.*/, "", s); gsub(/[[:space:]]/, "", s); print s }
  ' "$file")"
  for svc in $services; do
    mem="$(service_value "$file" "$svc" mem_limit)"
    swap="$(service_value "$file" "$svc" memswap_limit)"
    if [ -z "$mem" ]; then
      echo "${file}：服务 ${svc} 没有 mem_limit：每个服务都要有内存上限的配置项，例如 mem_limit: \${${prefix}$(echo "$svc" | tr 'a-z.-' 'A-Z__')_MEMORY:-0}"
      fail=1
    elif [ "$swap" != "$mem" ]; then
      echo "${file}：服务 ${svc} 的 memswap_limit（${swap:-没写}）要和 mem_limit（${mem}）写成同一个值，不给 swap"
      fail=1
    fi
  done
}

# 规则 2：凭据一律写成 ${VAR:?…}
check_credentials() {
  local file=$1 bad pat hits
  bad="$(awk -v q="'" -v keys='^(MYSQL_ROOT_PASSWORD|RABBITMQ_DEFAULT_(USER|PASS)|MINIO_ROOT_(USER|PASSWORD)|REDISCLI_AUTH)$' '
    /^[[:space:]]*#/ { next }
    {
      line = $0
      if (match(line, /^[[:space:]]*-[[:space:]]*/)) {
        rest = substr(line, RLENGTH + 1); gsub("^[\"" q "]", "", rest)
        n = index(rest, "="); if (n == 0) next
      } else {
        rest = line; sub(/^[[:space:]]+/, "", rest)
        n = index(rest, ":"); if (n == 0) next
      }
      key = substr(rest, 1, n - 1); val = substr(rest, n + 1)
      if (key !~ keys) next
      sub(/^[[:space:]]+/, "", val); gsub("^[\"" q "]", "", val)
      if (val !~ /^\$\{[A-Za-z_][A-Za-z0-9_]*:?\?/) print NR ": " line
    }' "$file")"
  if [ -n "$bad" ]; then
    echo "${file}：以下凭据没有写成 \${VAR:?…}（写死的值、\${VAR:-默认值}、\${VAR} 都不行），改成从 docker/.env 读取："
    printf '%s\n' "$bad"
    fail=1
  fi
  for pat in '-p123456' 'minioadmin'; do
    hits="$(grep -nE -- "$pat" "$file" || true)"
    if [ -n "$hits" ]; then
      echo "${file}：compose 里写死了凭据（匹配 ${pat}），改成从 docker/.env 读取："
      printf '%s\n' "$hits"
      fail=1
    fi
  done
}

# 规则 3：--requirepass 后面是 ${VAR:?…}：同一行（JSON 数组或字符串写法），或者多行列表的下一项
check_redis_password() {
  local file=$1 ok
  ok="$(awk -v q="'" '
    BEGIN {
      same = "^[\"" q ",[:space:]]*\\$\\{[A-Za-z_][A-Za-z0-9_]*:?\\?"
      next_item = "^[[:space:]]*-[[:space:]]*[\"" q "]?\\$\\{[A-Za-z_][A-Za-z0-9_]*:?\\?"
      bare = "^[\"" q "]?[[:space:]]*(#.*)?$"
    }
    /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
    want_next { if ($0 ~ next_item) ok = 1; want_next = 0 }
    /--requirepass/ {
      rest = $0; sub(/.*--requirepass/, "", rest)
      if (rest ~ same) ok = 1
      else if (rest ~ bare) want_next = 1
    }
    END { print ok ? "yes" : "no" }' "$file")"
  if [ "$ok" != yes ]; then
    echo "${file}：redis 没有用 docker/.env 里的密码启动：--requirepass 后面要跟 \${REDIS_PASSWORD:?…}"
    fail=1
  fi
}

# CRLF 换行的文件逐行解析会出错（每行末尾多一个 \r），直接说清楚
check_line_endings() {
  if grep -q $'\r' "$1"; then
    echo "${1}：文件是 CRLF 换行，先转成 LF（例如 dos2unix）再检查"
    fail=1
    return 1
  fi
}

check_prod() {
  local file=$1
  check_line_endings "$file" || return 0
  check_ports "$file"
  check_credentials "$file"
  check_redis_password "$file"
  # 规则 4：rabbitmq 固定 hostname。
  if ! service_has "$file" rabbitmq '^    hostname:[[:space:]]*[^[:space:]]'; then
    echo "${file}：rabbitmq 没有固定 hostname：每次重建容器 broker 都会换一个空的数据目录"
    fail=1
  fi
  # 规则 5：elasticsearch 的插件目录挂卷。
  if ! service_has "$file" elasticsearch ':/usr/share/elasticsearch/plugins'; then
    echo "${file}：elasticsearch 的插件目录没有挂卷：每次重建容器都要从外网重新下载 IK"
    fail=1
  fi
  check_memory "$file" ""
}

check_dev() {
  local file=$1
  check_line_endings "$file" || return 0
  check_ports "$file"
  check_memory "$file" "DEV_"
}

case "${1:-}" in
  "")
    check_prod "$ROOT/docker/docker-compose.yml"
    if [ -f "$ROOT/docker/docker-compose.dev.yml" ]; then
      check_dev "$ROOT/docker/docker-compose.dev.yml"
    fi
    ;;
  --dev)
    check_dev "${2:?用法：check-compose.sh --dev <文件>}"
    ;;
  *)
    check_prod "$1"
    ;;
esac

exit "$fail"
