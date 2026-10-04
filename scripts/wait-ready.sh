#!/usr/bin/env bash
# 等一个 HTTP 地址返回期望的状态码，超时就以非 0 退出。部署流程用它判断新版本是否真的就绪。
# 用法：wait-ready.sh <url> <超时秒数> [期望的状态码]
#   不给期望的状态码时，任何 2xx 都算就绪；给了就只认这一个码（例如网关对未登录请求应当回 401）。
# 环境变量 CURL 可以替换 curl 命令本身。部署时 data-server 的管理端口只在 Docker 网络里，要这样用：
#   CURL="docker run --rm --network data-service-infra_default curlimages/curl:8.10.1" \
#     bash scripts/wait-ready.sh http://ds-data-server:8021/actuator/health/readiness 180
set -uo pipefail
usage="用法：wait-ready.sh <url> <超时秒数> [期望的状态码]"
url="${1:?$usage}"
timeout="${2:?$usage}"
want="${3:-}"
if [ -n "$want" ] && ! [[ "$want" =~ ^[0-9]{3}$ ]]; then
  echo "期望的状态码要写成三位数字（现在是 ${want}）。${usage}" >&2
  exit 2
fi
curl_cmd="${CURL:-curl}"
deadline=$(( $(date +%s) + timeout ))
last="000"

is_ready() {   # $1 = 这一次拿到的状态码
  if [ -n "$want" ]; then
    [ "$1" = "$want" ]
  else
    [[ "$1" =~ ^2[0-9][0-9]$ ]]
  fi
}

while :; do
  # CURL 可能是带参数的整条命令，必须按词拆开，所以这里故意不加引号
  code="$($curl_cmd -s -m 5 -o /dev/null -w '%{http_code}' "$url" 2>/dev/null)" || code="000"
  if is_ready "$code"; then
    echo "就绪：${url}（HTTP ${code}）"
    exit 0
  fi
  last="$code"
  if (( $(date +%s) >= deadline )); then
    echo "超时：${timeout}s 内 ${url} 没有就绪（最后一次 HTTP ${last}）" >&2
    exit 1
  fi
  sleep 3
done
