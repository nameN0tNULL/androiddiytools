#!/bin/bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$DIR/bin"

if ! command -v xcrun >/dev/null 2>&1; then
  echo "未找到 xcrun。请先运行: xcode-select --install"
  exit 1
fi

xcrun --sdk macosx swiftc \
  -swift-version 5 \
  -O \
  -parse-as-library \
  "$DIR/Sources/SakuraVisionBridge.swift" \
  -o "$DIR/bin/sakura-vision-bridge" \
  -framework Vision \
  -framework AppKit \
  -framework Network

chmod +x "$DIR/bin/sakura-vision-bridge"
echo "Built: $DIR/bin/sakura-vision-bridge"
