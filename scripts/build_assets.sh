#!/usr/bin/env bash
# build_assets.sh — one-shot pipeline to bundle the encrypted model.
#
# Steps:
#   1. download_lfm2.py       → assets/_raw/
#   2. gen_key.py              → OptoBuildSecret.kt + opto_key.hint
#   3. build_tokenizer_meta.py → opto_meta.bin
#   4. encrypt_model.py        → opto_core.bin
#   5. clean up _raw/ so it does NOT ship in the APK
#
# Run from project root:
#   bash scripts/build_assets.sh

set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

RAW="android/app/src/main/assets/_raw"

echo "==> [1/5] downloading raw artifacts"
python3 scripts/download_lfm2.py --out "$RAW"

echo "==> [2/5] generating build secret + salt"
python3 scripts/gen_key.py --force

echo "==> [3/5] building encrypted tokenizer meta"
python3 scripts/build_tokenizer_meta.py \
    --in-tok "$RAW/tokenizer.json" \
    --out android/app/src/main/assets/opto_meta.bin

echo "==> [4/5] encrypting model"
python3 scripts/encrypt_model.py \
    --in-onnx "$RAW/model.onnx" \
    --in-data "$RAW/model.onnx.data" \
    --out android/app/src/main/assets/opto_core.bin

echo "==> [5/5] cleaning raw artifacts (we ship only encrypted bundles)"
rm -rf "$RAW"

echo "==> done. Bundled assets:"
ls -lh android/app/src/main/assets/opto_*.bin android/app/src/main/assets/opto_key.hint
