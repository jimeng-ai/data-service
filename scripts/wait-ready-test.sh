#!/usr/bin/env bash
# scripts/wait-ready.sh 的测试：起一个本地 HTTP 服务，让它返回不同的状态码或者干脆不监听，看脚本的退出码。
set -euo pipefail
cd "$(dirname "$0")"
PORT=18765
status_file="$(mktemp)"
err_file="$(mktemp)"
server_pid=""
cleanup() { [ -n "$server_pid" ] && kill "$server_pid" 2>/dev/null || true; rm -f "$status_file" "$err_file"; }
trap cleanup EXIT

python3 - "$PORT" "$status_file" <<'PY' &
import http.server, sys
port, status_file = int(sys.argv[1]), sys.argv[2]
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        code = int((open(status_file).read().strip() or "200"))
        self.send_response(code)
        self.end_headers()
        self.wfile.write(b"x")
    def log_message(self, *args):
        pass
http.server.HTTPServer(("127.0.0.1", port), Handler).serve_forever()
PY
server_pid=$!
disown "$server_pid" 2>/dev/null || true   # 清理时 kill 它，不要让 bash 打印 "Terminated"
sleep 1

echo 200 > "$status_file"
bash wait-ready.sh "http://127.0.0.1:${PORT}/ready" 10 >/dev/null || { echo "FAIL: 200 应判为就绪"; exit 1; }
echo "ok   200 -> 就绪"

echo 503 > "$status_file"
if bash wait-ready.sh "http://127.0.0.1:${PORT}/ready" 4 2>"$err_file" >/dev/null; then
  echo "FAIL: 503 不应判为就绪"; exit 1
fi
grep -q "超时" "$err_file" || { echo "FAIL: 503 超时后应提示'超时'"; cat "$err_file"; exit 1; }
echo "ok   503 -> 超时判失败"

# 第三个参数给了期望的状态码时，只认这一个码：网关对未登录请求回 401 才算起来了，回 200 反而说明没在验签。
echo 401 > "$status_file"
bash wait-ready.sh "http://127.0.0.1:${PORT}/ready" 10 401 >/dev/null || { echo "FAIL: 期望 401、实际 401，应判为就绪"; exit 1; }
echo "ok   期望 401、实际 401 -> 就绪"

echo 200 > "$status_file"
if bash wait-ready.sh "http://127.0.0.1:${PORT}/ready" 4 401 2>"$err_file" >/dev/null; then
  echo "FAIL: 期望 401、实际 200，不应判为就绪"; exit 1
fi
grep -q "HTTP 200" "$err_file" || { echo "FAIL: 超时提示里应带上最后一次的状态码"; cat "$err_file"; exit 1; }
echo "ok   期望 401、实际 200 -> 超时判失败"

kill "$server_pid"; server_pid=""
if bash wait-ready.sh "http://127.0.0.1:${PORT}/ready" 4 2>/dev/null >/dev/null; then
  echo "FAIL: 没人监听不应判为就绪"; exit 1
fi
echo "ok   没人监听 -> 超时判失败"
echo "全部通过"
