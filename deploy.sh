#!/usr/bin/env bash
#
# data-service 一键部署脚本 (macOS)
#
# 作用：
#   1. 检查并安装宿主环境：Xcode CLT / Homebrew / JDK 17 / Maven / Docker Desktop
#   2. 用 docker-compose 起基础设施：Nacos / MySQL / Redis / RabbitMQ / Elasticsearch
#   3. 执行 mvn clean install -DskipTests
#
# 用法：
#   ./deploy.sh              # 全流程
#   ./deploy.sh check        # 只检查环境，不安装、不启动
#   ./deploy.sh infra        # 只起/重启基础设施
#   ./deploy.sh build        # 只跑 mvn clean install
#   ./deploy.sh down         # 停止并清理基础设施容器（保留数据卷）
#
set -euo pipefail

# ---------- 配置 ----------
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${PROJECT_ROOT}/docker/docker-compose.yml"
REQUIRED_JDK_MAJOR=17
REQUIRED_MAVEN_MAJOR=3
REQUIRED_MAVEN_MINOR=6

# 端口占用检查列表
PORTS_TO_CHECK=(8848 9848 3306 6379 5672 15672 9200 9300)

# ---------- 美化输出 ----------
if [[ -t 1 ]]; then
  C_RESET=$'\033[0m'; C_RED=$'\033[31m'; C_GREEN=$'\033[32m'
  C_YELLOW=$'\033[33m'; C_BLUE=$'\033[34m'; C_BOLD=$'\033[1m'
else
  C_RESET=''; C_RED=''; C_GREEN=''; C_YELLOW=''; C_BLUE=''; C_BOLD=''
fi
log()   { printf "%s[INFO]%s  %s\n"  "$C_BLUE"   "$C_RESET" "$*"; }
ok()    { printf "%s[ OK ]%s  %s\n"  "$C_GREEN"  "$C_RESET" "$*"; }
warn()  { printf "%s[WARN]%s  %s\n"  "$C_YELLOW" "$C_RESET" "$*"; }
err()   { printf "%s[FAIL]%s  %s\n"  "$C_RED"    "$C_RESET" "$*" >&2; }
step()  { printf "\n%s==> %s%s\n"    "$C_BOLD"   "$*" "$C_RESET"; }

# ---------- 前置检查 ----------
require_macos() {
  if [[ "$(uname -s)" != "Darwin" ]]; then
    err "这个脚本只支持 macOS。当前系统：$(uname -s)"
    exit 1
  fi
}

ARCH="$(uname -m)"   # arm64 / x86_64
BREW_PREFIX_DEFAULT="/opt/homebrew"
[[ "$ARCH" == "x86_64" ]] && BREW_PREFIX_DEFAULT="/usr/local"

# ---------- Xcode Command Line Tools ----------
ensure_xcode_clt() {
  step "检查 Xcode Command Line Tools"
  if xcode-select -p >/dev/null 2>&1; then
    ok "已安装：$(xcode-select -p)"
    return
  fi
  warn "未安装 Xcode CLT，正在触发安装弹窗..."
  xcode-select --install || true
  log "请在弹窗中点击「安装」，安装完成后重新运行本脚本。"
  exit 1
}

# ---------- Homebrew ----------
ensure_brew() {
  step "检查 Homebrew"
  if command -v brew >/dev/null 2>&1; then
    ok "已安装：$(brew --version | head -1)"
    return
  fi
  log "未安装 Homebrew，开始安装..."
  /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
  # 把 brew 加进当前 shell 的 PATH
  if [[ -x "${BREW_PREFIX_DEFAULT}/bin/brew" ]]; then
    eval "$("${BREW_PREFIX_DEFAULT}/bin/brew" shellenv)"
  fi
  ok "Homebrew 安装完成：$(brew --version | head -1)"
}

# ---------- JDK 17 ----------
current_java_major() {
  command -v java >/dev/null 2>&1 || { echo ""; return 0; }
  # 注意：macOS 自带的 /usr/bin/java 在没装 JDK 时会以非 0 退出；
  # 配合 set -euo pipefail 会让外层 major="$(...)" 静默挂掉。
  # 这里吞掉非 0，保证函数始终返回 0，由调用方根据输出是否为空判断。
  local out
  out="$(java -version 2>&1 || true)"
  echo "$out" | awk -F\" '/version/ {print $2}' | awk -F. '{print ($1=="1"?$2:$1)}' || true
  return 0
}

ensure_jdk() {
  step "检查 JDK ${REQUIRED_JDK_MAJOR}"
  local major
  major="$(current_java_major)"
  if [[ -n "$major" && "$major" -ge "$REQUIRED_JDK_MAJOR" ]]; then
    ok "已安装 JDK ${major}：$(java -version 2>&1 | head -1)"
    return
  fi
  log "未检测到 JDK ${REQUIRED_JDK_MAJOR}+，使用 Homebrew 安装 Temurin 17..."
  brew install --cask temurin@17
  # 等待 java_home 可识别
  if /usr/libexec/java_home -v "${REQUIRED_JDK_MAJOR}" >/dev/null 2>&1; then
    local jhome
    jhome="$(/usr/libexec/java_home -v "${REQUIRED_JDK_MAJOR}")"
    export JAVA_HOME="$jhome"
    export PATH="$JAVA_HOME/bin:$PATH"
    ok "JDK 安装完成，JAVA_HOME=${JAVA_HOME}"
    log "建议把下面这行写进 ~/.zshrc："
    log "  export JAVA_HOME=\$(/usr/libexec/java_home -v ${REQUIRED_JDK_MAJOR})"
  else
    err "安装 JDK 后仍找不到 java_home，请手动检查"
    exit 1
  fi
}

# ---------- Maven ----------
ensure_maven() {
  step "检查 Maven"
  if command -v mvn >/dev/null 2>&1; then
    local v
    v="$(mvn -v | awk '/Apache Maven/{print $3; exit}')"
    ok "已安装 Maven ${v}"
    return
  fi
  log "未安装 Maven，使用 Homebrew 安装..."
  brew install maven
  ok "Maven 安装完成：$(mvn -v | head -1)"
}

# ---------- Docker Desktop ----------
ensure_docker() {
  step "检查 Docker"
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    ok "Docker 已运行：$(docker --version)"
    return
  fi
  if ! command -v docker >/dev/null 2>&1; then
    log "未安装 Docker Desktop，使用 Homebrew 安装..."
    brew install --cask docker
  fi
  # 启动 Docker Desktop
  if ! docker info >/dev/null 2>&1; then
    log "启动 Docker Desktop..."
    open -a Docker || true
    log "等待 Docker daemon 就绪（最多 120 秒）..."
    local i=0
    while ! docker info >/dev/null 2>&1; do
      ((i++))
      if (( i > 60 )); then
        err "Docker 在 120 秒内未启动。请手动打开 Docker Desktop 后重试。"
        exit 1
      fi
      sleep 2
    done
  fi
  ok "Docker 已就绪：$(docker --version)"
}

# ---------- 端口占用检查 ----------
check_ports() {
  step "检查端口占用（不会终止占用进程，只提示）"
  local conflict=0
  for p in "${PORTS_TO_CHECK[@]}"; do
    if lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then
      # 如果是我们自己的 docker 容器占用，视为正常
      local who
      who="$(lsof -nP -iTCP:"$p" -sTCP:LISTEN 2>/dev/null | awk 'NR==2{print $1}')"
      if [[ "$who" == "com.docke" || "$who" == "docker" || "$who" == "vpnkit" ]]; then
        ok "端口 ${p} 已被 Docker 占用（应该是 compose 起的服务）"
      else
        warn "端口 ${p} 被进程占用：${who}（可能与 compose 冲突）"
        conflict=1
      fi
    else
      ok "端口 ${p} 空闲"
    fi
  done
  return 0
}

# ---------- docker compose 工具兼容 ----------
compose() {
  if docker compose version >/dev/null 2>&1; then
    docker compose -f "$COMPOSE_FILE" "$@"
  else
    docker-compose -f "$COMPOSE_FILE" "$@"
  fi
}

# ---------- 起基础设施 ----------
start_infra() {
  step "启动基础设施（Nacos / MySQL / Redis / RabbitMQ / Elasticsearch）"
  compose up -d
  log "等待容器健康检查通过（最多 5 分钟）..."
  local i=0
  while :; do
    local unhealthy
    unhealthy="$(docker ps --filter "name=ds-" --format '{{.Names}}\t{{.Status}}' | awk '/(starting|unhealthy)/{print $1}')"
    if [[ -z "$unhealthy" ]]; then
      ok "所有容器健康检查通过"
      break
    fi
    ((i++))
    if (( i > 60 )); then
      warn "等待超时，仍未健康的容器：${unhealthy}"
      warn "用 'docker logs <容器名>' 查看详情。脚本继续执行。"
      break
    fi
    sleep 5
  done
  printf "\n%s基础设施访问入口:%s\n" "$C_BOLD" "$C_RESET"
  cat <<EOF
  - Nacos      : http://localhost:8848/nacos        (无鉴权 / 默认)
  - MySQL      : localhost:3306    root / root123456
  - Redis      : localhost:6379    无密码
  - RabbitMQ   : http://localhost:15672              admin / admin123456
  - Elasticsearch : http://localhost:9200

EOF
}

stop_infra() {
  step "停止并清理基础设施容器（保留数据卷）"
  compose down
  ok "已停止。数据卷保留，下次 up 时会复用。"
}

# ---------- Maven 构建 ----------
mvn_build() {
  step "执行 mvn clean install -DskipTests"
  cd "$PROJECT_ROOT"
  mvn clean install -DskipTests
  ok "Maven 构建完成"
}

# ---------- 检查模式 ----------
check_only() {
  step "环境检查（不安装）"
  local fail=0

  if [[ "$(uname -s)" == "Darwin" ]]; then ok "macOS: $(sw_vers -productVersion)"; else err "非 macOS"; fail=1; fi
  if xcode-select -p >/dev/null 2>&1; then ok "Xcode CLT 已装"; else warn "Xcode CLT 未装"; fail=1; fi
  if command -v brew >/dev/null 2>&1; then ok "Homebrew: $(brew --version | head -1)"; else warn "Homebrew 未装"; fail=1; fi

  local major; major="$(current_java_major)"
  if [[ -n "$major" && "$major" -ge "$REQUIRED_JDK_MAJOR" ]]; then
    ok "JDK ${major}: $(java -version 2>&1 | head -1)"
  else
    warn "JDK ${REQUIRED_JDK_MAJOR}+ 未装（当前: ${major:-none}）"; fail=1
  fi

  if command -v mvn >/dev/null 2>&1; then ok "Maven: $(mvn -v | awk '/Apache Maven/{print $3; exit}')"; else warn "Maven 未装"; fail=1; fi

  if command -v docker >/dev/null 2>&1; then
    if docker info >/dev/null 2>&1; then ok "Docker 运行中：$(docker --version)"
    else warn "Docker 已装但未运行"; fail=1; fi
  else
    warn "Docker 未装"; fail=1
  fi

  check_ports

  echo
  if (( fail == 0 )); then ok "环境齐备，可以直接 './deploy.sh' 一把梭"
  else warn "上面有项目未就绪，运行 './deploy.sh' 会自动补齐"; fi
}

# ---------- 主流程 ----------
main() {
  local cmd="${1:-all}"
  require_macos
  case "$cmd" in
    check)
      check_only
      ;;
    infra)
      ensure_docker
      check_ports
      start_infra
      ;;
    build)
      ensure_jdk
      ensure_maven
      mvn_build
      ;;
    down)
      ensure_docker
      stop_infra
      ;;
    all|"")
      ensure_xcode_clt
      ensure_brew
      ensure_jdk
      ensure_maven
      ensure_docker
      check_ports
      start_infra
      mvn_build
      echo
      ok "全部完成。下一步建议："
      cat <<EOF
  1. 打开 http://localhost:8848/nacos ，在命名空间 fe9e39ae-06af-49c3-9c5b-6060df2cf93e
     下导入下列 data-id 配置（参考 nacos_config/ 目录里的历史样例）：
       - gateway.yml
       - data-server.yml
       - sys-server.yml
       - default-mysql.yml / default-redis.yml / default-rabbitmq.yml
       - default-okhttp.yml / knife4j.yml
  2. 启动服务（按顺序）：
       mvn -pl modules/data-server -am spring-boot:run
       mvn -pl gateway -am spring-boot:run
EOF
      ;;
    *)
      err "未知子命令：$cmd"
      echo "用法：$0 [check|infra|build|down|all]"
      exit 1
      ;;
  esac
}

main "$@"
