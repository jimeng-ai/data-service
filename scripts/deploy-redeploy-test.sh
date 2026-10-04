#!/usr/bin/env bash
# .github/workflows/deploy.yml 里 "Redeploy containers" 这一步的测试。
# 用 macOS 自带的 /bin/bash 3.2 跑（runner 上可能就是它），docker 换成只记录命令的桩函数，不碰真容器。
# 要点：仓库变量 DS_*_MEMORY 写错时，必须在删掉旧容器之前就失败——否则会停服，而且回滚到上个 tag 也同样失败。
set -euo pipefail
cd "$(dirname "$0")/.."
step="$(mktemp)"; log="$(mktemp)"
trap 'rm -f "$step" "$log"' EXIT
python3 - "$step" <<'PY'
import sys, yaml
wf = yaml.safe_load(open(".github/workflows/deploy.yml"))
step = next(s for s in wf["jobs"]["deploy"]["steps"] if s["name"] == "Redeploy containers")
open(sys.argv[1], "w").write(step["run"])
PY

run_case() {   # $1 = DS_DATA_SERVER_MEMORY, $2 = DS_GATEWAY_MEMORY；返回 step 的退出码，docker 命令记进 $log
  : > "$log"
  set +e
  DS_DATA_SERVER_MEMORY="$1" DS_GATEWAY_MEMORY="$2" NET=testnet NACOS_NS=ns LOG="$log" STEP="$step" \
    /bin/bash -c 'docker() { echo "docker $*" >> "$LOG"; }; source "$STEP"' >/dev/null 2>&1
  local rc=$?
  set -e
  return $rc
}

fails=0
expect() {   # $1 = 说明，$2 = 期望退出码 0/1，$3 = 期望是否执行过 docker rm（yes/no），其余 = 两个内存变量
  local desc=$1 want_rc=$2 want_rm=$3 rc=0 did_rm=no
  run_case "$4" "$5" || rc=$?
  grep -q '^docker rm ' "$log" && did_rm=yes
  if [ "$((rc != 0))" = "$want_rc" ] && [ "$did_rm" = "$want_rm" ]; then
    echo "ok   ${desc}"
  else
    echo "FAIL ${desc}（退出码 ${rc}，执行过 docker rm：${did_rm}）"; fails=1
  fi
}

expect "两个变量都不配：照常部署"            0 yes ""      ""
expect "合法值 2304m / 1g：照常部署"          0 yes "2304m" "1g"
expect "格式错 2.3g：删旧容器前失败"          1 no  "2.3g"  ""
expect "缺单位 2304：删旧容器前失败"          1 no  "2304"  ""
expect "gateway 格式错 1 GB：删旧容器前失败"  1 no  ""      "1 GB"
expect "太小 128m：删旧容器前失败"            1 no  "128m"  ""

exit "$fails"
