#!/usr/bin/env bash
# scripts/check-compose.sh 的测试：拿仓库里真实的 docker/docker-compose.yml，每次改出一处毛病，看检查能不能拦住；
# 再改出几种合规的写法，看会不会误报。只用 bash 和 python3 标准库，不碰 Docker。
set -euo pipefail
cd "$(dirname "$0")/.."
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
BASE=docker/docker-compose.yml
fails=0

# 把 BASE 里恰好出现一次的原文片段换掉，写到 $1
variant() {
  python3 - "$BASE" "$1" "$2" "$3" <<'PY'
import sys
src, dst, old, new = sys.argv[1:5]
s = open(src, encoding="utf-8").read()
if s.count(old) != 1:
    sys.exit(f"测试本身有问题：原文片段应当恰好出现一次，实际 {s.count(old)} 次：{old!r}")
open(dst, "w", encoding="utf-8").write(s.replace(old, new))
PY
}

# $1 说明，$2 pass/fail，$3 原文片段，$4 替换成，$5 fail 时输出里应当出现的字样
expect() {
  local desc=$1 want=$2 f="$TMP/case.yml" out rc=0
  if ! variant "$f" "$3" "$4"; then echo "FAIL ${desc}（没能生成用例）"; fails=1; return; fi
  out="$(bash scripts/check-compose.sh "$f" 2>&1)" || rc=$?
  if [ "$want" = pass ] && [ "$rc" = 0 ]; then echo "ok   ${desc}"; return; fi
  if [ "$want" = fail ] && [ "$rc" != 0 ] && grep -qF -- "${5:-}" <<<"$out"; then echo "ok   ${desc}"; return; fi
  echo "FAIL ${desc}（期望 ${want}，退出码 ${rc}）"
  printf '%s\n' "$out" | sed 's/^/       /'
  fails=1
}

if out="$(bash scripts/check-compose.sh 2>&1)"; then
  echo "ok   仓库里的两份 compose 本身合规"
else
  echo "FAIL 仓库里的 compose 没通过检查"; printf '%s\n' "$out" | sed 's/^/       /'; fails=1
fi

# 规则 1：端口只绑 127.0.0.1
P='      - "127.0.0.1:8848:8848"'
expect "绑所有网卡"                       fail "$P" '      - "8848:8848"'                   '8848:8848'
expect "只写容器端口（随机端口、所有网卡）" fail "$P" '      - "8848"'                        '8848'
expect "只写容器端口、不加引号"            fail "$P" '      - 8848'                          '8848'
expect "端口范围没绑本机"                  fail "$P" '      - "8848-8849:8848-8849"'         '8848-8849'
expect "端口范围绑本机"                    pass "$P" '      - "127.0.0.1:8848-8849:8848-8849"'
expect "IPv6 全网卡"                       fail "$P" '      - "[::]:8848:8848"'              '[::]'
expect "长写法没写 host_ip"                fail "$P" $'      - target: 8848\n        published: "8848"' 'target: 8848'
expect "长写法绑所有网卡"                  fail "$P" $'      - target: 8848\n        published: "8848"\n        host_ip: 0.0.0.0' 'target: 8848'
expect "长写法绑本机"                      pass "$P" $'      - target: 8848\n        published: "8848"\n        host_ip: 127.0.0.1'
expect "行内列表写法"                      fail $'    ports:\n      - "127.0.0.1:8848:8848"\n      - "127.0.0.1:9848:9848"' \
                                                '    ports: ["8848:8848", "127.0.0.1:9848:9848"]' '8848:8848'
expect "network_mode: host 绕过端口绑定"   fail '    container_name: ds-nacos' $'    container_name: ds-nacos\n    network_mode: host' 'network_mode'

# 规则 2：不写死凭据（键值写法、列表写法都查）
M=$'    environment:\n      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:?在 docker/.env 里设置 MYSQL_ROOT_PASSWORD}\n      TZ: Asia/Shanghai'
expect "键值写法写死密码"                  fail "$M" $'    environment:\n      MYSQL_ROOT_PASSWORD: secret123\n      TZ: Asia/Shanghai' 'MYSQL_ROOT_PASSWORD'
expect "键值写法写死密码、带引号"          fail "$M" $'    environment:\n      MYSQL_ROOT_PASSWORD: "secret123"\n      TZ: Asia/Shanghai' 'MYSQL_ROOT_PASSWORD'
expect "键值写法从 .env 读、带引号"        pass "$M" $'    environment:\n      MYSQL_ROOT_PASSWORD: "${MYSQL_ROOT_PASSWORD:?x}"\n      TZ: Asia/Shanghai'
expect "列表写法写死密码"                  fail "$M" $'    environment:\n      - MYSQL_ROOT_PASSWORD=secret123\n      - TZ=Asia/Shanghai' 'MYSQL_ROOT_PASSWORD'
expect "列表写法从 .env 读"                pass "$M" $'    environment:\n      - MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD:?x}\n      - TZ=Asia/Shanghai'

# 规则 3：Redis 的密码也得从 .env 读
expect "requirepass 后面写死了密码"        fail '"--requirepass", "${REDIS_PASSWORD:?在 docker/.env 里设置 REDIS_PASSWORD}"' \
                                                '"--requirepass", "secret123"' 'requirepass'

# 规则 6：每个服务都有内存上限的配置项，swap 与内存同值
expect "某个服务漏了 mem_limit"            fail $'    mem_limit: ${KIBANA_MEMORY:-0}\n    memswap_limit: ${KIBANA_MEMORY:-0}\n' '' 'kibana'
expect "memswap_limit 与 mem_limit 不同"   fail '    memswap_limit: ${REDIS_MEMORY:-0}' '    memswap_limit: -1' 'redis'
expect "新加的服务没写内存上限"            fail $'\nvolumes:\n' $'\n  extra:\n    image: busybox\n\nvolumes:\n' 'extra'

exit "$fails"
