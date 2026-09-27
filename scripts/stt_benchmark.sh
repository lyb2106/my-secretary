#!/usr/bin/env bash
# Runs whisper-cli (same whisper.cpp tag and decoding settings as the app) over testdata/
# for several models and writes a Markdown report with per-file CER.
#
# Usage: scripts/stt_benchmark.sh WHISPER_CLI MODEL_DIR VAD_MODEL OUT_DIR model1.bin [model2.bin ...]
set -euo pipefail

CLI=$1; MODEL_DIR=$2; VAD=$3; OUT=$4; shift 4
MODELS=("$@")
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$OUT/wav"

# Optional whitelist vocabulary (testdata/whitelist.tsv: wrong<TAB>right) → initial prompt.
PROMPT=""
if [[ -f "$ROOT/testdata/whitelist.tsv" ]]; then
  PROMPT=$(cut -f2 "$ROOT/testdata/whitelist.tsv" | grep -v '^$' | sort -u | paste -sd, - | sed 's/,/, /g')
fi

REPORT="$OUT/report.md"
{
  echo "# STT benchmark"
  echo
  echo "whisper.cpp $(git -C "$ROOT/third_party/whisper.cpp" describe --tags 2>/dev/null || echo unknown) · beam 5 · language ko · suppress-nst · VAD(silero, min silence 500 ms, pad 200 ms) · 4 threads"
  [[ -n "$PROMPT" ]] && echo "· initial prompt: \`$PROMPT\`"
  echo
  echo "CER = 문자 편집거리 / 정답 문자 수 (공백·문장부호 제거, NFC). 처리 시간은 CI x86 러너 기준이며 기기 성능과 무관."
  echo
  echo "| 파일 | 길이(s) | 모델 | CER | 오류/정답 문자 | 처리(s) |"
  echo "|---|---|---|---|---|---|"
} > "$REPORT"

DETAILS="$OUT/details.md"
echo "## 전사 결과" > "$DETAILS"

for audio in "$ROOT"/testdata/audio/*; do
  id=$(basename "${audio%.*}")
  ref="$ROOT/testdata/transcript/$id.txt"
  [[ -f "$ref" ]] || { echo "skip $id (no transcript)"; continue; }
  wav="$OUT/wav/$id.wav"
  ffmpeg -loglevel error -y -i "$audio" -ar 16000 -ac 1 -c:a pcm_s16le "$wav"
  dur=$(python3 -c "import wave;w=wave.open('$wav');print(f'{w.getnframes()/w.getframerate():.1f}')")
  printf '\n### %s\n\n**정답**\n```\n%s\n```\n' "$id" "$(cat "$ref")" >> "$DETAILS"

  for model in "${MODELS[@]}"; do
    name="${model%.bin}"
    hyp="$OUT/$id.$name"
    args=(-m "$MODEL_DIR/$model" -f "$wav" -l ko -bs 5 -bo 5 -t 4 -nt -sns -np
          --vad -vm "$VAD" -vsd 500 -vp 200 -otxt -of "$hyp")
    [[ -n "$PROMPT" ]] && args+=(--prompt "$PROMPT" --carry-initial-prompt)
    start=$(date +%s.%N)
    "$CLI" "${args[@]}" > /dev/null 2> "$hyp.log"
    secs=$(python3 -c "import time;print(f'{time.time()-$start:.1f}')")
    read -r cer dist reflen < <(python3 "$ROOT/scripts/cer.py" "$ref" "$hyp.txt")
    pct=$(python3 -c "print(f'{$cer*100:.1f}%')")
    echo "| $id | $dur | $name | $pct | $dist/$reflen | $secs |" >> "$REPORT"
    printf '\n**%s** (CER %s)\n```\n%s\n```\n' "$name" "$pct" "$(cat "$hyp.txt")" >> "$DETAILS"
  done
done

echo >> "$REPORT"
cat "$DETAILS" >> "$REPORT"
cat "$REPORT"
