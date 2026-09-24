#!/usr/bin/env python3
"""
build_tokenizer_meta.py — build the encrypted opto_meta.bin from a HuggingFace
tokenizer.json. The output is the JSON the Kotlin OptoTokenizer parses:
    {
      "vocab": { "<token>": <id>, ... },
      "merges": ["<a> <b>", ...],
      "special": { "bos_id": <id>, "eos_id": <id>, "pad_id": <id> },
      "chat": { "userPrefix": "...", "assistantPrefix": "..." }
    }

We then AES-256-GCM encrypt the JSON using the same scheme as encrypt_model.py.
"""

import argparse
import base64
import hashlib
import json
import os
import secrets

SALT_LEN = 32
IV_LEN = 12
TAG_LEN = 16
PBKDF2_ITERS = 200_000
KEY_LEN = 32

# Chat template — model-agnostic. We hard-code our own wrapping so the
# runtime never references the source model's chat template fields.
CHAT_USER_PREFIX = "<|im_start|>user\n"
CHAT_ASSISTANT_PREFIX = "<|im_end|>\n<|im_start|>assistant\n"


def read_build_secret() -> str:
    import re
    kt = "android/app/src/main/java/com/optobot/native/OptoBuildSecret.kt"
    text = open(kt).read()
    m = re.search(r'BUILD_SECRET\s*:\s*CharArray\s*=\s*"([^"]+)"', text)
    if not m:
        raise SystemExit("missing OptoBuildSecret.kt — run gen_key.py")
    return m.group(1)


def read_salt() -> bytes:
    hint = "android/app/src/main/assets/opto_key.hint"
    return base64.b64decode(open(hint, "rb").read())


def derive_key(secret, salt):
    return hashlib.pbkdf2_hmac("sha256", secret.encode("utf-8"), salt, PBKDF2_ITERS, dklen=KEY_LEN)


def encrypt_bytes(key, plaintext: bytes, salt: bytes, out_path: str):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    iv = secrets.token_bytes(IV_LEN)
    aes = AESGCM(key)
    ct = aes.encrypt(iv, plaintext, None)
    with open(out_path, "wb") as f:
        f.write(salt)
        f.write(iv)
        f.write(ct)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in-tok", required=True, help="Path to HuggingFace tokenizer.json")
    ap.add_argument("--out", default="android/app/src/main/assets/opto_meta.bin")
    args = ap.parse_args()

    raw = json.load(open(args.in_tok))
    vocab = {}
    for k, v in raw.get("model", {}).get("vocab", {}).items():
        vocab[k] = int(v)
    merges_raw = raw.get("model", {}).get("merges", [])
    merges = [f"{a} {b}" if isinstance(m, list) else m for m in merges_raw
              for a, b in [m.split(" ", 1) if isinstance(m, str) and " " in m else (m, "")]]

    # Try to grab special token ids; default to -1.
    added = raw.get("added_tokens", [])
    bos_id = next((t["id"] for t in added if t["content"] == "<|im_start|>"), -1)
    eos_id = next((t["id"] for t in added if t["content"] == "<|im_end|>"), -1)
    pad_id = -1

    meta = {
        "vocab": vocab,
        "merges": [m for m in merges if m and " " in m],
        "special": {"bos_id": bos_id, "eos_id": eos_id, "pad_id": pad_id},
        "chat": {
            "userPrefix": CHAT_USER_PREFIX,
            "assistantPrefix": CHAT_ASSISTANT_PREFIX,
        },
    }

    plaintext = json.dumps(meta, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    salt = read_salt()
    key = derive_key(read_build_secret(), salt)
    encrypt_bytes(key, plaintext, salt, args.out)
    print(f"[optobot] wrote {args.out} ({len(plaintext)/1024:.1f} KB plaintext)")


if __name__ == "__main__":
    main()
