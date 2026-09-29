#!/bin/bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$DIR/.env" ]; then set -a; source "$DIR/.env"; set +a; fi
PORT="${BRIDGE_PORT:-8090}"
HOST="${BRIDGE_TEST_HOST:-127.0.0.1}"
KEY="${BRIDGE_KEY:-}"
GAME="${1:-selftest}"
SESSION="${2:-selftest}"
SCENE="${3:-selftest}"
ARGS=(
  --connect-timeout 2 --max-time 5 -fsS -X POST
  "http://$HOST:$PORT/reset-context"
  -H "Content-Type: application/json"
  -H "X-Game-ID: $GAME"
  -H "X-Session-ID: $SESSION"
  -H "X-Scene-ID: $SCENE"
  --data-binary '{}'
)
if [ -n "$KEY" ]; then ARGS+=( -H "X-Bridge-Key: $KEY" ); fi
curl "${ARGS[@]}"
echo
