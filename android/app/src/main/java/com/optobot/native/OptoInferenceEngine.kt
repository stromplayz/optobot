package com.optobot.native

import android.content.Context
import android.util.Log

/**
 * OptoInferenceEngine — orchestrates model load + streaming chat.
 *
 * Flow:
 *   1. load() → decrypt model → open ONNX session
 *   2. chat(prompt) → tokenize → step-by-step generation → stream tokens
 *   3. Each step: feed full sequence, get logits, argmax next token, emit
 *   4. Stop at EOS, max tokens, or stop string
 */
class OptoInferenceEngine(private val context: Context) {

    private val loader = OptoModelLoader(context)
    private val tokenizer = OptoTokenizer(context)
    private val streamer = OptoStreamer()

    @Volatile private var isGenerating = false
    @Volatile private var stopRequested = false

    val isReady: Boolean get() = loader.isReady && tokenizer.isLoaded

    interface Listener {
        fun onLoadProgress(p: Float)
        fun onToken(token: String, fullText: String)
        fun onComplete(fullText: String)
        fun onError(message: String)
    }

    fun load(listener: Listener) {
        try {
            listener.onLoadProgress(0.0f)
            tokenizer.load()
            listener.onLoadProgress(0.10f)
            loader.ensureLoaded { listener.onLoadProgress(it) }
        } catch (t: Throwable) {
            Log.e(TAG, "load failed", t)
            listener.onError(t.message ?: "Load failed")
        }
    }

    fun stop() { stopRequested = true }

    fun chat(prompt: String, systemPrompt: String?, listener: Listener) {
        if (isGenerating) {
            listener.onError("Already generating")
            return
        }
        if (!loader.isReady) {
            listener.onError("Model not loaded")
            return
        }
        isGenerating = true
        stopRequested = false

        Thread {
            try {
                val session = loader.session()
                val eosId = tokenizer.eosId

                // Build the full chat prompt
                val basePrompt = tokenizer.buildChatPrompt(prompt)
                val fullPrompt = if (systemPrompt != null) {
                    // Prepend system prompt if provided
                    val sysIds = tokenizer.encode(systemPrompt, addBos = true)
                    sysIds + basePrompt
                } else {
                    basePrompt
                }

                // Current sequence (prompt + generated tokens)
                val currentIds = ArrayList<Long>(fullPrompt.toList())
                val generated = StringBuilder()
                var produced = 0

                while (!stopRequested && produced < OptoConfig.MAX_NEW_TOKENS) {
                    val idsArray = currentIds.toLongArray()

                    // Run one forward pass
                    val step = streamer.step(session, idsArray)

                    // Check EOS
                    if (eosId >= 0 && step.nextId == eosId.toLong()) {
                        break
                    }

                    // Decode the new token
                    val piece = tokenizer.decode(longArrayOf(step.nextId))
                    if (piece.isNotEmpty()) {
                        generated.append(piece)
                        listener.onToken(piece, generated.toString())

                        // Stop-string check
                        if (OptoConfig.STOP_STRINGS.any { generated.contains(it) }) {
                            val cleaned = OptoConfig.STOP_STRINGS.fold(generated.toString()) { acc, s -> acc.replace(s, "") }
                            listener.onComplete(cleaned.trim())
                            break
                        }
                    }

                    currentIds.add(step.nextId)
                    produced++

                    // Check if we hit max context
                    if (currentIds.size > OptoConfig.MAX_CONTEXT_TOKENS) {
                        // Truncate from the front (drop oldest)
                        val dropCount = currentIds.size - OptoConfig.MAX_CONTEXT_TOKENS + 50
                        repeat(dropCount) { currentIds.removeAt(0) }
                    }
                }

                if (!stopRequested) {
                    listener.onComplete(generated.toString().trim())
                } else {
                    listener.onComplete(generated.toString().trim())
                }
            } catch (t: Throwable) {
                Log.e(TAG, "chat failed", t)
                listener.onError(t.message ?: "Generation failed")
            } finally {
                isGenerating = false
                stopRequested = false
            }
        }.start()
    }

    fun release() {
        loader.release()
    }

    companion object {
        private const val TAG = "OptoEngine"
    }
}
