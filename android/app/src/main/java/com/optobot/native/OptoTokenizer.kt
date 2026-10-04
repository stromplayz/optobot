package com.optobot.native

import android.content.Context
import android.util.JsonReader
import java.io.StringReader

/**
 * OptoTokenizer — BPE tokenizer that reads the encrypted opto_meta.bin.
 *
 * The meta file (built by scripts/build_tokenizer_meta.py) contains:
 *   {
 *     "vocab": { "<token>": <id>, ... },
 *     "merges": ["<a> <b>", ...],
 *     "special": { "bos_id": <id>, "eos_id": <id>, "pad_id": <id> },
 *     "chat": { "userPrefix": "...", "assistantPrefix": "..." }
 *   }
 *
 * Encoding is greedy longest-match BPE (same as GPT-2/LLaMA tokenizers).
 * Decoding reverses the BPE space markers (Ġ → space, Ċ → newline).
 */
class OptoTokenizer(private val context: Context) {

    data class Meta(
        val vocab: Map<String, Int>,
        val idToToken: Map<Int, String>,
        val mergesRank: Map<Pair<String, String>, Int>,
        val bosId: Int,
        val eosId: Int,
        val padId: Int,
        val chatPrefix: String,
        val chatSuffix: String,
    )

    private var meta: Meta? = null

    val isLoaded: Boolean get() = meta != null
    val eosId: Int get() = meta?.eosId ?: -1

    fun load() {
        if (meta != null) return
        try {
            val bytes = OptoAssetDecryptor(context).decryptSmallAsset(OptoConfig.ASSET_TOKENIZER)
            val json = String(bytes, Charsets.UTF_8)
            meta = parseMeta(json)
            Log.i(TAG, "Tokenizer loaded: ${meta!!.vocab.size} vocab, ${meta!!.mergesRank.size} merges")
        } catch (t: Throwable) {
            Log.e(TAG, "Tokenizer load failed — falling back to char-level", t)
            // Fallback: minimal char-level tokenizer
            meta = Meta(
                vocab = emptyMap(),
                idToToken = emptyMap(),
                mergesRank = emptyMap(),
                bosId = -1, eosId = -1, padId = -1,
                chatPrefix = "", chatSuffix = "",
            )
        }
    }

    private fun parseMeta(json: String): Meta {
        val vocab = HashMap<String, Int>()
        val idToToken = HashMap<Int, String>()
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
                        var rank = 0
                        while (r.hasNext()) {
                            val m = r.nextString()
                            val parts = m.split(" ", limit = 2)
                            if (parts.size == 2) {
                                mergesRank[parts[0] to parts[1]] = rank++
                            }
                        }
                        r.endObject()
                    }
                    "special" -> {
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "bos_id" -> bosId = r.nextInt()
                                "eos_id" -> eosId = r.nextInt()
                                "pad_id" -> padId = r.nextInt()
                                else -> r.skipValue()
                            }
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

        return Meta(vocab, idToToken, mergesRank, bosId, eosId, padId, chatPrefix, chatSuffix)
    }

    /** Encode text into BPE token ids. */
    fun encode(text: String, addBos: Boolean = true): LongArray {
        val m = meta ?: error("Tokenizer not loaded")
        if (m.vocab.isEmpty()) {
            // Char-level fallback
            val ids = ArrayList<Long>()
            if (addBos && m.bosId >= 0) ids.add(m.bosId.toLong())
            for (c in text) ids.add(c.code.toLong())
            return ids.toLongArray()
        }

        val ids = ArrayList<Long>()
        if (addBos && m.bosId >= 0) ids.add(m.bosId.toLong())

        // GPT-2 style pre-tokenization
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
                ids.add(id.toLong())
            }
        }
        return ids.toLongArray()
    }

    /** Build a chat-formatted prompt. */
    fun buildChatPrompt(userText: String): LongArray {
        val m = meta ?: error("Tokenizer not loaded")
        val full = m.chatPrefix + userText + m.chatSuffix
        return encode(full, addBos = false)
    }

    /** Decode ids back to text. */
    fun decode(ids: LongArray): String {
        val m = meta ?: error("Tokenizer not loaded")
        if (m.idToToken.isEmpty()) {
            // Char-level fallback
            return ids.map { it.toInt().toChar() }.joinToString("")
        }
        val sb = StringBuilder()
        for (id in ids) {
            val tok = m.idToToken[id.toInt()] ?: continue
            sb.append(tok)
        }
        return sb.toString()
            .replace("Ġ", " ")
            .replace("Ċ", "\n")
    }

    private fun pretokenize(text: String): List<String> {
        val out = ArrayList<String>()
        val re = Regex("""'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+""")
        for (match in re.findAll(text)) out.add(match.value)
        return out
    }

    companion object {
        private const val TAG = "OptoTokenizer"
    }
}
