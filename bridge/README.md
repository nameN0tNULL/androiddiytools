# Sakura Vision Bridge — Phase B Session + Compact

API stays compatible with the APK:

- `GET /health`
- `POST /translate-image`
- `POST /reset-context`

Phase B adds durable `game_id + session_id` sessions, exact llama.cpp token counting, automatic compact, prompt-cache metrics, and persisted Session JSON.

## Start llama.cpp

```bash
SAKURA_MODEL=/path/to/Sakura-GalTransl-7B-v3.7.gguf ./start-llama-sakura.sh
```

## Start Bridge

```bash
cp .env.example .env
./build.sh
./run.sh
```

## E2E

```bash
./e2e-phase-b.sh
```

It runs the real Bridge + Vision OCR against a mock llama.cpp-compatible server and verifies compact, cache metrics, and session persistence.
