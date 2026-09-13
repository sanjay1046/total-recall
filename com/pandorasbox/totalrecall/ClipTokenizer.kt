package com.pandorasbox.totalrecall

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.regex.Pattern

class ClipTokenizer(private val context: Context) {
    companion object {
        private const val TAG = "ClipTokenizer"
        const val MAX_SEQ_LENGTH = 77
        const val START_TOKEN_ID = 49406 // <|startoftext|>
        const val END_TOKEN_ID = 49407   // <|endoftext|>
    }

    private val encoder = mutableMapOf<String, Int>()
    private val bpeRanks = mutableMapOf<Pair<String, String>, Int>()
    private val byteEncoder: Map<Byte, String>
    private val bpeCache = mutableMapOf<String, String>()

    var isInitialized = false
        private set

    init {
        byteEncoder = bytesToUnicode()
        isInitialized = loadAssets()
    }

    private fun bytesToUnicode(): Map<Byte, String> {
        val bs = mutableListOf<Int>()
        for (i in '!'.code..'~'.code) bs.add(i)
        for (i in '¡'.code..'¬'.code) bs.add(i)
        for (i in '®'.code..'ÿ'.code) bs.add(i)

        val cs = bs.toMutableList()
        var n = 0
        for (b in 0..255) {
            if (!bs.contains(b)) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }
        return bs.indices.associate { bs[it].toByte() to cs[it].toChar().toString() }
    }

    private fun loadAssets(): Boolean {
        try {
            // 1. Load vocab.json
            val vocabInputStream = context.assets.open("vocab.json")
            val vocabJsonStr = vocabInputStream.bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(vocabJsonStr)
            for (key in jsonObject.keys()) {
                encoder[key] = jsonObject.getInt(key)
            }

            // 2. Load merges.txt
            val mergesInputStream = context.assets.open("merges.txt")
            var rank = 0
            BufferedReader(InputStreamReader(mergesInputStream)).use { reader ->
                reader.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        val parts = trimmed.split(Regex("\\s+"))
                        if (parts.size >= 2) {
                            bpeRanks[Pair(parts[0], parts[1])] = rank++
                        }
                    }
                }
            }

            Log.i(TAG, "ClipTokenizer loaded vocab size ${encoder.size} and ${bpeRanks.size} merges.")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "ClipTokenizer assets (vocab.json / merges.txt) missing from assets: ${e.message}")
            return false
        }
    }

    fun encode(text: String): LongArray {
        val tokenIds = LongArray(MAX_SEQ_LENGTH) { 0L }
        if (!isInitialized) {
            Log.e(TAG, "ClipTokenizer not initialized.")
            return tokenIds
        }

        tokenIds[0] = START_TOKEN_ID.toLong()

        val cleaned = text.lowercase().trim()
        val regex = Pattern.compile("<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+")
        val matcher = regex.matcher(cleaned)

        val tokensList = mutableListOf<Int>()
        while (matcher.find()) {
            val token = matcher.group()
            val utf8Bytes = token.toByteArray(Charsets.UTF_8)
            val byteString = StringBuilder()
            for (b in utf8Bytes) {
                byteString.append(byteEncoder[b] ?: b.toInt().toChar().toString())
            }

            val bpeResult = bpe(byteString.toString())
            for (bpeToken in bpeResult.split(" ")) {
                val id = encoder[bpeToken]
                if (id != null) {
                    tokensList.add(id)
                }
            }
        }

        var idx = 1
        for (id in tokensList) {
            if (idx >= MAX_SEQ_LENGTH - 1) break
            tokenIds[idx++] = id.toLong()
        }

        if (idx < MAX_SEQ_LENGTH) {
            tokenIds[idx] = END_TOKEN_ID.toLong()
        }

        return tokenIds
    }

    private fun bpe(token: String): String {
        if (bpeCache.containsKey(token)) {
            return bpeCache[token]!!
        }

        val word = mutableListOf<String>()
        for (i in 0 until token.length - 1) {
            word.add(token[i].toString())
        }
        if (token.isNotEmpty()) {
            word.add("${token.last()}</w>")
        }

        var pairs = getPairs(word)
        if (pairs.isEmpty()) {
            return "${token}</w>"
        }

        while (true) {
            val bigram = pairs.minByOrNull { bpeRanks[it] ?: Int.MAX_VALUE } ?: break
            if (!bpeRanks.containsKey(bigram)) {
                break
            }

            val first = bigram.first
            val second = bigram.second
            val newWord = mutableListOf<String>()
            var i = 0
            while (i < word.size) {
                val j = word.subList(i, word.size).indexOf(first)
                if (j == -1) {
                    newWord.addAll(word.subList(i, word.size))
                    break
                }
                val matchIdx = i + j
                newWord.addAll(word.subList(i, matchIdx))
                i = matchIdx

                if (word[i] == first && i < word.size - 1 && word[i + 1] == second) {
                    newWord.add(first + second)
                    i += 2
                } else {
                    newWord.add(word[i])
                    i += 1
                }
            }

            word.clear()
            word.addAll(newWord)

            if (word.size == 1) {
                break
            } else {
                pairs = getPairs(word)
            }
        }

        val result = word.joinToString(" ")
        bpeCache[token] = result
        return result
    }

    private fun getPairs(word: List<String>): Set<Pair<String, String>> {
        val pairs = mutableSetOf<Pair<String, String>>()
        if (word.size < 2) return pairs
        for (i in 0 until word.size - 1) {
            pairs.add(Pair(word[i], word[i + 1]))
        }
        return pairs
    }
}
