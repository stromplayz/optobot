#!/usr/bin/env python3
"""
download_voice_models.py — fetch ONNX Whisper + Kokoro models for
on-device speech-to-text + text-to-speech via ONNX Runtime.

Models (all from onnx-community on HuggingFace, all ONNX format):
  - Whisper base.en Q4:
    - encoder_model_q4.onnx  (~18MB)
    - decoder_model_merged_q4.onnx  (~118MB)
  - Kokoro-82M Q4:
    - model_q4.onnx  (~291MB)
    - voices/af.bin  (~512KB, single voice pack)

Total: ~427MB of voice models bundled alongside the LFM2.5-230M LLM.

These run on the same ONNX Runtime we already use for the LLM — no
extra native libs needed.

Uses HF_TOKEN env var if set. Free, open access, no API keys.
"""

import argparse
import os
import sys
import urllib.request
import urllib.error

_HF_BASE = "https://huggingface.co/"

# Whisper base.en Q4 — ONNX format from onnx-community
_WHISPER_REPO = "onnx-community/whisper-base.en"
_WHISPER_FILES = [
    ("onnx/encoder_model_q4.onnx", "whisper_encoder_q4.onnx", True),
    ("onnx/decoder_model_merged_q4.onnx", "whisper_decoder_q4.onnx", True),
    ("tokenizer.json", "whisper_tokenizer.json", True),
    ("generation_config.json", "whisper_generation_config.json", False),
]

# Kokoro-82M Q4 — ONNX format from onnx-community
_KOKORO_REPO = "onnx-community/Kokoro-82M-ONNX"
_KOKORO_FILES = [
    ("onnx/model_q4.onnx", "kokoro_q4.onnx", True),
    ("voices/af.bin", "kokoro_voice_af.bin", True),
    ("config.json", "kokoro_config.json", True),
    ("tokenizer.json", "kokoro_tokenizer.json", True),
    ("tokenizer_config.json", "kokoro_tokenizer_config.json", False),
]


def _auth_header() -> dict:
    tok = os.environ.get("HF_TOKEN", "").strip()
    if tok:
        return {"Authorization": f"Bearer {tok}"}
    return {}


def download(url: str, dest: str, essential: bool) -> bool:
    short = os.path.basename(dest)
    print(f"  -> .../{short}")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    req = urllib.request.Request(url, headers=_auth_header())
    try:
        with urllib.request.urlopen(req, timeout=300) as resp, open(dest, "wb") as f:
            total = int(resp.headers.get("Content-Length", 0))
            got = 0
            while True:
                chunk = resp.read(1024 * 1024)
                if not chunk:
                    break
                f.write(chunk)
                got += len(chunk)
                if total:
                    pct = 100.0 * got / total
                    sys.stdout.write(f"\r    {got/1024/1024:.1f}MB / {total/1024/1024:.1f}MB ({pct:.1f}%)")
                    sys.stdout.flush()
        print()
        return True
    except urllib.error.HTTPError as e:
        print(f"  ! HTTP {e.code} for {short}")
        if e.code == 401:
            print(f"    (401 Unauthorized — set HF_TOKEN env var)")
        if essential:
            print(f"    FATAL: {short} is essential. Aborting.")
            sys.exit(2)
        print(f"    (optional file skipped — voice features limited)")
        return False
    except Exception as e:
        print(f"  ! error for {short}: {e}")
        if essential:
            print(f"    FATAL: {short} is essential. Aborting.")
            sys.exit(2)
        return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, help="Output directory")
    args = ap.parse_args()

    print("[optobot] downloading voice models (Whisper base.en Q4 + Kokoro-82M Q4, ~427MB total)")
    failures = 0

    print("[optobot] Whisper base.en ONNX Q4 (~136MB):")
    for src_path, dest_name, essential in _WHISPER_FILES:
        url = f"{_HF_BASE}{_WHISPER_REPO}/resolve/main/{src_path}"
        dest = os.path.join(args.out, dest_name)
        if os.path.exists(dest) and os.path.getsize(dest) > 0:
            print(f"  OK  {dest_name} exists, skipping")
            continue
        if not download(url, dest, essential):
            failures += 1

    print("[optobot] Kokoro-82M ONNX Q4 (~291MB):")
    for src_path, dest_name, essential in _KOKORO_FILES:
        url = f"{_HF_BASE}{_KOKORO_REPO}/resolve/main/{src_path}"
        dest = os.path.join(args.out, dest_name)
        if os.path.exists(dest) and os.path.getsize(dest) > 0:
            print(f"  OK  {dest_name} exists, skipping")
            continue
        if not download(url, dest, essential):
            failures += 1

    print(f"[optobot] done. failures={failures}")
    if failures:
        print(f"[optobot] WARN: {failures} files failed. Voice features may be limited.")


if __name__ == "__main__":
    main()
