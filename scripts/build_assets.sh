#!/usr/bin/env bash
# build_assets.sh — encrypt + bundle the model from _model_raw/ into the APK assets.
#
# We NEVER download from HuggingFace or any other model hub. The user must
# drop their own ONNX model + tokenizer into _model_raw/ before running this.
#
# Required files in _model_raw/:
#   - model.onnx              (the ONNX weights)
#   - tokenizer.json          (HuggingFace tokenizer format)
# Optional:
#   - model.onnx.data         (external weights shard, if the ONNX file references one)
#
# Run from project root:
#   bash scripts/build_assets.sh

set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

RAW="_model_raw"

# ───────────────────────────────────────────────────────────────────────
# Stub mode: no model provided → build a tiny mock bundle so the UI can
# still be previewed. The Kotlin engine will fall back to a mock streamer
# when opto_core.bin is missing or smaller than 1 KB.
# ───────────────────────────────────────────────────────────────────────
if [ ! -f "$RAW/model.onnx" ] || [ ! -f "$RAW/tokenizer.json" ]; then
  echo "==> [stub] no model in $RAW/ — building stub bundle for UI preview"
  python3 scripts/gen_key.py --force

  # Write a stub encrypted file so OptoAssetDecryptor can still parse the
  # salt+iv+tag header without crashing on a real device.
  python3 - <<EOF
import base64, hashlib, json, os, re, secrets
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

SALT_LEN = 32
IV_LEN = 12
PBKDF2_ITERS = 200_000
KEY_LEN = 32

# Read the per-build secret + salt
with open("android/app/src/main/java/com/optobot/native/OptoBuildSecret.kt") as f:
    m = re.search(r'BUILD_SECRET\s*:\s*CharArray\s*=\s*"([^"]+)"', f.read())
    secret = m.group(1)
with open("android/app/src/main/assets/opto_key.hint", "rb") as f:
    salt = base64.b64decode(f.read())
key = hashlib.pbkdf2_hmac("sha256", secret.encode(), salt, PBKDF2_ITERS, dklen=KEY_LEN)
iv = secrets.token_bytes(IV_LEN)
ct = AESGCM(key).encrypt(iv, b"OPTOBOT_STUB_MODEL_v1", None)
with open("android/app/src/main/assets/opto_core.bin", "wb") as f:
    f.write(salt); f.write(iv); f.write(ct)

# Stub tokenizer meta — just enough so OptoTokenizer.parseMeta() works
meta = {
    "vocab": {"<unk>": 0, "<s>": 1, "</s>": 2, "<|im_start|>": 3, "<|im_end|>": 4},
    "merges": [],
    "special": {"bos_id": 1, "eos_id": 2, "pad_id": -1},
    "chat": {
        "userPrefix": "<|im_start|>user\n",
        "assistantPrefix": "<|im_end|>\n<|im_start|>assistant\n",
    },
}
plaintext = json.dumps(meta, separators=(",", ":")).encode("utf-8")
iv2 = secrets.token_bytes(IV_LEN)
ct2 = AESGCM(key).encrypt(iv2, plaintext, None)
with open("android/app/src/main/assets/opto_meta.bin", "wb") as f:
    f.write(salt); f.write(iv2); f.write(ct2)
EOF
  echo "OK stub bundle written (UI preview only - no real inference)"
  echo ""
  echo "To build a real APK with your own model:"
  echo "  1. Drop model.onnx + tokenizer.json into _model_raw/"
  echo "  2. Re-run: bash scripts/build_assets.sh"
  echo "  3. Re-run: cd android && ./gradlew assembleRelease"
  exit 0
fi

echo "==> [1/4] verifying local model in $RAW"
test -f "$RAW/model.onnx" || { echo "FATAL: $RAW/model.onnx missing"; exit 2; }
test -f "$RAW/tokenizer.json" || { echo "FATAL: $RAW/tokenizer.json missing"; exit 2; }
ls -lh "$RAW/"

# Stage into android/app/src/main/assets/_raw/ (the path encrypt_model.py expects)
STAGED="android/app/src/main/assets/_raw"
mkdir -p "$STAGED"
cp "$RAW/model.onnx" "$STAGED/model.onnx"
[ -f "$RAW/model.onnx.data" ] && cp "$RAW/model.onnx.data" "$STAGED/model.onnx.data" || true
cp "$RAW/tokenizer.json" "$STAGED/tokenizer.json"

echo "==> [2/4] generating build secret + salt"
python3 scripts/gen_key.py --force

echo "==> [3/4] building encrypted tokenizer meta"
python3 scripts/build_tokenizer_meta.py \
    --in-tok "$STAGED/tokenizer.json" \
    --out android/app/src/main/assets/opto_meta.bin

echo "==> [4/4] encrypting model"
python3 scripts/encrypt_model.py \
    --in-onnx "$STAGED/model.onnx" \
    --in-data "$STAGED/model.onnx.data" \
    --out android/app/src/main/assets/opto_core.bin

echo "==> cleaning staged raw files (we ship only encrypted bundles)"
rm -rf "$STAGED"

echo "==> done. Bundled assets:"
ls -lh android/app/src/main/assets/opto_*.bin android/app/src/main/assets/opto_key.hint
