#!/bin/bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$DIR/.env" ]; then
  set -a
  source "$DIR/.env"
  set +a
fi
if [ ! -x "$DIR/bin/sakura-vision-bridge" ] || [ "$DIR/Sources/SakuraVisionBridge.swift" -nt "$DIR/bin/sakura-vision-bridge" ]; then
  "$DIR/build.sh"
fi
exec "$DIR/bin/sakura-vision-bridge"
