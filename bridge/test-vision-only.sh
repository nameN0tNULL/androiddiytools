#!/bin/bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ $# -lt 1 ]; then echo "usage: $0 <image>"; exit 2; fi
if ! command -v xcrun >/dev/null 2>&1; then echo "未找到 xcrun。请先运行: xcode-select --install"; exit 1; fi
OUT="$DIR/bin/vision-ocr-test"
mkdir -p "$DIR/bin"
if [ ! -x "$OUT" ] || [ "$DIR/Tools/VisionOCRTest.swift" -nt "$OUT" ]; then
  xcrun --sdk macosx swiftc "$DIR/Tools/VisionOCRTest.swift" -o "$OUT" -framework Vision -framework AppKit
fi
exec "$OUT" "$1"
