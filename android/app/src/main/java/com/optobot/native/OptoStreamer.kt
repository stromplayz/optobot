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
     * The output can be [1, seq, vocab] (float array) or [1, vocab] (last step only).
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractLastLogits(value: Any): FloatArray {
        return when (value) {
            is Array<*> -> {
                if (value.size > 0 && value[0] is Array<*>) {
                    // [1, seq, vocab] or [batch, seq, vocab]
                    val batch = value[0] as Array<*>
                    if (batch.isNotEmpty() && batch[0] is FloatArray) {
                        // [seq, vocab] → take last
                        batch[batch.size - 1] as FloatArray
                    } else if (batch.isNotEmpty() && batch[0] is Array<*>) {
                        // [seq, vocab] nested → take last
                        val lastSeq = batch[batch.size - 1] as Array<*>
                        FloatArray(lastSeq.size) { (lastSeq[it] as Number).toFloat() }
                    } else {
                        // [1, vocab] → first element
                        value[0] as FloatArray
                    }
                } else {
                    // [vocab] directly
                    value[0] as FloatArray
                }
            }
            is FloatArray -> value
            else -> throw RuntimeException("unexpected logits type: ${value::class.java}")
        }
    }

    companion object {
        private const val TAG = "OptoStreamer"
    }
}
