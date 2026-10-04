#!/usr/bin/env bash
# scripts/check-compose.sh 的测试：拿仓库里真实的 compose 文件，每次改出一处毛病，看检查能不能拦住；
# 再改出几种合规的写法，看会不会误报。只用 bash 和 python3 标准库，不碰 Docker。
set -euo pipefail
cd "$(dirname "$0")/.."
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
PROD=docker/docker-compose.yml
DEV=docker/docker-compose.dev.yml
fails=0

# 用法：variant <源文件> <输出文件> <原文1> <替换1> [<原文2> <替换2> …]，每段原文必须恰好出现一次
variant() {
  python3 - "$@" <<'PY'
import sys
src, dst, pairs = sys.argv[1], sys.argv[2], sys.argv[3:]
s = open(src, encoding="utf-8").read()
for old, new in zip(pairs[0::2], pairs[1::2]):
    if s.count(old) != 1:
        sys.exit(f"测试本身有问题：原文片段应当恰好出现一次，实际 {s.count(old)} 次：{old!r}")
    s = s.replace(old, new)
open(dst, "w", encoding="utf-8").write(s)
PY
}

# 用法：check <说明> <pass|fail> <fail 时输出里应当出现的字样> <check-compose 的参数…>
check() {
  local desc=$1 want=$2 frag=$3 out rc=0
  shift 3
  out="$(bash scripts/check-compose.sh "$@" 2>&1)" || rc=$?
  if [ "$want" = pass ] && [ "$rc" = 0 ]; then echo "ok   ${desc}"; return; fi
  if [ "$want" = fail ] && [ "$rc" != 0 ] && grep -qF -- "$frag" <<<"$out"; then echo "ok   ${desc}"; return; fi
  echo "FAIL ${desc}（期望 ${want}，退出码 ${rc}）"
  printf '%s\n' "$out" | sed 's/^/       /'
  fails=1
}

# 用法：expect <说明> <pass|fail> <字样> <原文1> <替换1> [<原文2> <替换2> …]：按生产规则查改过的生产 compose
expect() {
  local desc=$1 want=$2 frag=$3 f="$TMP/case.yml"
  shift 3
  if ! variant "$PROD" "$f" "$@"; then echo "FAIL ${desc}（没能生成用例）"; fails=1; return; fi
  check "$desc" "$want" "$frag" "$f"
}

# 同上，按开发规则查改过的开发 compose
expect_dev() {
  local desc=$1 want=$2 frag=$3 f="$TMP/case-dev.yml"
  shift 3
  if ! variant "$DEV" "$f" "$@"; then echo "FAIL ${desc}（没能生成用例）"; fails=1; return; fi
  check "$desc" "$want" "$frag" --dev "$f"
}

check "仓库里的两份 compose 本身合规" pass ""

# 规则 1：端口只绑 127.0.0.1
P='      - "127.0.0.1:8848:8848"'
expect "绑所有网卡"                        fail '8848:8848'     "$P" '      - "8848:8848"'
expect "只写容器端口（随机端口、所有网卡）" fail '8848'          "$P" '      - "8848"'
expect "只写容器端口、不加引号"             fail '8848'          "$P" '      - 8848'
expect "端口范围没绑本机"                   fail '8848-8849'     "$P" '      - "8848-8849:8848-8849"'
expect "端口范围绑本机"                     pass ''              "$P" '      - "127.0.0.1:8848-8849:8848-8849"'
expect "IPv6 全网卡"                        fail '[::]'          "$P" '      - "[::]:8848:8848"'
expect "长写法没写 host_ip"                 fail 'target: 8848'  "$P" $'      - target: 8848\n        published: "8848"'
expect "长写法绑所有网卡"                   fail 'target: 8848'  "$P" $'      - target: 8848\n        published: "8848"\n        host_ip: 0.0.0.0'
expect "长写法绑本机"                       pass ''              "$P" $'      - target: 8848\n        published: "8848"\n        host_ip: 127.0.0.1'
NP=$'    ports:\n      - "127.0.0.1:8848:8848"\n      - "127.0.0.1:9848:9848"'
expect "行内列表写法"                       fail '8848:8848'     "$NP" '    ports: ["8848:8848", "127.0.0.1:9848:9848"]'
expect "行内列表跨了多行（看不懂就拦）"     fail '看不懂'        "$NP" $'    ports: [\n      "8848:8848",\n      "127.0.0.1:9848:9848"]'
expect "端口用 YAML 别名（看不懂就拦）"     fail '看不懂'        "$NP" '    ports: *nacos_ports'
expect "network_mode: host 绕过端口绑定"    fail 'network_mode'  '    container_name: ds-nacos' $'    container_name: ds-nacos\n    network_mode: host'

# 规则 2：凭据一律写成 ${VAR:?…}（键值写法、列表写法都查）
M=$'    environment:\n      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:?在 docker/.env 里设置 MYSQL_ROOT_PASSWORD}\n      TZ: Asia/Shanghai'
expect "键值写法写死密码"                   fail 'MYSQL_ROOT_PASSWORD' "$M" $'    environment:\n      MYSQL_ROOT_PASSWORD: secret123\n      TZ: Asia/Shanghai'
expect "键值写法写死密码、带引号"           fail 'MYSQL_ROOT_PASSWORD' "$M" $'    environment:\n      MYSQL_ROOT_PASSWORD: "secret123"\n      TZ: Asia/Shanghai'
expect "键值写法从 .env 读、带引号"         pass ''                    "$M" $'    environment:\n      MYSQL_ROOT_PASSWORD: "${MYSQL_ROOT_PASSWORD:?x}"\n      TZ: Asia/Shanghai'
expect "密码写成 \${VAR:-默认值}"           fail 'MYSQL_ROOT_PASSWORD' "$M" $'    environment:\n      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:-root123}\n      TZ: Asia/Shanghai'
expect "密码写成 \${VAR}（缺了不报错）"     fail 'MYSQL_ROOT_PASSWORD' "$M" $'    environment:\n      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}\n      TZ: Asia/Shanghai'
expect "列表写法写死密码"                   fail 'MYSQL_ROOT_PASSWORD' "$M" $'    environment:\n      - MYSQL_ROOT_PASSWORD=secret123\n      - TZ=Asia/Shanghai'
expect "列表写法写成 \${VAR:-默认值}"       fail 'MYSQL_ROOT_PASSWORD' "$M" $'    environment:\n      - MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD:-root123}\n      - TZ=Asia/Shanghai'
expect "列表写法从 .env 读"                 pass ''                    "$M" $'    environment:\n      - MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD:?x}\n      - TZ=Asia/Shanghai'
expect "REDISCLI_AUTH 写死了密码"           fail 'REDISCLI_AUTH' \
  '      REDISCLI_AUTH: ${REDIS_PASSWORD:?在 docker/.env 里设置 REDIS_PASSWORD}' '      REDISCLI_AUTH: redis123'

# 规则 3：Redis 的密码也得写成 ${VAR:?…}
R='"--requirepass", "${REDIS_PASSWORD:?在 docker/.env 里设置 REDIS_PASSWORD}"'
RC='    command: ["redis-server", "--appendonly", "yes", "--requirepass", "${REDIS_PASSWORD:?在 docker/.env 里设置 REDIS_PASSWORD}"]'
expect "requirepass 后面写死了密码"         fail 'requirepass' "$R" '"--requirepass", "secret123"'
expect "requirepass 写成 \${VAR:-默认值}"   fail 'requirepass' "$R" '"--requirepass", "${REDIS_PASSWORD:-redis123}"'
expect "command 写成多行列表、从 .env 读"   pass ''            "$RC" $'    command:\n      - redis-server\n      - --appendonly\n      - "yes"\n      - --requirepass\n      - "${REDIS_PASSWORD:?x}"'
expect "command 写成多行列表、写死密码"     fail 'requirepass' "$RC" $'    command:\n      - redis-server\n      - --requirepass\n      - secret123'

# 规则 6：每个服务都有内存上限的配置项，swap 与内存同值
K=$'  kibana:\n    image: docker.elastic.co/kibana/kibana:8.13.4\n    container_name: ds-kibana\n    restart: unless-stopped\n    mem_limit: ${KIBANA_MEMORY:-0}\n    memswap_limit: ${KIBANA_MEMORY:-0}\n'
K_NOMEM=$'    image: docker.elastic.co/kibana/kibana:8.13.4\n    container_name: ds-kibana\n    restart: unless-stopped\n'
expect "某个服务漏了 mem_limit"             fail 'kibana' "$K" $'  kibana:\n'"$K_NOMEM"
expect "服务名那行带注释、漏了 mem_limit"   fail 'kibana' "$K" $'  kibana:   # 看日志\n'"$K_NOMEM"
expect "服务名那行带注释、有 mem_limit"     pass ''       $'  kibana:\n' $'  kibana:   # 看日志\n'
expect "服务名那行带锚点、有 mem_limit"     pass ''       $'  kibana:\n' $'  kibana: &kibana\n'
expect "services: 带注释、漏了 mem_limit"   fail 'kibana' $'\nservices:\n' $'\nservices:   # 基础设施\n' "$K" $'  kibana:\n'"$K_NOMEM"
expect "memswap_limit 与 mem_limit 不同"    fail 'redis'  '    memswap_limit: ${REDIS_MEMORY:-0}' '    memswap_limit: -1'
expect "新加的服务没写内存上限"             fail 'extra'  $'\nvolumes:\n' $'\n  extra:\n    image: busybox\n\nvolumes:\n'

# 换行符：CRLF 的文件逐行解析会出错，直接说清楚
python3 -c 'import sys; s = open(sys.argv[1], encoding="utf-8").read(); open(sys.argv[2], "w", encoding="utf-8", newline="").write(s.replace("\n", "\r\n"))' "$PROD" "$TMP/crlf.yml"
check "CRLF 换行的文件" fail 'CRLF' "$TMP/crlf.yml"

# 开发那套（--dev）：只查端口和内存上限，凭据可以保留开发默认值
DP=$(grep -m1 -E '^      - "127\.0\.0\.1:[0-9]+:[0-9]+"' "$DEV")
check      "开发 compose 本身合规（--dev）"  pass '' --dev "$DEV"
expect_dev "开发：端口绑了所有网卡"          fail '没有只绑 127.0.0.1' "$DP" "${DP/127.0.0.1:/}"
expect_dev "开发：某个服务漏了 mem_limit"    fail 'nacos' $'    mem_limit: ${DEV_NACOS_MEMORY:-0}\n    memswap_limit: ${DEV_NACOS_MEMORY:-0}\n' ''

exit "$fails"
