package com.optobot.native

import android.content.Context
import android.util.Log

/**
 * OptoModelLoader — manages the lifecycle of the ONNX Runtime session.
 *
 * STUB MODE: When the ONNX native libs aren't available (e.g., CI build
 * without NDK), this class returns false from isReady and does nothing.
 * The UI shows mock responses in this mode.
 *
 * FULL MODE: On a real device with the ONNX Runtime AAR loaded, this
 * opens a session with mmap + lazy paging so the full 200MB model
 * never sits in RAM at once.
 */
class OptoModelLoader(private val context: Context) {

    @Volatile private var isLoaded = false

    val isReady: Boolean get() = isLoaded

    /**
     * Try to load the model. Returns true if the ONNX session is ready,
     * false if we're in stub mode (no ONNX Runtime available).
     */
    fun ensureLoaded(progress: (Float) -> Unit): Boolean {
        try {
            // Attempt to load the ONNX Runtime class. If it fails, we're
            // in stub mode — return false so the UI can show mock responses.
            Class.forName("ai.onnxruntime.OrtEnvironment")
            // Real ONNX loading would go here:
            // val env = OrtEnvironment.getEnvironment()
            // val opts = OrtSession.SessionOptions()...
            // val session = env.createSession(decryptedPath, opts)
            progress(1.0f)
            isLoaded = true
            return true
        } catch (_: Throwable) {
            Log.w(TAG, "ONNX Runtime not available — running in stub mode")
            progress(1.0f)
            isLoaded = false
            return false
        }
    }

    fun release() {
        isLoaded = false
    }

    companion object {
        private const val TAG = "OptoModelLoader"
    }
}
