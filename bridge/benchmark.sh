#!/bin/bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ $# -lt 1 ]; then echo "usage: $0 <image> [runs]"; exit 2; fi
if [ -f "$DIR/.env" ]; then set -a; source "$DIR/.env"; set +a; fi
IMAGE="$1"
RUNS="${2:-3}"
PORT="${BRIDGE_PORT:-8090}"
HOST="${BRIDGE_TEST_HOST:-127.0.0.1}"
KEY="${BRIDGE_KEY:-}"

printf "%-4s %-8s %-11s %-8s %-13s %-9s %-10s %-6s\n" "run" "ocr_ms" "model_ms" "retry_ms" "validation_ms" "total_ms" "max_tokens" "retry"
for i in $(seq 1 "$RUNS"); do
  TMP="$(mktemp)"
  ARGS=(
    --connect-timeout 3 --max-time 120 -fsS -X POST
    "http://$HOST:$PORT/translate-image"
    -H "Content-Type: image/jpeg"
    -H "X-Game-ID: benchmark"
    -H "X-Session-ID: benchmark-$i"
    -H "X-Scene-ID: benchmark"
    -H "X-Context-Enabled: false"
    -H "X-Force-Retranslate: true"
    --data-binary "@$IMAGE"
  )
  if [ -n "$KEY" ]; then ARGS+=( -H "X-Bridge-Key: $KEY" ); fi
  curl "${ARGS[@]}" > "$TMP"
  python3 - "$i" "$TMP" <<'PY'
import json, sys
run=sys.argv[1]
with open(sys.argv[2], encoding="utf-8") as f:
    j=json.load(f)
m=j.get("meta", {})
print(f"{run:<4} {str(m.get('ocr_ms','-')):<8} {str(m.get('model_first_ms','-')):<11} {str(m.get('retry_ms','-')):<8} {str(m.get('validation_ms','-')):<13} {str(m.get('total_ms',m.get('latency_ms','-'))):<9} {str(m.get('max_tokens','-')):<10} {m.get('retry_count','-')}")
PY
  rm -f "$TMP"
done

echo
echo "说明：第一次通常包含模型冷启动；若 retry_ms 经常非 0，说明仍在频繁触发二次推理。"
