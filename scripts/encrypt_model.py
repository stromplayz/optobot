#!/usr/bin/env python3
"""
encrypt_model.py — encrypt the raw ONNX model + tokenizer into the
opaque asset bundle consumed by the Kotlin loader.

Layout for opto_core.bin:
    [ salt(32) ][ iv(12) ][ ciphertext... ][ GCM tag(16) ]

We use AES-256-GCM with a key derived via PBKDF2-HMAC-SHA256
from the per-build secret (OptoBuildSecret.kt) and the salt
written to opto_key.hint.

This parity-matches the Kotlin reader in OptoAssetDecryptor.kt
and the constant block in OptoConfig.kt.

Usage:
    python scripts/encrypt_model.py \
        --in-onnx android/app/src/main/assets/_raw/model.onnx \
        --in-data android/app/src/main/assets/_raw/model.onnx.data \
        --out android/app/src/main/assets/opto_core.bin
"""

import argparse
import base64
import hashlib
import hmac
import os
import secrets

# Parity with OptoConfig
SALT_LEN = 32
IV_LEN = 12
TAG_LEN = 16
PBKDF2_ITERS = 200_000
KEY_LEN = 32


def read_build_secret() -> str:
    kt = "android/app/src/main/java/com/optobot/native/OptoBuildSecret.kt"
    if not os.path.exists(kt):
        print(f"[optobot] missing {kt}; run scripts/gen_key.py first.")
        raise SystemExit(1)
    text = open(kt).read()
    # Extract the literal between the quotes after BUILD_SECRET =
    import re
    m = re.search(r'BUILD_SECRET\s*:\s*CharArray\s*=\s*"([^"]+)"', text)
    if not m:
        print(f"[optobot] could not parse BUILD_SECRET from {kt}")
        raise SystemExit(1)
    return m.group(1)


def read_salt() -> bytes:
    hint = "android/app/src/main/assets/opto_key.hint"
    if not os.path.exists(hint):
        print(f"[optobot] missing {hint}; run scripts/gen_key.py first.")
        raise SystemExit(1)
    return base64.b64decode(open(hint, "rb").read())


def derive_key(secret: str, salt: bytes) -> bytes:
    return hashlib.pbkdf2_hmac("sha256", secret.encode("utf-8"), salt, PBKDF2_ITERS, dklen=KEY_LEN)


def encrypt_aes_gcm(key: bytes, plaintext_path: str, salt: bytes, out_path: str):
    """Encrypt the plaintext file with AES-256-GCM in a single shot.

    The cryptography library's AESGCM class only supports one-shot encrypt()
    on its Python API (the lower-level Cipher + ChaCha20Poly1305 interface
    supports streaming, but AESGCM does not). For a ~200MB file on a CI
    runner with 7GB RAM, one-shot is fine. We cap input at 1GB as a sanity
    check.
    """
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    iv = secrets.token_bytes(IV_LEN)
    aes = AESGCM(key)

    # Read the full plaintext into memory
    with open(plaintext_path, "rb") as f:
        plaintext = f.read()
    if len(plaintext) > 1_000_000_000:
        raise ValueError(f"plaintext too large ({len(plaintext)} bytes) — cap is 1GB")

    # One-shot encrypt (handles tag internally)
    ciphertext = aes.encrypt(iv, plaintext, None)

    with open(out_path, "wb") as out:
        out.write(salt)            # 32 bytes
        out.write(iv)              # 12 bytes
        out.write(ciphertext)      # ciphertext + 16-byte GCM tag at the end


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in-onnx", required=True, help="Path to model.onnx")
    ap.add_argument("--in-data", help="Optional external .onnx.data file. If set, concatenated.")
    ap.add_argument("--out", required=True, help="Output path (e.g. assets/opto_core.bin)")
    args = ap.parse_args()

    secret = read_build_secret()
    salt = read_salt()
    key = derive_key(secret, salt)
    print(f"[optobot] derived key: {hmac.new(b'', key, hashlib.sha256).hexdigest()[:16]}…")
    print(f"[optobot] salt       : {base64.b64encode(salt).decode()}")

    # We concatenate the onnx + onnx.data (if any) into a single plaintext
    # stream, then encrypt the whole thing. The Kotlin reader expects one
    # ciphertext stream; the runtime then mmaps it.
    tmp_pt = args.out + ".plaintext.tmp"
    print(f"[optobot] concatenating input to {tmp_pt}")
    with open(tmp_pt, "wb") as out:
        with open(args.in_onnx, "rb") as f:
            out.write(f.read())
        if args.in_data and os.path.exists(args.in_data):
            print(f"[optobot] appending external data: {args.in_data}")
            with open(args.in_data, "rb") as f:
                out.write(f.read())

    print(f"[optobot] encrypting → {args.out}")
    encrypt_aes_gcm(key, tmp_pt, salt, args.out)
    os.remove(tmp_pt)

    size_mb = os.path.getsize(args.out) / 1024 / 1024
    print(f"[optobot] wrote {args.out} ({size_mb:.1f} MB)")


if __name__ == "__main__":
    main()
