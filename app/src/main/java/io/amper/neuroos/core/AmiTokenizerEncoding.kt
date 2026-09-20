package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

data class AmiTokenizerEncodeConfig(
    val addBosToken: Boolean? = null,
    val addEosToken: Boolean? = null
)

object AmiTokenizerEncoder {
    fun encode(
        lexicon: AmiTokenizerLexicon,
        text: String,
        config: AmiTokenizerEncodeConfig = AmiTokenizerEncodeConfig()
    ): IntArray {
        val model = lexicon.model?.lowercase()
            ?: error("AMI tokenizer model metadata is missing")
        val body = when {
            model == "gpt2" || model == "bpe" ->
                Gpt2BpeEncoder.encodeBody(lexicon, text)

            model == "llama" || model == "spm" || model == "sentencepiece" ->
                SentencePieceUnigramEncoder.encodeBody(lexicon, text)

            else -> error("unsupported AMI tokenizer encoder model: $model")
        }

        val addBos = config.addBosToken
            ?: lexicon.special.addBosToken
            ?: false
        val addEos = config.addEosToken
            ?: lexicon.special.addEosToken
            ?: false

        val result = ArrayList<Int>(
            body.size + (if (addBos) 1 else 0) + (if (addEos) 1 else 0)
        )
        if (addBos) {
            result += requireNotNull(lexicon.special.bosTokenId) {
                "AMI tokenizer requests BOS insertion but BOS id is missing"
            }
        }
        result.addAll(body.asList())
        if (addEos) {
            result += requireNotNull(lexicon.special.eosTokenId) {
                "AMI tokenizer requests EOS insertion but EOS id is missing"
            }
        }
        return result.toIntArray()
    }
}

private object Gpt2BpeEncoder {
    private val preTokenPattern = Pattern.compile(
        "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+"
    )

    fun encodeBody(
        lexicon: AmiTokenizerLexicon,
        text: String
    ): IntArray {
        require(lexicon.merges.isNotEmpty()) {
            "GPT-2 BPE tokenizer requires preserved merge rules"
        }

        val tokenToId = lexicon.tokens
            .withIndex()
            .associate { it.value to it.index }

        val ranks = linkedMapOf<Pair<String, String>, Int>()
        lexicon.merges.forEachIndexed { rank, merge ->
            val split = merge.indexOf(' ')
            require(split > 0 && split < merge.lastIndex) {
                "malformed GPT-2 BPE merge rule"
            }
            val pair = Pair(
                merge.substring(0, split),
                merge.substring(split + 1)
            )
            require(ranks.put(pair, rank) == null) {
                "duplicate GPT-2 BPE merge rule"
            }
        }

        val output = ArrayList<Int>()
        val matcher = preTokenPattern.matcher(text)
        var coveredUntil = 0
        while (matcher.find()) {
            require(matcher.start() == coveredUntil) {
                "GPT-2 pre-tokenizer did not cover input contiguously"
            }
            val segment = matcher.group()
            val encoded = byteUnicodeEncode(
                segment.toByteArray(StandardCharsets.UTF_8)
            )
            val symbols = bpe(encoded, ranks)
            symbols.forEach { symbol ->
                output += requireNotNull(tokenToId[symbol]) {
                    "GPT-2 BPE result is missing from AMI vocabulary: $symbol"
                }
            }
            coveredUntil = matcher.end()
        }
        require(coveredUntil == text.length) {
            "GPT-2 pre-tokenizer did not consume the complete input"
        }
        return output.toIntArray()
    }

    private fun bpe(
        encoded: String,
        ranks: Map<Pair<String, String>, Int>
    ): List<String> {
        val symbols = encoded.codePoints()
            .toArray()
            .map { String(Character.toChars(it)) }
            .toMutableList()
        if (symbols.size <= 1) return symbols

        while (true) {
            var bestIndex = -1
            var bestRank = Int.MAX_VALUE
            for (index in 0 until symbols.lastIndex) {
                val rank = ranks[Pair(symbols[index], symbols[index + 1])]
                    ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestIndex = index
                }
            }
            if (bestIndex < 0) break

            val first = symbols[bestIndex]
            val second = symbols[bestIndex + 1]
            val merged = ArrayList<String>(symbols.size)
            var index = 0
            while (index < symbols.size) {
                if (
                    index < symbols.lastIndex &&
                    symbols[index] == first &&
                    symbols[index + 1] == second
                ) {
                    merged += first + second
                    index += 2
                } else {
                    merged += symbols[index]
                    index += 1
                }
            }
            symbols.clear()
            symbols.addAll(merged)
        }
        return symbols
    }

    private fun byteUnicodeEncode(bytes: ByteArray): String {
        val builder = StringBuilder()
        bytes.forEach { raw ->
            val byte = raw.toInt() and 0xff
            val codePoint = GPT2_BYTE_TO_CODE_POINT[byte]
            builder.appendCodePoint(codePoint)
        }
        return builder.toString()
    }

    private val GPT2_BYTE_TO_CODE_POINT: IntArray = IntArray(256).also { map ->
        val direct = ArrayList<Int>()
        for (value in 33..126) direct += value
        for (value in 161..172) direct += value
        for (value in 174..255) direct += value

        val directSet = direct.toHashSet()
        var extra = 0
        for (byte in 0..255) {
            if (byte in directSet) {
                map[byte] = byte
            } else {
                map[byte] = 256 + extra
                extra += 1
            }
        }
    }
}

private object SentencePieceUnigramEncoder {
    private data class Candidate(
        val tokenId: Int,
        val piece: String,
        val score: Double
    )

    private data class Step(
        val previousPosition: Int,
        val tokenIds: IntArray
    )

    fun encodeBody(
        lexicon: AmiTokenizerLexicon,
        text: String
    ): IntArray {
        val scores = requireNotNull(lexicon.scores) {
            "SentencePiece unigram tokenizer requires preserved token scores"
        }
        require(scores.size == lexicon.vocabularySize)

        if (text.isEmpty()) return intArrayOf()

        val normalized = normalize(text)
        val byFirstChar = linkedMapOf<Char, MutableList<Candidate>>()
        lexicon.tokens.forEachIndexed { tokenId, piece ->
            if (piece.isEmpty()) return@forEachIndexed
            val type = lexicon.tokenType(tokenId)
            if (
                type == AmiTokenTypes.CONTROL ||
                type == AmiTokenTypes.UNUSED ||
                type == AmiTokenTypes.BYTE
            ) {
                return@forEachIndexed
            }
            byFirstChar.getOrPut(piece[0]) { mutableListOf() } +=
                Candidate(
                    tokenId = tokenId,
                    piece = piece,
                    score = scores[tokenId].toDouble()
                )
        }
        byFirstChar.values.forEach { candidates ->
            candidates.sortByDescending { it.piece.length }
        }

        val byteFallback = buildByteFallbackMap(lexicon)
        val length = normalized.length
        val best = DoubleArray(length + 1) { Double.NEGATIVE_INFINITY }
        val steps = arrayOfNulls<Step>(length + 1)
        best[0] = 0.0

        for (position in 0 until length) {
            if (!best[position].isFinite()) continue

            byFirstChar[normalized[position]].orEmpty().forEach { candidate ->
                if (
                    normalized.regionMatches(
                        thisOffset = position,
                        other = candidate.piece,
                        otherOffset = 0,
                        length = candidate.piece.length
                    )
                ) {
                    val end = position + candidate.piece.length
                    val score = best[position] + candidate.score
                    if (score > best[end]) {
                        best[end] = score
                        steps[end] = Step(
                            previousPosition = position,
                            tokenIds = intArrayOf(candidate.tokenId)
                        )
                    }
                }
            }

            val codePoint = normalized.codePointAt(position)
            val charCount = Character.charCount(codePoint)
            val next = position + charCount
            val bytes = String(Character.toChars(codePoint))
                .toByteArray(StandardCharsets.UTF_8)
            val fallbackIds = IntArray(bytes.size)
            var completeFallback = true
            bytes.indices.forEach { index ->
                val value = bytes[index].toInt() and 0xff
                val id = byteFallback[value]
                if (id == null) {
                    completeFallback = false
                } else {
                    fallbackIds[index] = id
                }
            }

            val fallbackTokens = when {
                completeFallback -> fallbackIds
                lexicon.special.unknownTokenId != null ->
                    intArrayOf(lexicon.special.unknownTokenId)
                else -> null
            }

            if (fallbackTokens != null) {
                val score = best[position] - 1_000_000.0 -
                    fallbackTokens.size.toDouble()
                if (score > best[next]) {
                    best[next] = score
                    steps[next] = Step(
                        previousPosition = position,
                        tokenIds = fallbackTokens
                    )
                }
            }
        }

        require(best[length].isFinite()) {
            "SentencePiece encoder cannot represent input with current AMI vocabulary"
        }

        val reversed = ArrayList<Int>()
        var position = length
        while (position > 0) {
            val step = requireNotNull(steps[position]) {
                "SentencePiece encoder backtrace is incomplete"
            }
            for (index in step.tokenIds.indices.reversed()) {
                reversed += step.tokenIds[index]
            }
            position = step.previousPosition
        }
        reversed.reverse()
        return reversed.toIntArray()
    }

    private fun normalize(text: String): String {
        val spaces = text.replace(' ', '▁')
        return if (spaces.startsWith("▁")) spaces else "▁$spaces"
    }

    private fun buildByteFallbackMap(
        lexicon: AmiTokenizerLexicon
    ): Map<Int, Int> {
        val map = linkedMapOf<Int, Int>()
        lexicon.tokens.forEachIndexed { tokenId, piece ->
            val byte = parseBytePiece(piece) ?: return@forEachIndexed
            require(map.put(byte, tokenId) == null) {
                "duplicate SentencePiece byte-fallback token"
            }
        }
        return map
    }

    private fun parseBytePiece(piece: String): Int? {
        if (
            piece.length == 6 &&
            piece.startsWith("<0x") &&
            piece.endsWith(">")
        ) {
            return piece.substring(3, 5).toIntOrNull(16)
        }
        return null
    }
}
