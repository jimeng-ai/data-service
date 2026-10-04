#!/usr/bin/env bash
# 等一个 HTTP 地址返回 2xx，超时就以非 0 退出。部署流程用它判断新版本是否真的就绪。
# 用法：wait-ready.sh <url> <超时秒数>
# 环境变量 CURL 可以替换 curl 命令本身。部署时 data-server 的管理端口只在 Docker 网络里，要这样用：
#   CURL="docker run --rm --network data-service-infra_default curlimages/curl:8.10.1" \
#     bash scripts/wait-ready.sh http://ds-data-server:8021/actuator/health/readiness 180
set -uo pipefail
url="${1:?用法：wait-ready.sh <url> <超时秒数>}"
timeout="${2:?用法：wait-ready.sh <url> <超时秒数>}"
curl_cmd="${CURL:-curl}"
deadline=$(( $(date +%s) + timeout ))
last="000"
while :; do
  # CURL 可能是带参数的整条命令，必须按词拆开，所以这里故意不加引号
  code="$($curl_cmd -s -m 5 -o /dev/null -w '%{http_code}' "$url" 2>/dev/null)" || code="000"
  if [[ "$code" =~ ^2[0-9][0-9]$ ]]; then
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
