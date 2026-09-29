#!/bin/bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"
MOCK_PID=""; BRIDGE_PID=""
cleanup(){
  [ -n "$BRIDGE_PID" ] && kill "$BRIDGE_PID" 2>/dev/null || true
  [ -n "$MOCK_PID" ] && kill "$MOCK_PID" 2>/dev/null || true
  rm -rf "$TMP"
}
trap cleanup EXIT

"$DIR/build.sh"
xcrun --sdk macosx swiftc "$DIR/Tools/MakeTestImage.swift" -o "$TMP/make-test-image" -framework AppKit
"$TMP/make-test-image" "$TMP/test.jpg" >/dev/null

MOCK_LLAMA_PORT=18081 MOCK_LLAMA_LOG="$TMP/mock.jsonl" python3 "$DIR/Tools/MockLlamaServer.py" &
MOCK_PID=$!
for _ in {1..30}; do
  curl -fsS http://127.0.0.1:18081/health >/dev/null 2>&1 && break
  sleep 0.1
done

BRIDGE_PORT=18090 \
BRIDGE_KEY=test-key \
TRANSLATE_URL=http://127.0.0.1:18081/v1/chat/completions \
LLAMA_TOKEN_COUNT_URL=http://127.0.0.1:18081/v1/chat/completions/input_tokens \
TRANSLATE_MODEL=Sakura-GalTransl-7B-v3.7 \
COMPACT_URL=http://127.0.0.1:18081/v1/chat/completions \
COMPACT_MODEL=Sakura-GalTransl-7B-v3.7 \
LLAMA_CTX_SIZE=1024 OUTPUT_RESERVE_TOKENS=128 CONTEXT_SAFETY_TOKENS=128 \
COMPACT_TRIGGER_RATIO=0.30 COMPACT_HARD_RATIO=0.90 \
RECENT_HISTORY_ENTRIES=1 HARD_RECENT_HISTORY_ENTRIES=1 \
SESSION_DIR="$TMP/sessions" SESSION_PERSIST=true \
PROMPT_VERSION=sakura-v3.7-mw-phase-b-session-001 \
"$DIR/bin/sakura-vision-bridge" >"$TMP/bridge.log" 2>&1 &
BRIDGE_PID=$!

for _ in {1..40}; do
  curl -fsS http://127.0.0.1:18090/health >"$TMP/health.json" 2>/dev/null && break
  sleep 0.15
done

python3 - <<PY
import json
j=json.load(open('$TMP/health.json'))
assert j['ok'] and j['phase']=='B', j
assert j['prompt_version']=='sakura-v3.7-mw-phase-b-session-001', j
PY

call_bridge(){
  curl -fsS -X POST http://127.0.0.1:18090/translate-image \
    -H 'Content-Type: image/jpeg' \
    -H 'X-Bridge-Key: test-key' \
    -H 'X-Game-ID: e2e-game' \
    -H 'X-Session-ID: e2e-session' \
    -H 'X-Scene-ID: scene-1' \
    -H 'X-Context-Enabled: true' \
    -H 'X-Force-Retranslate: true' \
    --data-binary "@$TMP/test.jpg"
}

call_bridge >"$TMP/r1.json"
call_bridge >"$TMP/r2.json"
call_bridge >"$TMP/r3.json"

python3 - <<PY
import json,glob
r1=json.load(open('$TMP/r1.json'))
r2=json.load(open('$TMP/r2.json'))
r3=json.load(open('$TMP/r3.json'))
for r in (r1,r2,r3):
    assert r['ok'] and r['translation']=='你好。', r
    assert 'llama_cache_hit_ratio' in r['meta'], r
assert r3['meta']['compacted'] is True, r3
assert r3['meta']['compact_count'] >= 1, r3
assert glob.glob('$TMP/sessions/*.json'), 'session file missing'
print('Phase B E2E OK:', {
    'compact_count':r3['meta']['compact_count'],
    'cache_hit_ratio':r3['meta']['llama_cache_hit_ratio'],
    'context_tokens':r3['meta']['context_input_tokens']
})
PY

grep -q '"cache_prompt": true' "$TMP/mock.jsonl"
grep -q '翻译会话压缩器' "$TMP/mock.jsonl"
