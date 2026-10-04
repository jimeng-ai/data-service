#!/usr/bin/env bash
# .github/workflows/deploy.yml 的结构检查（就绪检查步骤本身的行为由 wait-ready-test.sh 测）。
#   1. 部署 job 和就绪检查都有时间上限。五个仓库的部署共用一台自托管 runner，Docker 卡住时没有上限的 job
#      会一直挂着（GitHub 默认 6 小时），期间哪个仓库都部署不了。
#   2. 网关检查要反复探到 401 为止，不能只探一次：网关可能比 data-server 起得晚，只探一次会把正常部署判成失败。
#   3. if: always() 的收尾步骤也调 Docker，同样要有时间上限，不然就绪检查判失败之后它们还会挂到 job 的上限。
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
import re, sys, yaml
wf = yaml.safe_load(open(".github/workflows/deploy.yml", encoding="utf-8"))
job = wf["jobs"]["deploy"]
steps = {s.get("name"): s for s in job["steps"]}
fails = []
def check(cond, msg):
    print(("ok   " if cond else "FAIL ") + msg)
    if not cond:
        fails.append(msg)

t = job.get("timeout-minutes")
check(isinstance(t, int) and 0 < t <= 60, f"部署 job 有时间上限（不超过 60 分钟），现在是 {t}")
ready = steps["Readiness check"]
t = ready.get("timeout-minutes")
check(isinstance(t, int) and 0 < t <= 10, f"就绪检查有时间上限（不超过 10 分钟），现在是 {t}")
check(re.search(r'wait-ready\.sh\s+"?http://127\.0\.0\.1:20011/data/admin/auth/me"?\s+\d+\s+401\b', ready["run"]) is not None,
      "网关检查用 wait-ready.sh 反复探，直到对未登录请求回 401")
# 就绪检查超时之后，if: always() 的收尾步骤照样会跑；它们也调 Docker，Docker 卡住时同样会挂到 job 的上限
for s in job["steps"]:
    if str(s.get("if", "")).strip() == "always()":
        t = s.get("timeout-minutes")
        check(isinstance(t, int) and 0 < t <= 5, f"收尾步骤「{s.get('name')}」有时间上限（不超过 5 分钟），现在是 {t}")
sys.exit(1 if fails else 0)
PY
