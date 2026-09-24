package com.optobot.native

import android.content.Context
import android.util.JsonReader
import android.util.Log
import java.io.StringReader

/**
 * OptoTokenizer — minimal BPE tokenizer for the bundled model.
 *
 * Why we re-implement instead of pulling a heavyweight dep:
 *  - We do not want a "tokenizer.json" that names the source model (stealth).
 *  - The bundled `opto_meta.bin` is a JSON blob we encrypt: { vocab, merges, special }.
 *  - This class only knows how to read that blob — it does not know or care
 *    which model architecture it is feeding.
 *
 * Implementation:
 *  - Greedy longest-match BPE merge loop (O(1) per merge via a hash lookup).
 *  - Pre-tokenization is a simple regex split on whitespace + punctuation,
 *    matching the GPT-style pretokenizer that the LFM2 tokenizer uses.
 */
class OptoTokenizer(private val context: Context) {

    data class Meta(
        val vocab: Map<String, Int>,
        val idToToken: Map<Int, String>,
        val merges: List<Pair<String, String>>,
        val mergesRank: Map<Pair<String, String>, Int>,
        val bosId: Int,
        val eosId: Int,
        val padId: Int,
        val chatPrefix: String,   // e.g. "<|im_start|>user\n"
        val chatSuffix: String    // e.g. "<|im_end|>\n<|im_start|>assistant\n"
    )

    private var meta: Meta? = null

    fun load() {
        if (meta != null) return
        val bytes = OptoAssetDecryptor(context).decryptSmallAsset(OptoConfig.ASSET_TOKENIZER)
        val json = String(bytes, Charsets.UTF_8)
        meta = parseMeta(json)
    }

    private fun parseMeta(json: String): Meta {
        val vocab = HashMap<String, Int>()
        val idToToken = HashMap<Int, String>()
        val merges = ArrayList<Pair<String, String>>()
        val mergesRank = HashMap<Pair<String, String>, Int>()
        var bosId = -1
        var eosId = -1
        var padId = -1
        var chatPrefix = "<|im_start|>user\n"
        var chatSuffix = "<|im_end|>\n<|im_start|>assistant\n"

        JsonReader(StringReader(json)).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "vocab" -> {
                        r.beginObject()
                        while (r.hasNext()) {
                            val tok = r.nextName()
                            val id = r.nextInt()
                            vocab[tok] = id
                            idToToken[id] = tok
                        }
                        r.endObject()
                    }
                    "merges" -> {
                        r.beginArray()
                        while (r.hasNext()) {
                            val m = r.nextString()
                            val parts = m.split(" ", limit = 2)
                            if (parts.size == 2) {
                                val pair = parts[0] to parts[1]
                                merges.add(pair)
                                mergesRank[pair] = mergesRank.size
                            }
                        }
                        r.endArray()
                    }
                    "special" -> {
                        r.beginObject()
                        while (r.hasNext()) {
                            when (val name = r.nextName()) {
                                "bos", "bos_id" -> bosId = r.nextInt()
                                "eos", "eos_id" -> eosId = r.nextInt()
                                "pad", "pad_id" -> padId = r.nextInt()
                                else -> r.skipValue()
                            }
                            if (name.isEmpty()) {}
                        }
                        r.endObject()
                    }
                    "chat" -> {
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "userPrefix" -> chatPrefix = r.nextString()
                                "assistantPrefix" -> chatSuffix = r.nextString()
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }

        return Meta(vocab, idToToken, merges, mergesRank, bosId, eosId, padId, chatPrefix, chatSuffix)
    }

    /** Encode a single user string into BPE token ids. */
    fun encode(text: String, addBos: Boolean = true): IntArray {
        val m = meta ?: error("Tokenizer not loaded")
        val ids = ArrayList<Int>()

        if (addBos && m.bosId >= 0) ids.add(m.bosId)

        val pretokens = pretokenize(text)
        for (chunk in pretokens) {
            val parts = chunk.map { it.toString() }.toMutableList()
            // Greedy merges
            while (parts.size > 1) {
                var bestRank = Int.MAX_VALUE
                var bestIdx = -1
                for (i in 0 until parts.size - 1) {
                    val rank = m.mergesRank[parts[i] to parts[i + 1]] ?: continue
                    if (rank < bestRank) { bestRank = rank; bestIdx = i }
                }
                if (bestIdx < 0) break
                parts[bestIdx] = parts[bestIdx] + parts[bestIdx + 1]
                parts.removeAt(bestIdx + 1)
            }
            for (p in parts) {
                val id = m.vocab[p] ?: m.vocab["<unk>"] ?: continue
                ids.add(id)
            }
        }
        return ids.toIntArray()
    }

    /** Decode ids back to a string. Drops BPE space markers (Ġ, Ċ) per GPT-BPE convention. */
    fun decode(ids: IntArray): String {
        val m = meta ?: error("Tokenizer not loaded")
        val sb = StringBuilder()
        for (id in ids) {
            val tok = m.idToToken[id] ?: continue
            sb.append(tok)
        }
        return sb.toString()
            .replace("Ġ", " ")
            .replace("Ċ", "\n")
    }

    /** Build a chat-formatted prompt: prefix + user text + assistant prefix */
    fun buildChatPrompt(userText: String): IntArray {
        val m = meta ?: error("Tokenizer not loaded")
        val full = m.chatPrefix + userText + m.chatSuffix
        return encode(full, addBos = false)
    }

    val eosId: Int get() = meta?.eosId ?: -1

    private fun pretokenize(text: String): List<String> {
        // GPT-2/4 style pretokenizer regex simplified to:
        //   words, runs of whitespace, runs of punctuation, single digits
        val out = ArrayList<String>()
        val re = Regex("""'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+""")
        for (m in re.findAll(text)) out.add(m.value)
        return out
    }

    companion object {
        private const val TAG = "OptoTokenizer"
    }
}
