#!/usr/bin/env bash
# deploy.sh infra 的门禁测试：compose 文件没通过 scripts/check-compose.sh 时，必须在 docker compose up 之前就停下。
# 在临时目录里放一份 deploy.sh + 检查脚本 + compose，docker / lsof / pgrep 换成只记录命令的桩，不碰真容器。
set -euo pipefail
cd "$(dirname "$0")/.."
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin" "$TMP/home"
cat > "$TMP/bin/docker" <<'EOF'
#!/usr/bin/env bash
echo "docker $*" >> "$DOCKER_LOG"
[ "${1:-}" = "--version" ] && echo "Docker version (stub)"
exit 0
EOF
printf '#!/usr/bin/env bash\nexit 1\n' > "$TMP/bin/lsof"     # 端口都空闲
printf '#!/usr/bin/env bash\nexit 1\n' > "$TMP/bin/pgrep"    # 没有宿主机版 Nacos
chmod +x "$TMP/bin/"*

# $1 说明，$2 compose 文件，$3 期望 up（会执行 docker compose up）/ refuse（拒绝启动）
fails=0
run_case() {
  local desc=$1 compose=$2 want=$3 proj="$TMP/proj" rc=0 out
  rm -rf "$proj"; mkdir -p "$proj/scripts" "$proj/docker"
  cp deploy.sh "$proj/"; cp scripts/check-compose.sh "$proj/scripts/"
  cp "$compose" "$proj/docker/docker-compose.yml"; cp docker/docker-compose.dev.yml "$proj/docker/"
  : > "$proj/docker/.env"
  : > "$TMP/docker.log"
  out="$(PATH="$TMP/bin:$PATH" HOME="$TMP/home" DOCKER_LOG="$TMP/docker.log" bash "$proj/deploy.sh" infra 2>&1)" || rc=$?
  local did_up=no
  grep -q 'compose.* up -d' "$TMP/docker.log" && did_up=yes
  if { [ "$want" = up ] && [ "$rc" = 0 ] && [ "$did_up" = yes ]; } ||
     { [ "$want" = refuse ] && [ "$rc" != 0 ] && [ "$did_up" = no ] && grep -q 'check-compose' <<<"$out"; }; then
    echo "ok   ${desc}"
  else
    echo "FAIL ${desc}（退出码 ${rc}，执行过 compose up：${did_up}）"
    printf '%s\n' "$out" | tail -8 | sed 's/^/       /'
    fails=1
  fi
}

run_case "compose 合规：照常起基础设施" docker/docker-compose.yml up
sed 's#"127.0.0.1:8848:8848"#"8848:8848"#' docker/docker-compose.yml > "$TMP/bad.yml"
run_case "端口绑了所有网卡：compose up 之前就拒绝" "$TMP/bad.yml" refuse

exit "$fails"
