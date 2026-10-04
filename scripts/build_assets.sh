#!/usr/bin/env bash
# build_assets.sh — encrypt + bundle the model into the APK assets.
#
# Resolution order:
#   1. If _model_raw/model.onnx + tokenizer.json exist → use the local files.
#   2. Else if HF_TOKEN is set → download LFM2.5-230M Q4 from HuggingFace.
#   3. Else → produce a stub bundle (UI preview only, no real inference).
#
# We prefer the user's local model. HF download is a convenience fallback.
#
# Run from project root:
#   bash scripts/build_assets.sh
# Or with HF download:
#   HF_TOKEN=hf_xxx bash scripts/build_assets.sh

set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

RAW="_model_raw"
STAGED="android/app/src/main/assets/_raw"

# ───────────────────────────────────────────────────────────────────────
# Step 1: Acquire model files. Either copy from _model_raw/, download
# from HuggingFace, or fall back to stub mode.
# ───────────────────────────────────────────────────────────────────────

if [ -f "$RAW/model.onnx" ] && [ -f "$RAW/tokenizer.json" ]; then
  echo "==> [1/4] using local model from $RAW/"
  ls -lh "$RAW/"
  mkdir -p "$STAGED"
  cp "$RAW/model.onnx" "$STAGED/model.onnx"
  [ -f "$RAW/model.onnx.data" ] && cp "$RAW/model.onnx.data" "$STAGED/model.onnx.data" || true
  cp "$RAW/tokenizer.json" "$STAGED/tokenizer.json"
elif [ -n "$HF_TOKEN" ]; then
  echo "==> [1/4] downloading model from HuggingFace (LFM2.5-230M Q4, ~202MB)"
  mkdir -p "$STAGED"
  python3 scripts/download_lfm2.py --out "$STAGED"
  # Verify essential files exist
  test -f "$STAGED/model.onnx" || { echo "FATAL: model.onnx missing after HF download"; exit 2; }
  test -f "$STAGED/tokenizer.json" || { echo "FATAL: tokenizer.json missing after HF download"; exit 2; }
  ls -lh "$STAGED/"
else
  echo "==> [stub] no local model in $RAW/ and no HF_TOKEN — building stub bundle"
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
  echo "To build with a real model, either:"
  echo "  1. Drop model.onnx + tokenizer.json into _model_raw/ and re-run, OR"
  echo "  2. Set HF_TOKEN env var and re-run (downloads LFM2.5-230M Q4)"
  exit 0
fi

# ───────────────────────────────────────────────────────────────────────
# Step 2-4: Generate key, encrypt tokenizer, encrypt model
# ───────────────────────────────────────────────────────────────────────

echo "==> [2/4] generating build secret + salt"
python3 scripts/gen_key.py --force

echo "==> [3/4] building encrypted tokenizer meta"
python3 scripts/build_tokenizer_meta.py \
    --in-tok "$STAGED/tokenizer.json" \
    --out android/app/src/main/assets/opto_meta.bin

echo "==> [4/5] encrypting model"
python3 scripts/encrypt_model.py \
    --in-onnx "$STAGED/model.onnx" \
    --in-data "$STAGED/model.onnx.data" \
    --out android/app/src/main/assets/opto_core.bin

# Step 5 (optional): download + encrypt voice models (Whisper + Kokoro)
if [ -n "$HF_TOKEN" ]; then
  echo "==> [5/5] downloading + encrypting voice models (Whisper + Kokoro)"
  VOICE_RAW="android/app/src/main/assets/_voice_raw"
  mkdir -p "$VOICE_RAW"
  python3 scripts/download_voice_models.py --out "$VOICE_RAW"
  python3 - <<EOF
import base64, hashlib, os, re, secrets
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

SALT_LEN = 32
IV_LEN = 12
PBKDF2_ITERS = 200_000
KEY_LEN = 32

with open("android/app/src/main/java/com/optobot/native/OptoBuildSecret.kt") as f:
    m = re.search(r'BUILD_SECRET\s*:\s*CharArray\s*=\s*"([^"]+)"', f.read())
    secret = m.group(1)
with open("android/app/src/main/assets/opto_key.hint", "rb") as f:
    salt = base64.b64decode(f.read())
key = hashlib.pbkdf2_hmac("sha256", secret.encode(), salt, PBKDF2_ITERS, dklen=KEY_LEN)

voice_dir = "$VOICE_RAW"
assets_dir = "android/app/src/main/assets"
for fn in os.listdir(voice_dir):
    src = os.path.join(voice_dir, fn)
    if not os.path.isfile(src): continue
    with open(src, "rb") as f: plaintext = f.read()
    if len(plaintext) > 500_000_000:
        print(f"  ! skipping {fn}: too large"); continue
    iv = secrets.token_bytes(IV_LEN)
    ct = AESGCM(key).encrypt(iv, plaintext, None)
    base = fn.replace(".bin", "").replace(".onnx", "")
    dest = os.path.join(assets_dir, f"opto_voice_{base}.bin")
    with open(dest, "wb") as f: f.write(salt); f.write(iv); f.write(ct)
    print(f"  encrypted {fn} -> {os.path.basename(dest)} ({len(plaintext)/1024/1024:.1f}MB)")
EOF
  rm -rf "$VOICE_RAW"
else
  echo "==> [5/5] skipping voice models (HF_TOKEN not set) — voice features disabled in this build"
fi

echo "==> cleaning staged raw files (we ship only encrypted bundles)"
rm -rf "$STAGED"

echo "==> done. Bundled assets:"
ls -lh android/app/src/main/assets/opto_*.bin android/app/src/main/assets/opto_key.hint 2>/dev/null
