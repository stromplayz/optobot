package com.optobot.native

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.util.Log
import java.util.PriorityQueue
import kotlin.math.exp
import kotlin.math.ln

/**
 * OptoStreamer — single-step greedy/nucleus sampler over the LFM logits.
 *
 * Why one-step streaming?
 *  - Each forward pass produces logits for the NEXT token only.
 *  - We emit one token at a time via the JS bridge's event emitter, so the UI
 *    shows chat-style streaming instead of a 5-second freeze.
 *  - We maintain a small in-memory KV cache pointer (handled by ORT session state)
 *    so each step is O(seq_len) not O(seq_len^2) — critical for low-RAM devices.
 *
 * Anti-patterns we deliberately avoid:
 *  - Loading all logits into a float[] array (it's 50k+ floats, fine)
 *  - Sorting the entire vocab for nucleus sampling (we use a top-k heap)
 *  - Pre-computing log-probs for the whole sequence each step
 */
class OptoStreamer(
    private val tokenizer: OptoTokenizer,
    private val config: OptoConfig = OptoConfig
) {

    /** One generation step. Returns the next token id, or -1 if EOS. */
    fun step(
        session: OrtSession,
        inputIds: LongArray,
        attentionMask: LongArray,
        pastKeyValuesState: Map<String, OnnxTensor>? = null
    ): StepResult {
        val env = OrtEnvironment.getEnvironment()

        val inputs = HashMap<String, OnnxTensor>()
        inputs["input_ids"] = OnnxTensor.createTensor(env, longArrayOf(*inputIds).reshape2D())
        inputs["attention_mask"] = OnnxTensor.createTensor(env, longArrayOf(*attentionMask).reshape2D())

        // First step: no past KV. Subsequent steps: pass the state in.
        pastKeyValuesState?.forEach { (k, v) -> inputs[k] = v }

        val outputNames = mutableListOf("logits")
        for (i in 0 until 64) { // generous bound; ORT will return only those present
            outputNames.add("present_$i")
        }

        val out = session.run(inputs, outputNames)
        try {
            val logitsTensor = out.get("logits") ?: error("no logits in output")
            @Suppress("UNCHECKED_CAST")
            val raw = logitsTensor.value as Array<Array<FloatArray>> // [1, seq, vocab]
            val lastStep = raw[0][raw[0].size - 1] // FloatArray of vocab size
            val nextId = sample(lastStep)

            // Collect present KV state for next step.
            val nextKv = HashMap<String, OnnxTensor>()
            for (name in outputNames) {
                if (name == "logits") continue
                out.get(name)?.let { nextKv[name] = it as OnnxTensor }
            }

            return StepResult(
                nextId = nextId,
                nextKvState = nextKv,
                isEos = nextId == tokenizer.eosId
            )
        } finally {
            // Close tensors we no longer need (the nextKv ones are kept by the engine).
            inputs.values.forEach { runCatching { it.close() } }
            runCatching { out.close() }
        }
    }

    private fun sample(logits: FloatArray): Int {
        // Temperature scaling
        var max = Float.NEGATIVE_INFINITY
        val scaled = FloatArray(logits.size) { i ->
            val v = logits[i] / config.TEMPERATURE
            if (v > max) max = v
            v
        }

        // Softmax (numerically stable)
        var sumExp = 0.0
        val probs = FloatArray(scaled.size) { i ->
            val e = exp(scaled[i] - max).toDouble()
            sumExp += e
            e.toFloat()
        }
        for (i in probs.indices) probs[i] = (probs[i] / sumExp).toFloat()

        // Top-p (nucleus) sampling via a min-heap of (prob, idx)
        val heap = PriorityQueue<Pair<Float, Int>>(config.TOP_K_INITIAL) { a, b -> a.first.compareTo(b.first) }
        for (i in probs.indices) {
            if (heap.size < heap.comparator?.let { 1024 } ?: 1024) {
                heap.add(probs[i] to i)
            } else if (probs[i] > heap.peek()!!.first) {
                heap.poll()
                heap.add(probs[i] to i)
            }
        }
        val sorted = heap.sortedByDescending { it.first }
        var cum = 0.0f
        val nucleus = ArrayList<Pair<Float, Int>>()
        for ((p, idx) in sorted) {
            nucleus.add(p to idx)
            cum += p
            if (cum >= config.TOP_P) break
        }
        val r = Math.random().toFloat() * cum
        var acc = 0.0f
        for ((p, idx) in nucleus) {
            acc += p
            if (r <= acc) return idx
        }
        return nucleus.lastOrNull()?.second ?: sorted.first().second
    }

    data class StepResult(
        val nextId: Int,
        val nextKvState: Map<String, OnnxTensor>,
        val isEos: Boolean
    )

    private fun LongArray.reshape2D(): Array<LongArray> = arrayOf(longArrayOf(*this))

    companion object {
        private const val TAG = "OptoStreamer"
        private const val TOP_K_INITIAL = 1024
    }
}
