#!/bin/bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$DIR/.env" ]; then
  set -a; source "$DIR/.env"; set +a
fi
PORT="${BRIDGE_PORT:-8090}"
HOST="${BRIDGE_TEST_HOST:-127.0.0.1}"
curl --connect-timeout 2 --max-time 5 -fsS "http://$HOST:$PORT/health"
echo
