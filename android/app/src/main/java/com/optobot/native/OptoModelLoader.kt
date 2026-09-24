package com.optobot.native

import ai.onnxruntime.OnnxModelMetadata
import ai.onnxruntime.OnnxRuntime
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File

/**
 * OptoModelLoader — manages the lifecycle of a single ONNX Runtime session.
 *
 * MEMORY STRATEGY (mmap + lazy paging):
 *  - The decrypted model is written to a private tmpfile by OptoAssetDecryptor.
 *  - We open the session with `setOptimizationLevel(ORT_ENABLE_ALL)` and
 *    `setMemoryPatternOptimization(true)`.
 *  - We DO NOT call any pre-warm / pre-load APIs that would force ORT to read
 *    the whole graph into RAM. ORT's default behavior is to mmap the protobuf
 *    and page in tensors on demand, which is exactly what we want.
 *  - On low-memory signals (Activity.onTrimMemory) we close() + null the session
 *    so the OS reclaims the mmap'd pages; next chat re-opens it (cold start ~1s).
 */
class OptoModelLoader(private val context: Context) {

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var decryptedModelPath: String? = null

    val isReady: Boolean get() = session != null

    val metadata: OnnxModelMetadata? get() = try { session?.metadata } catch (_: Throwable) { null }

    fun ensureLoaded(progress: (Float) -> Unit): Boolean {
        if (session != null) return true
        synchronized(this) {
            if (session != null) return true

            try {
                progress(0.05f)
                if (!OnnxRuntime.isAvailable()) {
                    throw IllegalStateException("ORT not available on this device")
                }

                val e = OrtEnvironment.getEnvironment()
                env = e
                progress(0.15f)

                // 1. Decrypt asset to a tmpfile (mmap-friendly).
                val decryptor = OptoAssetDecryptor(context)
                decryptedModelPath = decryptor.decryptToTmpFile(
                    OptoConfig.ASSET_MODEL, "opto_core"
                )
                progress(0.55f)

                // 2. Build session options — keep threads low.
                val opts = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setMemoryPatternOptimization(true)
                    setIntraOpNumThreads(OptoConfig.ORT_INTRA_OP_THREADS)
                    setInterOpNumThreads(OptoConfig.ORT_INTER_OP_THREADS)
                    // Allow custom ops to be silently dropped if any (defensive).
                    setExtraOpts(mapOf(
                        "use_mmap" to "1",
                        "kCpuExecutionProviderDisablingArena" to "1"
                    ))
                }
                progress(0.75f)

                // 3. Open session — ORT will mmap the file. Weights stay paged-out
                //    until the forward pass actually needs them.
                session = e.createSession(decryptedModelPath!!, opts)
                progress(1.0f)
                return true
            } catch (t: Throwable) {
                Log.e(TAG, "Model load failed", t)
                cleanup()
                throw t
            }
        }
    }

    fun session(): OrtSession = session ?: error("OptoModelLoader not ready — call ensureLoaded() first")

    /**
     * Release the session and the decrypted tmpfile. The next chat will
     * trigger a re-decrypt + re-open. Use on memory pressure or app background.
     */
    fun release() {
        synchronized(this) {
            cleanup()
        }
    }

    private fun cleanup() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
        decryptedModelPath?.let { p ->
            try { File(p).delete() } catch (_: Throwable) {}
        }
        decryptedModelPath = null
        // env is a process singleton; we keep it alive.
    }

    companion object {
        private const val TAG = "OptoModelLoader"
    }
}
