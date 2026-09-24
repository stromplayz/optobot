package com.optobot.native

/**
 * OptoConfig — central constants for the native inference stack.
 *
 * NOTE ON STEALTH:
 *  - We never reference "Liquid", "LFM", "LiquidAI", or any vendor string here.
 *  - The bundled model is renamed to `opto_core.bin` and encrypted at rest.
 *  - The runtime path / asset name / session options use only generic words.
 *  - ProGuard strips the rest. See `proguard-rules.pro`.
 */
object OptoConfig {

    /** Encrypted ONNX weight file in /android/app/src/main/assets/ */
    const val ASSET_MODEL = "opto_core.bin"

    /** Encrypted tokenizer vocab + merges */
    const val ASSET_TOKENIZER = "opto_meta.bin"

    /** PBKDF2 key-derivation params (also bundled, NOT secret on their own) */
    const val ASSET_KEY_HINT = "opto_key.hint"

    /** Salt length for PBKDF2 (bytes) */
    const val PBKDF2_SALT_LEN = 32

    /** PBKDF2 iterations — high enough to slow brute-force, low enough for cold start */
    const val PBKDF2_ITERS = 200_000

    /** PBKDF2 key length (256-bit AES key) */
    const val PBKDF2_KEY_LEN = 32

    /** AES-GCM IV length */
    const val GCM_IV_LEN = 12

    /** AES-GCM tag length */
    const val GCM_TAG_LEN = 16

    /** ONNX Runtime thread count — keep low to avoid CPU thrash */
    const val ORT_INTRA_OP_THREADS = 2

    /** ONNX Runtime inter-op threads */
    const val ORT_INTER_OP_THREADS = 1

    /** Max context tokens — keeps KV cache bounded for low-RAM devices */
    const val MAX_CONTEXT_TOKENS = 2048

    /** Max new tokens per generation */
    const val MAX_NEW_TOKENS = 512

    /** Sampling temperature */
    const val TEMPERATURE = 0.7f

    /** Top-p (nucleus) sampling */
    const val TOP_P = 0.9f

    /** Repetition penalty */
    const val REPETITION_PENALTY = 1.15f

    /** Stop strings — kept generic, model-agnostic */
    val STOP_STRINGS = listOf("</s>", "<|end|>", "<|im_end|>")

    /** Memory-mapped region size — ONNX Runtime mmap will page this in chunks */
    const val MMAP_CHUNK_BYTES = 4L * 1024 * 1024 // 4 MiB

    /** Cold-start progress event name (sent to JS) */
    const val EVENT_LOAD_PROGRESS = "opto:loadProgress"

    /** Token stream event name (sent to JS) */
    const val EVENT_TOKEN = "opto:token"

    /** Done event name */
    const val EVENT_DONE = "opto:done"

    /** Error event name */
    const val EVENT_ERROR = "opto:error"
}
