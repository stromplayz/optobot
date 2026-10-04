#!/usr/bin/env python3
"""
download_voice_models.py — fetch Whisper + Kokoro models for on-device
speech-to-text + text-to-speech.

Both are bundled alongside the LFM2.5-230M model so the APK ships with
voice support out of the box.

Models:
  - Whisper base.en Q5_1 (~75MB) from ggerganov/whisper.cpp
  - Kokoro-82M voice pack (~3MB single voice) from hexgrad/Kokoro-82M

Note: The full Kokoro model is PyTorch (.pth), not ONNX. For ONNX TTS,
users can either:
  - Convert Kokoro to ONNX locally (see docs/voice.md for instructions)
  - Or skip Kokoro and use Android's built-in TextToSpeech engine

We treat both as OPTIONAL here — the build succeeds even if voice models
aren't downloaded. The Kotlin OptoVoice module falls back to stub mode
when the native libs aren't available, and the UI shows "voice disabled".

Uses HF_TOKEN env var if set. Free, open access, no API keys needed.
"""

import argparse
import os
import sys
import urllib.request
import urllib.error

_HF_BASE = "https://huggingface.co/"

# Whisper — ggerganov/whisper.cpp repo on HuggingFace
_WHISPER_REPO = "ggerganov/whisper.cpp"
_WHISPER_FILE = "ggml-base.en-q5_1.bin"   # base.en Q5_1, ~75MB, English-only

# Kokoro — single voice pack from hexgrad/Kokoro-82M
_KOKORO_REPO = "hexgrad/Kokoro-82M"
_KOKORO_FILES = [
    "voices/af_heart.pt",   # ~3MB single-voice pack (PyTorch format)
]

# All voice files are OPTIONAL — build succeeds even if download fails.
# The Kotlin side falls back to stub mode (text-only chat, no voice).
ESSENTIAL = set()
OPTIONAL = {_WHISPER_FILE, _KOKORO_FILES[0]}


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
        print(f"    (optional file skipped — voice features will be limited)")
        return False
    except Exception as e:
        print(f"  ! error for {short}: {e}")
        if essential:
            print(f"    FATAL: {short} is essential. Aborting.")
            sys.exit(2)
        print(f"    (optional file skipped — voice features will be limited)")
        return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, help="Output directory")
    args = ap.parse_args()

    print("[optobot] downloading voice models (Whisper base.en + Kokoro voice, ~78MB total)")
    files = [
        (_WHISPER_REPO, _WHISPER_FILE, _WHISPER_FILE, False),       # optional
        (_KOKORO_REPO, _KOKORO_FILES[0], "kokoro-voice-af_heart.pt", False),  # optional
    ]
    failures = 0
    for repo, src_path, dest_name, essential in files:
        url = f"{_HF_BASE}{repo}/resolve/main/{src_path}"
        dest = os.path.join(args.out, dest_name)
        if os.path.exists(dest) and os.path.getsize(dest) > 0:
            print(f"  OK  {dest_name} exists, skipping")
            continue
        if not download(url, dest, essential=essential):
            failures += 1

    print(f"[optobot] done. failures={failures}")
    if failures:
        print(f"[optobot] WARN: {failures} optional voice files could not be downloaded.")
        print(f"[optobot] Voice features will be limited to stub mode in this build.")


if __name__ == "__main__":
    main()
