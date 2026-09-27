#!/usr/bin/env bash
# Downloads the ggml models bundled into the APK (app/src/main/assets/models/).
# Model names come from gradle.properties (whisperModel, vadModel).
set -euo pipefail

cd "$(dirname "$0")/.."
DEST=app/src/main/assets/models
mkdir -p "$DEST"

prop() { grep -E "^$1=" gradle.properties | cut -d= -f2 | tr -d '[:space:]'; }
WHISPER_MODEL=${WHISPER_MODEL:-$(prop whisperModel)}
VAD_MODEL=${VAD_MODEL:-$(prop vadModel)}

fetch() {
  local url=$1 out=$2
  if [[ -s "$out" ]]; then
    echo "exists: $out"
    return
  fi
  echo "downloading $url"
  curl -fL --retry 4 --retry-delay 5 -o "$out.part" "$url"
  mv "$out.part" "$out"
}

fetch "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${WHISPER_MODEL}" "$DEST/${WHISPER_MODEL}"
fetch "https://huggingface.co/ggml-org/whisper-vad/resolve/main/${VAD_MODEL}" "$DEST/${VAD_MODEL}"
ls -la "$DEST"
