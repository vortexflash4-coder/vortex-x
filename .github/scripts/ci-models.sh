#!/usr/bin/env bash
# Downloads the on-device AI models into app/src/main/assets/ai before Gradle runs. They are
# hundreds of megabytes of binary, so they are fetched here instead of travelling in the source.
#
#   Voice separation: Spleeter 2-stems (Deezer, MIT), ONNX export from the sherpa-onnx project
#   Speech to text:   Whisper small, multilingual, 8-bit (OpenAI, MIT), ONNX export from sherpa-onnx
#
# It also fetches short test recordings for the unit tests (not packaged) and, when the
# sherpa-onnx Python package installs, writes reference results the tests compare against.
# On failure the partial models are removed, the app builds without AI and the summary says so.
#
# usage: ci-models.sh <project_dir>
set -euo pipefail

DIR="$(cd "${1:?project dir}" && pwd)"
OUT="$DIR/app/src/main/assets/ai"
TEST="$DIR/app/build/ai-test"
SEPARATION="https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models"
WHISPER="https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main"
WORK="$(mktemp -d "${RUNNER_TEMP:-/tmp}/vxmodels.XXXXXX")"
mkdir -p "$OUT" "$TEST"
trap 'rm -rf "$WORK"' EXIT
fail() {
  echo "::warning::$1"
  rm -f "$OUT"/*.onnx "$OUT"/whisper-tokens.txt
  exit 1
}
fetch() { curl -fsSL --retry 3 --retry-delay 5 -o "$2" "$1"; }
need() { # <file> <min bytes>
  local size
  size="$(wc -c < "$1" 2>/dev/null || echo 0)"
  [ "$size" -ge "$2" ] || fail "Model file $(basename "$1") is missing or too small ($size bytes)."
}

echo "AI models: voice separation (Spleeter 2-stems)"
fetch "$SEPARATION/sherpa-onnx-spleeter-2stems.tar.bz2" "$WORK/spleeter.tar.bz2" || fail "Could not download the Spleeter model."
tar -xjf "$WORK/spleeter.tar.bz2" -C "$WORK"
VOCALS="$(find "$WORK" -name '*vocals*.onnx' ! -name '*int8*' ! -name '*fp16*' | head -n 1)"
MUSIC="$(find "$WORK" -name '*accompaniment*.onnx' ! -name '*int8*' ! -name '*fp16*' | head -n 1)"
[ -n "$VOCALS" ] && [ -n "$MUSIC" ] || fail "The Spleeter archive does not contain the two expected models."
cp "$VOCALS" "$OUT/spleeter-vocals.onnx"
cp "$MUSIC" "$OUT/spleeter-accompaniment.onnx"
need "$OUT/spleeter-vocals.onnx" 5000000
need "$OUT/spleeter-accompaniment.onnx" 5000000

echo "AI models: speech to text (Whisper small, 8-bit)"
whisper_direct() {
  fetch "$WHISPER/small-encoder.int8.onnx" "$OUT/whisper-encoder.onnx" &&
    fetch "$WHISPER/small-decoder.int8.onnx" "$OUT/whisper-decoder.onnx" &&
    fetch "$WHISPER/small-tokens.txt" "$OUT/whisper-tokens.txt"
}
# Second source: the same files inside the project's release archive (larger download).
whisper_archive() {
  fetch "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2" "$WORK/whisper.tar.bz2" || return 1
  tar -xjf "$WORK/whisper.tar.bz2" -C "$WORK" || return 1
  rm -f "$WORK/whisper.tar.bz2"
  local enc dec tok
  enc="$(find "$WORK" -name 'small-encoder.int8.onnx' | head -n 1)"
  dec="$(find "$WORK" -name 'small-decoder.int8.onnx' | head -n 1)"
  tok="$(find "$WORK" -name 'small-tokens.txt' | head -n 1)"
  [ -n "$enc" ] && [ -n "$dec" ] && [ -n "$tok" ] || return 1
  cp "$enc" "$OUT/whisper-encoder.onnx" && cp "$dec" "$OUT/whisper-decoder.onnx" && cp "$tok" "$OUT/whisper-tokens.txt"
  local wav
  wav="$(find "$WORK" -path '*test_wavs*' -name '0.wav' | head -n 1)"
  [ -n "$wav" ] && cp "$wav" "$TEST/speech.wav"
  return 0
}
whisper_direct || whisper_archive || fail "Could not download the Whisper model."
need "$OUT/whisper-encoder.onnx" 50000000
need "$OUT/whisper-decoder.onnx" 100000000
need "$OUT/whisper-tokens.txt" 100000

# Test recordings for the unit tests (kept out of the APK).
fetch "$SEPARATION/qi-feng-le-zh.wav" "$TEST/song.wav" || echo "No test song (the separation test will skip the comparison)."
[ -s "$TEST/speech.wav" ] || fetch "$WHISPER/test_wavs/0.wav" "$TEST/speech.wav" || echo "No test speech (the transcription test will be skipped)."
fetch "$WHISPER/test_wavs/trans.txt" "$TEST/speech-trans.txt" || true

# Reference results from the sherpa-onnx project's own implementation, when it installs.
if python3 -m pip install --quiet --disable-pip-version-check sherpa-onnx soundfile numpy > "$TEST/pip.log" 2>&1; then
  python3 - "$OUT" "$TEST" <<'PY' || echo "Reference step failed (tests skip the comparison)."
import sys

import numpy as np
import sherpa_onnx
import soundfile as sf

out, test = sys.argv[1], sys.argv[2]

try:
    samples, rate = sf.read(f"{test}/song.wav", dtype="float32", always_2d=True)
    samples = np.ascontiguousarray(samples.T)
    config = sherpa_onnx.OfflineSourceSeparationConfig(
        model=sherpa_onnx.OfflineSourceSeparationModelConfig(
            spleeter=sherpa_onnx.OfflineSourceSeparationSpleeterModelConfig(
                vocals=f"{out}/spleeter-vocals.onnx",
                accompaniment=f"{out}/spleeter-accompaniment.onnx",
            ),
            num_threads=2,
            debug=False,
            provider="cpu",
        )
    )
    separator = sherpa_onnx.OfflineSourceSeparation(config)
    result = separator.process(sample_rate=rate, samples=samples)
    vocals = np.array(result.stems[0].data, dtype="float32")
    sf.write(f"{test}/song-vocals-ref.wav", vocals.T, result.sample_rate, subtype="PCM_16")
    print("reference: separated vocals", vocals.shape, result.sample_rate)
except Exception as error:  # the tests then skip the comparison
    print("reference: separation failed:", repr(error))

try:
    recognizer = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=f"{out}/whisper-encoder.onnx",
        decoder=f"{out}/whisper-decoder.onnx",
        tokens=f"{out}/whisper-tokens.txt",
        language="en",
        task="transcribe",
        num_threads=2,
    )
    samples, rate = sf.read(f"{test}/speech.wav", dtype="float32", always_2d=True)
    stream = recognizer.create_stream()
    stream.accept_waveform(rate, samples[:, 0])
    recognizer.decode_stream(stream)
    with open(f"{test}/speech-ref.txt", "w", encoding="utf-8") as handle:
        handle.write(stream.result.text.strip())
    print("reference: transcript:", stream.result.text.strip())
except Exception as error:
    print("reference: transcription failed:", repr(error))
PY
else
  echo "sherpa-onnx (Python) did not install; tests skip the reference comparison."
fi

TOTAL=0
{
  for f in spleeter-vocals.onnx spleeter-accompaniment.onnx whisper-encoder.onnx whisper-decoder.onnx whisper-tokens.txt; do
    size="$(wc -c < "$OUT/$f")"
    TOTAL=$((TOTAL + size))
    echo "$f $size $(sha256sum "$OUT/$f" | cut -c1-16)"
  done
  echo "total $TOTAL"
} > "$TEST/models.txt"
cat "$TEST/models.txt"
echo "AI models: ready ($((TOTAL / 1048576)) MB)"
