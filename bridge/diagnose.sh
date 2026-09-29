#!/bin/bash
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$DIR/.env" ]; then set -a; source "$DIR/.env"; set +a; fi
PORT="${BRIDGE_PORT:-8090}"
HOST="${BRIDGE_TEST_HOST:-127.0.0.1}"

echo "== Sakura Vision Bridge diagnose =="
echo "dir: $DIR"
echo "bridge: $HOST:$PORT"
echo "model: ${TRANSLATE_MODEL:-unset}"
echo "prompt: ${PROMPT_VERSION:-default}"

echo
echo "-- binary --"
if [ -x "$DIR/bin/sakura-vision-bridge" ]; then file "$DIR/bin/sakura-vision-bridge" || true; else echo "missing: bin/sakura-vision-bridge"; fi

echo
echo "-- process --"
pgrep -fl sakura-vision-bridge || echo "not running"

echo
echo "-- listen --"
lsof -nP -iTCP:"$PORT" -sTCP:LISTEN || echo "port $PORT not listening"

echo
echo "-- health --"
"$DIR/health.sh" || echo "health failed"

echo
echo "-- upstream TCP --"
python3 - "${TRANSLATE_URL:-http://127.0.0.1:8080/v1/chat/completions}" <<'PY'
import socket, sys
from urllib.parse import urlparse
u=urlparse(sys.argv[1])
host=u.hostname or "127.0.0.1"
port=u.port or (443 if u.scheme=="https" else 80)
try:
    with socket.create_connection((host, port), timeout=2):
        print(f"reachable: {host}:{port}")
except Exception as e:
    print(f"unreachable: {host}:{port}: {e}")
PY
