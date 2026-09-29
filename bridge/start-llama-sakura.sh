#!/bin/bash
set -euo pipefail

LLAMA_SERVER="\${LLAMA_SERVER:-llama-server}"
MODEL="\${SAKURA_MODEL:-\${1:-}}"
if [ -z "$MODEL" ]; then
  echo "usage: SAKURA_MODEL=/path/to/model.gguf $0"
  echo "   or: $0 /path/to/model.gguf"
  exit 2
fi

HOST="\${LLAMA_HOST:-127.0.0.1}"
PORT="\${LLAMA_PORT:-8080}"
CTX="\${LLAMA_CTX_SIZE:-8192}"
GPU_LAYERS="\${LLAMA_GPU_LAYERS:-99}"
PARALLEL="\${LLAMA_PARALLEL:-1}"
ALIAS="\${LLAMA_MODEL_ALIAS:-Sakura-GalTransl-7B-v3.7}"
SLOT_DIR="\${LLAMA_SLOT_SAVE_PATH:-$HOME/Library/Application Support/SakuraVisionBridge/llama-slots}"
mkdir -p "$SLOT_DIR"

exec "$LLAMA_SERVER" \
  -m "$MODEL" \
  --alias "$ALIAS" \
  --host "$HOST" \
  --port "$PORT" \
  -ngl "$GPU_LAYERS" \
  -c "$CTX" \
  -np "$PARALLEL" \
  --flash-attn on \
  --cache-prompt \
  --slots \
  --slot-save-path "$SLOT_DIR" \
  --no-context-shift \
  --metrics \
  --jinja \
  --reasoning off
