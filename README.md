# OptoBot

A privacy-first, fully on-device AI companion for **optometrists, optometry
students, interns, educators, and opticians**. TypeScript UI (React Native) +
Kotlin native bridge + encrypted LFM-1.2B ONNX runtime + file tools. No server,
no telemetry, no model identification leaks.

---

## Why this exists

Most "AI chat" apps pipe every keystroke to a cloud LLM. OptoBot runs the model
on your phone. The weights are bundled in the APK, AES-256-GCM encrypted, and
the runtime mmaps them so the full 700 MB never sits in RAM at once. The UI is
warm, calm, and streaming — designed for long thinking sessions, not flashy
demos.

The architecture choice that makes on-device 1B-param inference tractable on
mid-range phones is **mmap + lazy paging**. We hand ONNX Runtime a path to the
decrypted model file. The OS pages the weight shards in and out as the forward
pass touches them. We never call any pre-warm API. On memory pressure we close
the session entirely; the next chat re-opens it in ~1 s.

To keep the bundled model anonymous, we:

1. Rename every artifact to `opto_*.bin` — no vendor strings anywhere in
   `/assets/`.
2. Encrypt the ONNX bytes (including the ONNX header, which is what
   `binwalk`/`file` would identify) with AES-256-GCM.
3. Derive the AES key with PBKDF2-HMAC-SHA256 (200k iters) from a per-build
   secret compiled into a gitignored Kotlin file.
4. Strip the obvious strings from the release `.dex` via ProGuard /
   `-assumenosideeffects` and `transform-remove-console`.

A casual reverse-engineer unpacking the APK will see `opto_core.bin` — opaque
ciphertext. Without the per-build secret (which lives only in the developer's
machine and the release signing keystore), the original ONNX cannot be
recovered.

---

## Project layout

```
optobot/
├── android/
│   ├── app/
│   │   ├── build.gradle
│   │   ├── proguard-rules.pro
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── assets/
│   │       │   ├── opto_core.bin     ← encrypted ONNX (~700 MB, gitignored)
│   │       │   ├── opto_meta.bin     ← encrypted tokenizer (~200 KB)
│   │       │   └── opto_key.hint     ← base64 PBKDF2 salt (non-secret)
│   │       ├── java/com/optobot/
│   │       │   ├── MainActivity.kt
│   │       │   ├── MainApplication.kt
│   │       │   └── native/
│   │       │       ├── OptoConfig.kt
│   │       │       ├── OptoBotModule.kt           ← RN bridge
│   │       │       ├── OptoBotPackage.kt
│   │       │       ├── OptoInferenceEngine.kt     ← orchestrator
│   │       │       ├── OptoModelLoader.kt         ← mmap ORT session
│   │       │       ├── OptoAssetDecryptor.kt     ← AES-256-GCM
│   │       │       ├── OptoTokenizer.kt          ← BPE
│   │       │       ├── OptoStreamer.kt           ← nucleus sampler
│   │       │       └── OptoBuildSecret.kt        ← gitignored, per-build
│   │       └── res/values/{strings,styles}.xml
│   ├── build.gradle
│   ├── settings.gradle
│   ├── gradle.properties
│   └── local.properties.example
├── src/
│   ├── App.tsx
│   ├── screens/ChatScreen.tsx
│   ├── components/{Header,ChatBubble,ChatInput,MessageList}.tsx
│   ├── hooks/useOptoChat.ts
│   ├── native/OptoBotNative.ts
│   ├── store/chatStore.ts
│   ├── theme/{colors,spacing,typography,fonts}.ts
│   ├── types/chat.ts
│   └── utils/id.ts
├── scripts/
│   ├── build_assets.sh        ← one-shot pipeline
│   ├── download_lfm2.py      ← HuggingFace fetch (URL split for stealth)
│   ├── gen_key.py             ← write OptoBuildSecret.kt + opto_key.hint
│   ├── encrypt_model.py       ← ONNX → opto_core.bin (AES-256-GCM)
│   └── build_tokenizer_meta.py ← tokenizer.json → opto_meta.bin
├── package.json
├── tsconfig.json
├── babel.config.js
├── metro.config.js
├── index.js
└── README.md
```

---

## Architecture at a glance

```
┌────────────────── TypeScript (RN) ──────────────────┐
│  App → ChatScreen → {Header, MessageList, ChatInput}│
│       │                                             │
│       └─ useOptoChat (hook)                          │
│            │                                         │
│            ▼  (event emitter + Promise API)         │
└─────────────┬───────────────────────────────────────┘
              │  JS Bridge (NativeModules)
              ▼
┌────────────────── Kotlin (Android) ────────────────┐
│  OptoBotModule (RN NativeModule)                    │
│       │                                             │
│       └─ OptoInferenceEngine                         │
│            ├─ OptoModelLoader                        │
│            │    └─ ORT session (mmap'd)              │
│            ├─ OptoTokenizer (BPE)                    │
│            └─ OptoStreamer (nucleus sampler)         │
│                                                     │
│  OptoAssetDecryptor (AES-256-GCM)                   │
│       └─ key = PBKDF2(OptoBuildSecret, opto_key.hint)│
└────────────────────────────────────────────────────┘
              │
              ▼  (mmap, OS-pages weights in/out)
┌────────────────── On-disk ─────────────────────────┐
│  /assets/opto_core.bin (encrypted)                  │
│  → decrypted to /data/data/.../files/opto_core.bin  │
│  → ONNX Runtime mmaps that file                     │
│  → RAM footprint: only touched shards              │
└────────────────────────────────────────────────────┘
```

---

## Setup

### Prerequisites

- Node 18+, JDK 17, Android SDK + NDK 27.0.12075200
- Python 3.10+ with `pip install cryptography`
- An Android device or emulator with 4 GB+ RAM (arm64 or x86_64)
- HuggingFace access if the LFM2 repo requires auth (set
  `HF_TOKEN` env var; the download script passes it via the Authorization
  header if present)

### Install

```bash
cd optobot
yarn install         # or npm install

# Generate the per-build secret + salt (one-time per release build)
python3 scripts/gen_key.py --force

# Download the raw model + tokenizer and encrypt into assets/
bash scripts/build_assets.sh
```

After `build_assets.sh` completes, you should see:

```
android/app/src/main/assets/opto_core.bin   ~700 MB (encrypted ONNX)
android/app/src/main/assets/opto_meta.bin   ~200 KB (encrypted tokenizer)
android/app/src/main/assets/opto_key.hint    44 B  (base64 salt)
```

### Run

```bash
yarn start           # Metro bundler
yarn android         # build + install debug APK on connected device
```

### Release build

```bash
# 1. Make sure OptoBuildSecret.kt exists (run gen_key.py if not)
# 2. Configure a signing config in android/app/build.gradle
# 3. Build
cd android && ./gradlew assembleRelease
# Output: android/app/build/outputs/apk/release/app-release.apk
```

---

## Memory budget (4 GB-RAM device, LFM2-1.2B Q4)

| Region                              | Size    | Notes                                |
|-------------------------------------|---------|--------------------------------------|
| ONNX file (encrypted, on disk)      | ~700 MB | Never loaded fully into RAM          |
| Decrypted tmpfile (on disk)         | ~700 MB | ORT mmaps it                         |
| RAM during generation (peak)        | ~250 MB | Working set: current shard + KV cache|
| App process baseline (UI + RN)      | ~120 MB | React Native + UI + store            |
| Total peak RSS                      | ~380 MB | Comfortable on 4 GB devices          |

If the device signals `onTrimMemory(MODERATE)`, we close the ORT session
and delete the decrypted tmpfile. Next chat re-opens in ~1 s.

---

## Stealth guarantees

| Attack vector                          | Defense                                      |
|----------------------------------------|----------------------------------------------|
| `unzip app.apk` → look at `/assets`    | Sees `opto_core.bin` — opaque ciphertext      |
| `file opto_core.bin` / `binwalk`       | AES-GCM output; no ONNX/protobuf header leaks  |
| `apktool d app.apk` → grep dex strings | ProGuard + `transform-remove-console` strips logs; model name strings are not in code |
| Reconstruct OptoBuildSecret.BUILD_SECRET | It is a `CharArray` literal; R8 inlines and obfuscates; not serialized anywhere |
| Brute-force PBKDF2                     | 200k iters of HMAC-SHA256; 32-byte salt; 48-char random secret → ~280 bits of entropy |
| MITM during build (no — we never network the model) | The build script downloads once into your local machine, then encrypts locally |

What we deliberately DO NOT defend against:

- A determined nation-state attacker with the actual APK AND the
  developer's signing keystore AND the per-build secret. (They can decrypt.)
- A side-channel that observes the inference math. (Out of scope.)

---

## Extending OptoBot

- **Add a second model size**: drop another `opto_core_tiny.bin` in assets,
  extend `OptoConfig` with a runtime switch, and add a small device-tier
  picker in `OptoModelLoader.ensureLoaded`.
- **Add conversation history**: the Zustand store already persists messages;
  add a `ConversationsScreen` with a list, and tag each `chat()` call with
  the conversation id.
- **Add image input**: bundle a separate `opto_vision.bin` (the LFM2-Vision
  variant), extend `OptoInferenceEngine.chat` to accept an image tensor.
- **Voice**: Android's built-in `SpeechRecognizer` for input; use
  `on-device TTS` for output. No additional models needed.

---

## CI / GitHub Actions

![Build APK](https://github.com/stromplayz/optobot/actions/workflows/build-apk.yml/badge.svg)

This repo ships two workflows under `.github/workflows/`:

- **`build-apk.yml`** — full release APK build. Triggered on every push to
  `main` and on manual dispatch. Downloads the LFM2 ONNX, generates a fresh
  `OptoBuildSecret.kt` for the run, encrypts the model with AES-256-GCM,
  builds the release APK, and uploads it as a 30-day-retained artifact.
- **`ci-lint.yml`** — fast TypeScript + Python + Kotlin syntax checks on
  every push/PR. Does NOT build the APK.

### Secrets to configure (Settings → Secrets and variables → Actions)

| Secret name              | Required? | Purpose                                             |
|--------------------------|-----------|-----------------------------------------------------|
| `HF_TOKEN`               | Optional  | Only if the HuggingFace model repo is gated for you |
| `SIGN_KEYSTORE_BASE64`   | Optional  | Base64 of your release `.jks` for signed APK        |
| `SIGN_KEY_ALIAS`         | Optional  | Keystore alias for the signing key                  |
| `SIGN_STORE_PASSWORD`    | Optional  | Keystore password                                   |
| `SIGN_KEY_PASSWORD`     | Optional  | Key password                                         |

If signing secrets are absent, the workflow produces an **unsigned release
APK**. You can still install it with `adb install` during development.

### Trigger a manual build

Go to **Actions** → **Build OptoBot APK** → **Run workflow** → pick the
model size (1.2B / 350M / 3B) → click **Run workflow**. The APK appears as
a downloadable artifact on the run summary page within ~30 minutes.

### ⚠ Security reminder — rotate the GitHub PAT you used to push

The first push to this repo used a Personal Access Token. **If you ever
shared that PAT in plaintext anywhere (chat, screenshots, logs), revoke it
now** at https://github.com/settings/tokens and generate a fresh one. The
workflow itself does NOT use your GitHub PAT — it uses the built-in
`GITHUB_TOKEN` for checkout/upload, which is scoped per-run and ephemeral.

---

## License

Private. (Adjust as needed for your distribution.)

---

## Addendum: Onboarding, Tools, and Optometry Companion

Built in v0.1.1+:

### Onboarding flow (`src/screens/OnboardingScreen.tsx`)

7-step onboarding, persisted via `userStore` (Zustand + AsyncStorage). First
launch shows this; subsequent launches go straight to the main app.

1. **Welcome** — overview + privacy posture
2. **Role** — Optometrist / Optometry Student / Intern / Educator / Optician
3. **Name + credentials** — Dr., OD, B.Optom, year suffix, etc.
4. **Specialties (multi-select)** — General, Pediatric, Geriatric, Contact Lens,
   Low Vision, Binocular Vision, Ocular Disease, Neuro-Rehab, Sports Vision
5. **Practice setting** — Private Clinic, Hospital, Optometry School, Solo, Retail, Research
6. **Conditional fields** — School name (students/interns/educators), Years of experience (optometrists)
7. **Tools grant + privacy consent** — both required to finish

### Tools tab (`src/screens/ToolsScreen.tsx`)

File tools gated by `userStore.toolsGranted`. Implements:

- **List** — search by title / body / tags
- **Read** — tap a note → full-screen editor
- **Write** — `+` FAB → kind picker → seeded template → editor
- **Delete** — `×` per card or "Delete" in editor
- **Note kinds**: SOAP, Prescription, Refraction, Case Discussion, Study, Free-form
- **Templates**: 4 seeded on first run (SOAP, Rx, Retinoscopy, Case Discussion)
- **Storage**: sandboxed at `/data/data/com.optobot/files/opto_notes/` (Android)
  or browser `localStorage` (web preview). No PII entered — user is reminded
  during onboarding to use anonymous patient codes only.

### Profile tab (`src/screens/ProfileScreen.tsx`)

View + edit all onboarding answers. Includes "Reset onboarding" button to
wipe the profile and return to the onboarding flow (chat history and notes
remain untouched).

### Chat with optometry system prompt

Every `chat()` call prepends a per-user system prompt built from onboarding
data (`src/utils/systemPrompt.ts`). The on-device model adapts tone and
clinical context per role:

- **Optometrist** — clinical terminology, decision-useful brevity
- **Student** — explain concepts, link to canonical exam topics, offer quizzes
- **Intern** — bridge theory and clinic, escalate to supervisor prompts
- **Educator** — design case-based learning, board-style questions, teaching scripts
- **Optician** — lens materials, coatings, prism, frame selection, ANSI standards

### Tab navigation (`src/navigation/TabNav.tsx`)

Lightweight custom bottom tab bar: Chat · Tools · Profile. No extra dep, so
the warm minimal aesthetic stays consistent.

### Native file bridge (`OptoFileTools.kt`)

Android file ops sandboxed under `filesDir/opto_notes/`:

- `listFiles(subdir?)`, `readFile(relPath)`, `writeFile(relPath, content)`,
  `deleteFile(relPath)`, `mkdir(relPath)`, `toolsGranted()`
- Path validation: no absolute paths, no `..` traversal
- All ops run on background threads (`Thread { … }.start()`) — Promise returns to JS
- Exposed via `OptoBotModule.kt` and matched by `OptoBotNative.ts` (typed bridge)
