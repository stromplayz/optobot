# Security Policy

## Reporting a Vulnerability

OptoBot is private source. If you discover a vulnerability, **do not open a
public issue**. Email the maintainer directly.

## What counts as a security issue for OptoBot

- A way to recover the AES-256-GCM key from the published APK without the
  developer's signing keystore and per-build secret.
- A way to identify the underlying model architecture from the released APK
  without privileged access.
- A path that exfiltrates user chat data off-device (the app should be
  network-silent by default).
- A crash that allows the engine to read uninitialized memory.

## What does NOT count

- "Anyone with the APK + signing keystore + OptoBuildSecret can decrypt."
  That is a documented design assumption — see README "Stealth guarantees".
- Performance issues / model quality complaints. These are bugs, not
  security issues.

## Hardening checklist for releases

- [ ] Rotate `OptoBuildSecret.kt` for every release build
- [ ] Confirm ProGuard `minifyEnabled true` in `app/build.gradle`
- [ ] Verify release APK does not contain any `Log.*` output (`apktool d` + grep)
- [ ] Verify `/assets/` only contains `opto_*.bin` and `opto_key.hint` — no JSON / ONNX
- [ ] Confirm `INTERNET` permission is not used at runtime (set up a Charles proxy if unsure)
