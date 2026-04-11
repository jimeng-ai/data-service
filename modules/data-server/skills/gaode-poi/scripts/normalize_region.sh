#!/usr/bin/env bash
set -euo pipefail

# Normalize a simple region alias to a canonical Chinese region name.
# Usage: ./normalize_region.sh "shanghai xuhui"

input="${1:-}"
key="$(echo "$input" | tr '[:upper:]' '[:lower:]' | xargs)"

case "$key" in
  "shanghai"|"shanghai xuhui"|"xuhui")
    echo "上海市徐汇区"
    ;;
  "hangzhou"|"hangzhou binjiang"|"binjiang")
    echo "杭州市滨江区"
    ;;
  *)
    echo "$input"
    ;;
esac
