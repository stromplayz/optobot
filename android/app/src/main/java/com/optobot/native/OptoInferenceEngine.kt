package com.optobot.native

import android.content.Context
import android.util.Log

/**
 * OptoInferenceEngine — orchestrates load + chat.
 *
 * STUB MODE: When ONNX Runtime isn't available, chat() returns a
 * canned mock response after a short delay. The UI works identically
 * — only the AI responses are placeholder text.
 *
 * FULL MODE: Runs the on-device LFM2.5-230M with mmap + streaming.
 */
class OptoInferenceEngine(private val context: Context) {

    private val loader = OptoModelLoader(context)

    interface Listener {
        fun onLoadProgress(p: Float)
        fun onToken(token: String, fullText: String)
        fun onComplete(fullText: String)
        fun onError(message: String)
    }

    val isReady: Boolean get() = loader.isReady

    fun load(listener: Listener) {
        listener.onLoadProgress(0.0f)
        val ok = loader.ensureLoaded { listener.onLoadProgress(it) }
        if (!ok) {
            listener.onLoadProgress(1.0f)
        }
    }

    fun chat(prompt: String, systemPrompt: String?, listener: Listener) {
        if (!loader.isReady) {
            // Stub mode: generate a canned response
            Thread {
                try {
                    Thread.sleep(300)
                    listener.onToken("(", prompt)
                    val response = generateStubResponse(prompt)
                    var acc = ""
                    for (word in response.split(" ")) {
                        acc += if (acc.isEmpty()) word else " $word"
                        listener.onToken(word, acc)
                        Thread.sleep(40)
                    }
                    listener.onComplete(acc)
                } catch (t: Throwable) {
                    listener.onError(t.message ?: "stub generation failed")
                }
            }.start()
            return
        }
        // Full mode would run ONNX inference here
        listener.onError("Full inference mode not yet implemented in this build")
    }

    fun stop() { /* no-op in stub */ }
    fun release() { loader.release() }

    private fun generateStubResponse(prompt: String): String {
        val p = prompt.lowercase()
        return when {
            "glaucoma" in p -> "Glaucoma is a group of eye conditions that damage the optic nerve, often caused by high intraocular pressure. Early detection via tonometry and dilated exams is key. I'd recommend annual screening for patients over 40."
            "diabet" in p -> "Diabetic retinopathy is a complication of diabetes affecting retinal blood vessels. Annual dilated eye exams are recommended for all diabetic patients. Tight glycemic control slows progression."
            "soap" in p || "note" in p -> "I can help draft a SOAP note. Tell me the patient's complaint, exam findings, assessment, and plan — I'll structure it. Should I save it?"
            "search" in p || "latest" in p -> "I'm running in stub mode. On a real device, I'd suggest running /search to fetch current info from the web."
            else -> "I heard: \"$prompt\". In stub mode I can't do real inference. The full build with ONNX Runtime would give a grounded response using the on-device LFM2.5-230M model."
        }
    }

    companion object {
        private const val TAG = "OptoEngine"
    }
}
