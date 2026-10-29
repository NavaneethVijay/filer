#!/bin/sh
# Starts the Mac embedder and lets a USB-connected phone reach it at 127.0.0.1:9379.
set -e
cd "$(dirname "$0")"
PORT="${PORT:-9379}"
MODEL="models/embeddinggemma-2-740m.litertlm"

if [ ! -f "$MODEL" ]; then
  echo "Downloading the model (485 MB)…"
  mkdir -p models
  curl -L --fail -o "$MODEL.part" \
    "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/main/embeddinggemma-2-740m.litertlm"
  mv "$MODEL.part" "$MODEL"
fi

# adb drops the reverse port when the phone reconnects or the app is reinstalled: keep re-adding it.
adb reverse "tcp:$PORT" "tcp:$PORT" && echo "Phone can reach the Mac at 127.0.0.1:$PORT"
( while sleep 5; do adb reverse "tcp:$PORT" "tcp:$PORT" >/dev/null 2>&1; done ) &
KEEPER=$!
trap 'kill $KEEPER 2>/dev/null' EXIT INT TERM
uv run python server.py --port "$PORT" "$@"
