#!/bin/bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ $# -lt 1 ]; then
  echo "usage: $0 <image>"
  exit 2
fi
if [ -f "$DIR/.env" ]; then
  set -a; source "$DIR/.env"; set +a
fi
PORT="${BRIDGE_PORT:-8090}"
HOST="${BRIDGE_TEST_HOST:-127.0.0.1}"
KEY="${BRIDGE_KEY:-}"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
ARGS=(
  --connect-timeout 3 --max-time 120 -fsS -X POST
  "http://$HOST:$PORT/translate-image"
  -H "Content-Type: image/jpeg"
  -H "X-Game-ID: selftest"
  -H "X-Session-ID: selftest"
  -H "X-Scene-ID: selftest"
  -H "X-Context-Enabled: false"
  -H "X-Force-Retranslate: true"
  --data-binary "@$1"
)
if [ -n "$KEY" ]; then ARGS+=( -H "X-Bridge-Key: $KEY" ); fi
curl "${ARGS[@]}" > "$TMP"
if command -v python3 >/dev/null 2>&1; then python3 -m json.tool "$TMP"; else cat "$TMP"; echo; fi
