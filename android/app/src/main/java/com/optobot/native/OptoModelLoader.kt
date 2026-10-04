package com.optobot.native

import android.content.Context
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * OptoModelLoader — loads the ONNX Runtime session with mmap + lazy paging.
 *
 * Flow:
 *   1. Decrypt opto_core.bin → tmpfile (via OptoAssetDecryptor)
 *   2. Open OrtSession with optimization + mmap
 *   3. ORT mmaps the tmpfile so weights are paged in/out by the OS
 *
 * We do NOT call any pre-warm API. ORT's default behavior is to mmap
 * the protobuf and page tensors in on demand.
 */
class OptoModelLoader(private val context: Context) {

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var decryptedPath: String? = null

    val isReady: Boolean get() = session != null

    /** Input names the model expects (e.g. "input_ids", "attention_mask"). */
    val inputNames: List<String> get() = session?.inputNames?.toList() ?: emptyList()

    /** Output names the model produces (e.g. "logits"). */
    val outputNames: List<String> get() = session?.outputNames?.toList() ?: emptyList()

    fun session(): OrtSession = session ?: error("OptoModelLoader not ready")

    fun ensureLoaded(progress: (Float) -> Unit): Boolean {
        if (session != null) return true
        synchronized(this) {
            if (session != null) return true
            try {
                progress(0.05f)

                val e = OrtEnvironment.getEnvironment()
                env = e
                progress(0.15f)

                val decryptor = OptoAssetDecryptor(context)
                decryptedPath = decryptor.decryptToTmpFile(OptoConfig.ASSET_MODEL, "opto_core")
                progress(0.55f)

                val opts = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setMemoryPatternOptimization(true)
                    setIntraOpNumThreads(OptoConfig.ORT_INTRA_OP_THREADS)
                    setInterOpNumThreads(OptoConfig.ORT_INTER_OP_THREADS)
                }
                progress(0.75f)

                session = e.createSession(decryptedPath!!, opts)
                progress(1.0f)
                Log.i(TAG, "Model loaded. inputs=$inputNames outputs=$outputNames")
                return true
            } catch (t: Throwable) {
                Log.e(TAG, "Model load failed", t)
                cleanup()
                throw t
            }
        }
    }

    fun release() {
        synchronized(this) {
            cleanup()
        }
    }

    private fun cleanup() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
        decryptedPath?.let { p ->
            try { File(p).delete() } catch (_: Throwable) {}
        }
        decryptedPath = null
    }

    companion object {
        private const val TAG = "OptoModelLoader"
    }
}
