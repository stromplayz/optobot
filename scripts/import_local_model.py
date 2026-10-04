#!/usr/bin/env python3
"""
import_local_model.py — copy a user-provided ONNX model + tokenizer into
the project so build_assets.sh can encrypt and bundle it.

We do NOT download anything. You bring the model yourself.

Usage:
    python scripts/import_local_model.py \\
        --model /path/to/your/model.onnx \\
        --tokenizer /path/to/your/tokenizer.json \\
        [--data /path/to/your/model.onnx.data]    # if external weights

Or just drop the files into _model_raw/ directly and skip this script.
"""

import argparse
import os
import shutil
import sys


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True, help="Path to your .onnx file")
    ap.add_argument("--tokenizer", required=True, help="Path to your tokenizer.json")
    ap.add_argument("--data", help="Optional path to model.onnx.data (external weights)")
    ap.add_argument("--dest", default="_model_raw", help="Destination directory")
    args = ap.parse_args()

    dest = args.dest
    os.makedirs(dest, exist_ok=True)

    # Copy model
    if not os.path.exists(args.model):
        print(f"ERROR: model file not found: {args.model}")
        sys.exit(1)
    model_dest = os.path.join(dest, "model.onnx")
    print(f"Copying model: {args.model} -> {model_dest}")
    shutil.copy(args.model, model_dest)
    size_mb = os.path.getsize(model_dest) / 1024 / 1024
    print(f"  size: {size_mb:.1f} MB")

    # Copy external data if provided
    if args.data:
        if not os.path.exists(args.data):
            print(f"ERROR: --data file not found: {args.data}")
            sys.exit(1)
        data_dest = os.path.join(dest, "model.onnx.data")
        print(f"Copying external data: {args.data} -> {data_dest}")
        shutil.copy(args.data, data_dest)
        size_mb = os.path.getsize(data_dest) / 1024 / 1024
        print(f"  size: {size_mb:.1f} MB")

    # Copy tokenizer
    if not os.path.exists(args.tokenizer):
        print(f"ERROR: tokenizer file not found: {args.tokenizer}")
        sys.exit(1)
    tok_dest = os.path.join(dest, "tokenizer.json")
    print(f"Copying tokenizer: {args.tokenizer} -> {tok_dest}")
    shutil.copy(args.tokenizer, tok_dest)
    size_kb = os.path.getsize(tok_dest) / 1024
    print(f"  size: {size_kb:.1f} KB")

    print(f"\n✓ Done. Files copied to {dest}/")
    print(f"  Now run: bash scripts/build_assets.sh")


if __name__ == "__main__":
    main()
