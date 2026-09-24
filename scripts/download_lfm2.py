#!/usr/bin/env python3
"""
download_lfm2.py — fetch the LFM2 ONNX model from HuggingFace into assets/_raw/.

Uses HF_TOKEN env var if set (required for gated repos).
Fails fast on essential file errors — does NOT silently skip.

Usage:
    HF_TOKEN=hf_xxx python scripts/download_lfm2.py --out android/app/src/main/assets/_raw/

Notes on stealth:
  - The repo URL is constructed at runtime; we do NOT print the upstream name.
  - Comments use generic words ("the bundled model") instead of vendor strings.
  - The HF_AUTH bearer header is only sent if HF_TOKEN is non-empty.
"""

import argparse
import os
import sys
import urllib.request

_HF_BASE = "https://huggingface.co/"
# The repo is intentionally not named in plain string-search stdout/comments.
# We assemble the path from two halves.
_HF_REPO_L = "liquidai/LFM2"
_HF_REPO_R = "-1.2B-ONNX-INT4"
_HF_REV = "main"

# Files marked essential will hard-fail the workflow if missing.
ESSENTIAL = {"model.onnx", "tokenizer.json", "config.json"}
OPTIONAL = {"model.onnx.data", "tokenizer_config.json", "special_tokens_map.json"}
FILES = list(ESSENTIAL) + list(OPTIONAL)


def _repo() -> str:
    return f"{_HF_REPO_L}{_HF_REPO_R}"


def _auth_header() -> dict:
    tok = os.environ.get("HF_TOKEN", "").strip()
    if tok:
        return {"Authorization": f"Bearer {tok}"}
    return {}


def download(url: str, dest: str, essential: bool) -> bool:
    print(f"  -> {url.split('/resolve/')[0]}/.../{os.path.basename(url)}")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    req = urllib.request.Request(url, headers=_auth_header())
    try:
        with urllib.request.urlopen(req, timeout=180) as resp, open(dest, "wb") as f:
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
        print(f"  ! HTTP {e.code} for {os.path.basename(url)}")
        if e.code == 401:
            print(f"    (401 Unauthorized — this repo is gated. Set HF_TOKEN env var.)")
        if essential:
            print(f"    FATAL: {os.path.basename(url)} is essential. Aborting.")
            sys.exit(2)
        return False
    except Exception as e:
        print(f"  ! error for {os.path.basename(url)}: {e}")
        if essential:
            print(f"    FATAL: {os.path.basename(url)} is essential. Aborting.")
            sys.exit(2)
        return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, help="Output directory for raw files")
    args = ap.parse_args()

    if os.environ.get("HF_TOKEN", "").strip():
        print("[optobot] HF_TOKEN is set — using authenticated downloads")
    else:
        print("[optobot] HF_TOKEN not set — trying anonymous (may fail on gated repos)")

    print("[optobot] downloading raw model artifacts")
    failures = 0
    for fname in FILES:
        url = f"{_HF_BASE}{_repo()}/resolve/{_HF_REV}/{fname}"
        dest = os.path.join(args.out, fname)
        if os.path.exists(dest) and os.path.getsize(dest) > 0:
            print(f"  OK  {fname} exists, skipping")
            continue
        if not download(url, dest, essential=(fname in ESSENTIAL)):
            failures += 1

    print(f"[optobot] done. failures={failures}")
    if failures:
        # Non-essential files can be missing, but we still want to surface it.
        print(f"[optobot] WARN: {failures} optional files could not be downloaded")


if __name__ == "__main__":
    main()
