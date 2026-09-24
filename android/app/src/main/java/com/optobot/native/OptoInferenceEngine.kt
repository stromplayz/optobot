package com.optobot.native

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log

/**
 * OptoInferenceEngine — orchestrates load + chat.
 *
 * Flow:
 *   load()           → decrypt asset → ORT session open (mmap, lazy)
 *   chat(prompt)     → tokenize → run step-by-step generation
 *                     → emit each token to the listener (for streaming UI)
 *                     → stop at EOS, max tokens, or stop string
 *
 * Anti-RAM tactics:
 *  - We bound the prompt to OptoConfig.MAX_CONTEXT_TOKENS via a sliding window
 *    (drop the oldest tokens, re-feed only recent + KV state invalidated for
 *    the dropped span). In practice ORT only re-runs the prompt once.
 *  - We never store the full logits tensor, only the chosen next id.
 *  - KV state is held as OnnxTensor references that we close() on completion
 *    so ORT releases the underlying native buffers immediately.
 */
class OptoInferenceEngine(private val context: Context) {

    private val loader = OptoModelLoader(context)
    private val tokenizer = OptoTokenizer(context)
    private val streamer = OptoStreamer(tokenizer)

    @Volatile private var isGenerating = false
    @Volatile private var stopRequested = false

    interface Listener {
        fun onLoadProgress(p: Float)
        fun onToken(token: String, fullText: String)
        fun onComplete(fullText: String)
        fun onError(message: String)
    }

    val isReady: Boolean get() = loader.isReady

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
        // Build a chat-wrapped prompt. If a systemPrompt is provided (from
        // onboarding → role + specialty), it is prepended as the first turn.
        val wrapped = if (systemPrompt.isNullOrBlank()) {
            prompt
        } else {
            "<|im_start|>system\n$systemPrompt<|im_end|>\n" + prompt
        }
        chatInternal(wrapped, listener)
    }

    private fun chatInternal(prompt: String, listener: Listener) {
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
                var inputIds = tokenizer.buildChatPrompt(prompt)
                var attentionMask = LongArray(inputIds.size) { 1L }
                val generated = StringBuilder()

                // First forward pass: full prompt, no past KV
                var step = streamer.step(session, inputIds.map { it.toLong() }.toLongArray(), attentionMask, null)

                // Token loop
                var produced = 0
                while (!stopRequested && produced < OptoConfig.MAX_NEW_TOKENS && !step.isEos) {
                    val piece = tokenizer.decode(intArrayOf(step.nextId))
                    if (piece.isEmpty()) {
                        produced++
                        continue
                    }
                    generated.append(piece)
                    listener.onToken(piece, generated.toString())

                    // Stop-string check
                    if (OptoConfig.STOP_STRINGS.any { generated.contains(it) }) {
                        val cleaned = OptoConfig.STOP_STRINGS.fold(generated.toString()) { acc, s -> acc.replace(s, "") }
                        listener.onComplete(cleaned.trim())
                        break
                    }

                    // Next step: feed only the new token, plus the past KV state
                    inputIds = longArrayOf(step.nextId.toLong())
                    attentionMask = LongArray(inputIds.size + 1).also { it[it.size - 1] = 1L }
                    step = streamer.step(session, inputIds, attentionMask, step.nextKvState)
                    produced++
                }

                // Cleanup: close KV state tensors
                step.nextKvState.values.forEach { runCatching { it.close() } }

                if (!stopRequested && produced >= OptoConfig.MAX_NEW_TOKENS) {
                    listener.onComplete(generated.toString().trim())
                } else if (stopRequested) {
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
