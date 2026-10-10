package com.optobot.native

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import kotlin.math.exp
import kotlin.math.ln

/**
 * OptoStreamer — single-step greedy decoder over the ONNX model.
 *
 * For each step:
 *   1. Feed input_ids + attention_mask to the model
 *   2. Get logits (float [1, seq, vocab])
 *   3. Take the last position's logits
 *   4. Apply temperature + argmax (greedy decoding for speed)
 *   5. Return the next token id
 *
 * Note: This is the simple version without KV cache — we re-feed the
 * full sequence each step. It's O(n²) but works correctly for short
 * conversations on a 230M model.
 */
class OptoStreamer {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    data class StepResult(
        val nextId: Long,
        val isEos: Boolean,
    )

    /** Run one forward pass and return the next token id (greedy). */
    fun step(session: OrtSession, inputIds: LongArray): StepResult {
        val seqLen = inputIds.size
        val attentionMask = LongArray(seqLen) { 1L }

        // Create tensors: shape [1, seq]
        val inputShape = longArrayOf(1, seqLen.toLong())
        val inputIdsTensor = OnnxTensor.createTensor(env, arrayOf(inputIds))
        val attentionMaskTensor = OnnxTensor.createTensor(env, arrayOf(attentionMask))

        // Build input map — use whatever names the model expects
        val inputNames = session.inputNames.toList()
        val inputs = HashMap<String, OnnxTensor>()
        if (inputNames.isNotEmpty()) {
            inputs[inputNames[0]] = inputIdsTensor
        }
        if (inputNames.size > 1) {
            inputs[inputNames[1]] = attentionMaskTensor
        }

        val output = session.run(inputs)
        try {
            // Find the logits output (usually "logits" or the first output)
            val outputName = session.outputNames.firstOrNull() ?: "logits"
            val logitsTensorOpt = output.get(outputName)
            if (logitsTensorOpt == null || !logitsTensorOpt.isPresent) {
                throw RuntimeException("no output '$outputName' in model output")
            }
            val logitsTensor = logitsTensorOpt.get()

            // The value is [1, seq, vocab] as a nested array
            val logits = extractLastLogits(logitsTensor.value)

            // Greedy: pick the argmax
            var bestId = 0L
            var bestLogit = Float.NEGATIVE_INFINITY
            for (i in logits.indices) {
                if (logits[i] > bestLogit) {
                    bestLogit = logits[i]
                    bestId = i.toLong()
                }
            }

            return StepResult(
                nextId = bestId,
                isEos = false,  // checked by caller against eosId
            )
        } finally {
            inputIdsTensor.close()
            attentionMaskTensor.close()
            output.close()
        }
    }

    /**
     * Extract the logits for the last position from the model output.
     * Handles multiple possible ONNX tensor return types:
     *   - Array<Array<FloatArray>>  → [1, seq, vocab] → take [0][last]
     *   - Array<FloatArray>          → [seq, vocab] → take last
     *   - FloatArray                 → [vocab] → return directly
     */
    private fun extractLastLogits(value: Any): FloatArray {
        return try {
            when (value) {
                is FloatArray -> value
                is Array<*> -> {
                    if (value.isEmpty()) return FloatArray(0)
                    val first = value[0]
                    when (first) {
                        is Array<*> -> {
                            if (first.isEmpty()) FloatArray(0)
                            else {
                                val lastSeq = first[first.size - 1]
                                when (lastSeq) {
                                    is FloatArray -> lastSeq
                                    is Array<*> -> FloatArray(lastSeq.size) { (lastSeq[it] as Number).toFloat() }
                                    else -> FloatArray(0)
                                }
                            }
                        }
                        is FloatArray -> first
                        else -> FloatArray(0)
                    }
                }
                else -> FloatArray(0)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to extract logits", t)
            FloatArray(0)
        }
    }

    companion object {
        private const val TAG = "OptoStreamer"
    }
}
