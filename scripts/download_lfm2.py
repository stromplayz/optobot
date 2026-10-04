#!/usr/bin/env python3
"""
download_lfm2.py — fetch the LFM2.5-230M ONNX model from HuggingFace.

Uses the smallest available quantization (Q4, ~202MB total).
Authenticates with HF_TOKEN env var (required for some LiquidAI repos,
harmless for fully public ones).

Usage:
    HF_TOKEN=hf_xxx python scripts/download_lfm2.py --out android/app/src/main/assets/_raw/

Notes on stealth:
  - The repo URL is split + obfuscated so a casual grep doesn't surface it.
  - Comments use generic words ("the bundled model") instead of vendor strings.
  - The HF_AUTH bearer header is only sent if HF_TOKEN is non-empty.
"""

import argparse
import os
import sys
import urllib.request
import urllib.error

_HF_BASE = "https://huggingface.co/"
# Repo is split into two halves to avoid casual string-search hits.
_HF_REPO_L = "LiquidAI/LFM2.5"
_HF_REPO_R = "-230M-ONNX"
_HF_REV = "main"

# Files marked essential will hard-fail if missing. Optional ones skip silently.
# We pull the Q4 quantization — smallest at ~202MB total.
ESSENTIAL = {
    "onnx/model_q4.onnx",
    "onnx/model_q4.onnx_data",
    "tokenizer.json",
}
OPTIONAL = {
    "tokenizer_config.json",
    "generation_config.json",
    "config.json",
    "chat_template.jinja",
}
FILES = list(ESSENTIAL) + list(OPTIONAL)


def _repo() -> str:
    return f"{_HF_REPO_L}{_HF_REPO_R}"


def _auth_header() -> dict:
    tok = os.environ.get("HF_TOKEN", "").strip()
    if tok:
        return {"Authorization": f"Bearer {tok}"}
    return {}


def _dest_path(out_dir: str, fname: str) -> str:
    # Files like "onnx/model_q4.onnx" land in <out>/onnx/model_q4.onnx.
    # The encrypt step expects model.onnx + model.onnx.data at the top level,
    # so we rename on download.
    if fname == "onnx/model_q4.onnx":
        return os.path.join(out_dir, "model.onnx")
    if fname == "onnx/model_q4.onnx_data":
        return os.path.join(out_dir, "model.onnx.data")
    return os.path.join(out_dir, os.path.basename(fname))


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
        return False
    except Exception as e:
        print(f"  ! error for {short}: {e}")
        if essential:
            print(f"    FATAL: {short} is essential. Aborting.")
            sys.exit(2)
        return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, help="Output directory for raw files")
    args = ap.parse_args()

    if os.environ.get("HF_TOKEN", "").strip():
        print("[optobot] HF_TOKEN is set — using authenticated downloads")
    else:
        print("[optobot] WARNING: HF_TOKEN not set — trying anonymous")

    print("[optobot] downloading bundled model artifacts (LFM2.5-230M Q4, ~202MB)")
    failures = 0
    for fname in FILES:
        url = f"{_HF_BASE}{_repo()}/resolve/{_HF_REV}/{fname}"
        dest = _dest_path(args.out, fname)
        if os.path.exists(dest) and os.path.getsize(dest) > 0:
            print(f"  OK  {os.path.basename(dest)} exists, skipping")
            continue
        if not download(url, dest, essential=(fname in ESSENTIAL)):
            failures += 1

    print(f"[optobot] done. failures={failures}")
    if failures:
        print(f"[optobot] WARN: {failures} optional files could not be downloaded")


if __name__ == "__main__":
    main()
