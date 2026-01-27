package com.sbf.assistant.llm

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * Basic tokenizer for TFLite LLM models.
 * Supports SentencePiece-style vocabulary files (text format).
 *
 * LIMITATIONS:
 * - This is a simplified word-level tokenizer with character fallback.
 * - Does NOT implement proper SentencePiece BPE/Unigram algorithms.
 * - Works best with simple prompts; complex text may tokenize poorly.
 * - For production use, consider integrating the SentencePiece library
 *   or using MediaPipe's built-in tokenization (.task models).
 *
 * SUPPORTED FORMATS:
 * - Plain text vocab files (one token per line)
 * - Tab/space-separated vocab files (token + score per line)
 *
 * NOT SUPPORTED:
 * - Binary .model files (SentencePiece native format)
 * - BPE merges files
 * - HuggingFace tokenizer.json format
 */
class LlmTokenizer {
    private val tokenToId = mutableMapOf<String, Int>()
    private val idToToken = mutableMapOf<Int, String>()

    private var bosToken = "<s>"
    private var eosToken = "</s>"
    private var padToken = "<pad>"
    private var unkToken = "<unk>"

    var bosId = 1
        private set
    var eosId = 2
        private set
    var padId = 0
        private set
    var unkId = 3
        private set

    var vocabSize = 0
        private set

    var isLoaded = false
        private set

    /**
     * Load vocabulary from a text file.
     * Expected format: one token per line, or "token score" per line.
     */
    fun loadVocab(vocabPath: String): Boolean {
        return try {
            val file = File(vocabPath)
            if (!file.exists()) {
                Log.e(TAG, "Vocab file not found: $vocabPath")
                return false
            }

            BufferedReader(FileReader(file)).use { reader ->
                var id = 0
                reader.forEachLine { line ->
                    val parts = line.trim().split("\t", " ")
                    val token = if (parts.isNotEmpty()) parts[0] else line.trim()

                    if (token.isNotEmpty()) {
                        tokenToId[token] = id
                        idToToken[id] = token

                        // Detect special tokens
                        when (token) {
                            "<s>", "<bos>", "[BOS]" -> {
                                bosToken = token
                                bosId = id
                            }
                            "</s>", "<eos>", "[EOS]" -> {
                                eosToken = token
                                eosId = id
                            }
                            "<pad>", "[PAD]" -> {
                                padToken = token
                                padId = id
                            }
                            "<unk>", "[UNK]" -> {
                                unkToken = token
                                unkId = id
                            }
                        }

                        id++
                    }
                }
                vocabSize = id
            }

            isLoaded = true
            Log.d(TAG, "Loaded vocab with $vocabSize tokens. BOS=$bosId, EOS=$eosId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vocab", e)
            false
        }
    }

    /**
     * Encode text to token IDs.
     * Simple character/word-based encoding for demonstration.
     * For production, use proper SentencePiece tokenization.
     */
    fun encode(text: String, addBos: Boolean = true): IntArray {
        val tokens = mutableListOf<Int>()

        if (addBos) {
            tokens.add(bosId)
        }

        // Simple word-level tokenization with subword fallback
        val words = text.split(Regex("\\s+"))
        for (word in words) {
            val wordWithSpace = " $word"
            when {
                tokenToId.containsKey(wordWithSpace) -> {
                    tokens.add(tokenToId[wordWithSpace]!!)
                }
                tokenToId.containsKey(word) -> {
                    tokens.add(tokenToId[word]!!)
                }
                else -> {
                    // Character-level fallback
                    for (char in word) {
                        val charStr = char.toString()
                        tokens.add(tokenToId[charStr] ?: unkId)
                    }
                }
            }
        }

        return tokens.toIntArray()
    }

    /**
     * Decode token IDs to text.
     */
    fun decode(tokenIds: IntArray, skipSpecialTokens: Boolean = true): String {
        val result = StringBuilder()

        for (id in tokenIds) {
            if (skipSpecialTokens && (id == bosId || id == eosId || id == padId)) {
                continue
            }

            val token = idToToken[id] ?: unkToken
            result.append(token)
        }

        return result.toString()
            .replace("▁", " ")  // SentencePiece space marker
            .trim()
    }

    /**
     * Decode a single token ID to text.
     */
    fun decodeToken(tokenId: Int): String {
        if (tokenId == bosId || tokenId == eosId || tokenId == padId) {
            return ""
        }
        return (idToToken[tokenId] ?: unkToken).replace("▁", " ")
    }

    /**
     * Check if token ID is EOS.
     */
    fun isEos(tokenId: Int): Boolean = tokenId == eosId

    companion object {
        private const val TAG = "LlmTokenizer"
    }
}
