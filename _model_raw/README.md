# Place your model here

Drop your pre-downloaded ONNX model + tokenizer in this folder before running
`scripts/build_assets.sh`. Required files:

- `model.onnx` (and optionally `model.onnx.data` if external weights are split)
- `tokenizer.json` (HuggingFace tokenizer format)
- `config.json` (model config — optional, used for metadata only)

These files are gitignored — they never get committed. They are read once
at build time by `scripts/build_assets.sh`, encrypted with AES-256-GCM, and
shipped inside the APK as `assets/opto_core.bin` + `assets/opto_meta.bin`.

We do NOT download from HuggingFace or any other model hub at build time.
You bring the model yourself, you keep full control.
