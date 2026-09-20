package io.amper.neuroos.core

import java.io.EOFException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

data class AmiTokenizerSpecialTokens(
    val bosTokenId: Int?,
    val eosTokenId: Int?,
    val unknownTokenId: Int?,
    val paddingTokenId: Int?,
    val addBosToken: Boolean?,
    val addEosToken: Boolean?
)

data class AmiTokenizerLexicon(
    val model: String?,
    val preTokenizer: String?,
    val tokens: List<String>,
    val scores: FloatArray?,
    val tokenTypes: IntArray?,
    val merges: List<String>,
    val special: AmiTokenizerSpecialTokens
) {
    init {
        require(tokens.isNotEmpty())
        scores?.let { require(it.size == tokens.size) }
        tokenTypes?.let { require(it.size == tokens.size) }
        require(
            listOfNotNull(
                special.bosTokenId,
                special.eosTokenId,
                special.unknownTokenId,
                special.paddingTokenId
            ).all { it in tokens.indices }
        ) {
            "AMI tokenizer special token id is outside vocabulary"
        }
    }

    val vocabularySize: Int
        get() = tokens.size

    fun piece(tokenId: Int): String {
        require(tokenId in tokens.indices)
        return tokens[tokenId]
    }

    fun tokenType(tokenId: Int): Int? {
        require(tokenId in tokens.indices)
        return tokenTypes?.get(tokenId)
    }
}

/**
 * Targeted reader for tokenizer arrays preserved byte-for-byte in the AMI TOKENIZER section.
 *
 * The general scalar metadata reader deliberately skips large arrays. This reader materializes only
 * tokenizer-owned arrays and bounds each allocation before reading it.
 */
class AmiTokenizerLexiconReader(
    private val maxVocabularySize: Int = 1_000_000,
    private val maxTokenBytes: Int = 1 * 1024 * 1024,
    private val maxMerges: Int = 4_000_000
) {
    init {
        require(maxVocabularySize > 0)
        require(maxTokenBytes > 0)
        require(maxMerges >= 0)
    }

    fun read(loaded: AmiLoadedArtifact): Result<AmiTokenizerLexicon> = runCatching {
        val metadataCount = metadataCount(loaded)
        val section = loaded.index.sections.single {
            it.type == AmiSectionType.TOKENIZER
        }

        var model: String? = null
        var preTokenizer: String? = null
        var tokens: List<String>? = null
        var scores: FloatArray? = null
        var tokenTypes: IntArray? = null
        var merges: List<String> = emptyList()
        var bos: Int? = null
        var eos: Int? = null
        var unknown: Int? = null
        var padding: Int? = null
        var addBos: Boolean? = null
        var addEos: Boolean? = null

        RandomAccessFile(loaded.file, "r").use { raf ->
            raf.seek(section.offset)
            val end = section.endExclusive

            repeat(metadataCount) {
                val key = readString(
                    raf,
                    end,
                    "GGUF metadata key",
                    65_535
                )
                val type = readU32(raf, end)
                when (key) {
                    "tokenizer.ggml.model" -> {
                        require(type == TYPE_STRING)
                        model = readString(
                            raf,
                            end,
                            key,
                            maxTokenBytes
                        )
                    }
                    "tokenizer.ggml.pre" -> {
                        require(type == TYPE_STRING)
                        preTokenizer = readString(
                            raf,
                            end,
                            key,
                            maxTokenBytes
                        )
                    }
                    "tokenizer.ggml.tokens" -> {
                        require(type == TYPE_ARRAY)
                        require(readU32(raf, end) == TYPE_STRING) {
                            "tokenizer.ggml.tokens must be a string array"
                        }
                        val count = checkedCount(
                            readU64(raf, end),
                            maxVocabularySize,
                            key
                        )
                        tokens = List(count) {
                            readString(
                                raf,
                                end,
                                "tokenizer token",
                                maxTokenBytes
                            )
                        }
                    }
                    "tokenizer.ggml.scores" -> {
                        require(type == TYPE_ARRAY)
                        val elementType = readU32(raf, end)
                        require(elementType == TYPE_FLOAT32) {
                            "tokenizer.ggml.scores must be float32"
                        }
                        val count = checkedCount(
                            readU64(raf, end),
                            maxVocabularySize,
                            key
                        )
                        scores = FloatArray(count) {
                            Float.fromBits(readU32(raf, end).toInt())
                        }
                    }
                    "tokenizer.ggml.token_type" -> {
                        require(type == TYPE_ARRAY)
                        val elementType = readU32(raf, end)
                        require(elementType == TYPE_INT32) {
                            "tokenizer.ggml.token_type must be int32"
                        }
                        val count = checkedCount(
                            readU64(raf, end),
                            maxVocabularySize,
                            key
                        )
                        tokenTypes = IntArray(count) {
                            readU32(raf, end).toInt()
                        }
                    }
                    "tokenizer.ggml.merges" -> {
                        require(type == TYPE_ARRAY)
                        require(readU32(raf, end) == TYPE_STRING) {
                            "tokenizer.ggml.merges must be a string array"
                        }
                        val count = checkedCount(
                            readU64(raf, end),
                            maxMerges,
                            key
                        )
                        merges = List(count) {
                            readString(
                                raf,
                                end,
                                "tokenizer merge",
                                maxTokenBytes
                            )
                        }
                    }
                    "tokenizer.ggml.bos_token_id" -> {
                        bos = readTokenIdScalar(raf, end, type, key)
                    }
                    "tokenizer.ggml.eos_token_id" -> {
                        eos = readTokenIdScalar(raf, end, type, key)
                    }
                    "tokenizer.ggml.unknown_token_id" -> {
                        unknown = readTokenIdScalar(raf, end, type, key)
                    }
                    "tokenizer.ggml.padding_token_id" -> {
                        padding = readTokenIdScalar(raf, end, type, key)
                    }
                    "tokenizer.ggml.add_bos_token" -> {
                        require(type == TYPE_BOOL)
                        addBos = readBoolean(raf, end, key)
                    }
                    "tokenizer.ggml.add_eos_token" -> {
                        require(type == TYPE_BOOL)
                        addEos = readBoolean(raf, end, key)
                    }
                    else -> skipValue(raf, end, type, 0)
                }
            }

            require(raf.filePointer == end) {
                "AMI tokenizer metadata length does not match GRAPH_IR count"
            }
        }

        val resolvedTokens = requireNotNull(tokens) {
            "preserved GGUF tokenizer vocabulary is missing"
        }
        scores?.let {
            require(it.size == resolvedTokens.size) {
                "tokenizer score count differs from vocabulary"
            }
        }
        tokenTypes?.let {
            require(it.size == resolvedTokens.size) {
                "tokenizer type count differs from vocabulary"
            }
        }

        AmiTokenizerLexicon(
            model = model,
            preTokenizer = preTokenizer,
            tokens = resolvedTokens,
            scores = scores,
            tokenTypes = tokenTypes,
            merges = merges,
            special = AmiTokenizerSpecialTokens(
                bosTokenId = bos,
                eosTokenId = eos,
                unknownTokenId = unknown,
                paddingTokenId = padding,
                addBosToken = addBos,
                addEosToken = addEos
            )
        )
    }

    private fun metadataCount(loaded: AmiLoadedArtifact): Int {
        val graph = loaded.index.sections.single {
            it.type == AmiSectionType.GRAPH_IR
        }
        require(graph.length in 1L..64L * 1024L)
        val text = RandomAccessFile(loaded.file, "r").use { raf ->
            raf.seek(graph.offset)
            ByteArray(graph.length.toInt()).also(raf::readFully)
                .toString(Charsets.UTF_8)
        }
        val raw = text.lineSequence()
            .firstOrNull { it.trim().startsWith("metadata_count=") }
            ?.substringAfter('=')
            ?.trim()
            ?: error("AMI GRAPH_IR metadata_count is missing")
        return raw.toInt().also {
            require(it in 1..1_000_000)
        }
    }

    private fun readTokenIdScalar(
        raf: RandomAccessFile,
        end: Long,
        type: Long,
        key: String
    ): Int {
        val value = when (type) {
            TYPE_UINT8 -> readU8(raf, end).toLong()
            TYPE_INT8 -> readU8(raf, end).toByte().toLong()
            TYPE_UINT16 -> readU16(raf, end).toLong()
            TYPE_INT16 -> readU16(raf, end).toShort().toLong()
            TYPE_UINT32 -> readU32(raf, end)
            TYPE_INT32 -> readU32(raf, end).toInt().toLong()
            TYPE_UINT64 -> {
                val raw = readU64(raf, end)
                require(raw <= Long.MAX_VALUE.toULong()) {
                    "$key exceeds signed mobile token-id range"
                }
                raw.toLong()
            }
            TYPE_INT64 -> readU64(raf, end).toLong()
            else -> error("$key must be an integer metadata value")
        }
        require(value in 0L..Int.MAX_VALUE.toLong()) {
            "$key is outside AMI token-id range"
        }
        return value.toInt()
    }

    private fun readBoolean(
        raf: RandomAccessFile,
        end: Long,
        key: String
    ): Boolean = when (val value = readU8(raf, end)) {
        0 -> false
        1 -> true
        else -> error("$key contains invalid boolean value: $value")
    }

    private fun skipValue(
        raf: RandomAccessFile,
        end: Long,
        type: Long,
        depth: Int
    ) {
        require(depth < 16)
        when (type) {
            TYPE_UINT8,
            TYPE_INT8,
            TYPE_BOOL -> skipExact(raf, end, 1)
            TYPE_UINT16,
            TYPE_INT16 -> skipExact(raf, end, 2)
            TYPE_UINT32,
            TYPE_INT32,
            TYPE_FLOAT32 -> skipExact(raf, end, 4)
            TYPE_UINT64,
            TYPE_INT64,
            TYPE_FLOAT64 -> skipExact(raf, end, 8)
            TYPE_STRING -> {
                val length = readU64(raf, end)
                require(length <= MAX_SKIPPED_STRING_BYTES.toULong())
                skipExact(raf, end, length.toLong())
            }
            TYPE_ARRAY -> {
                val elementType = readU32(raf, end)
                val count = readU64(raf, end)
                require(count <= MAX_ARRAY_ELEMENTS.toULong())
                when (elementType) {
                    TYPE_UINT8,
                    TYPE_INT8,
                    TYPE_BOOL ->
                        skipExact(raf, end, checkedBytes(count, 1))
                    TYPE_UINT16,
                    TYPE_INT16 ->
                        skipExact(raf, end, checkedBytes(count, 2))
                    TYPE_UINT32,
                    TYPE_INT32,
                    TYPE_FLOAT32 ->
                        skipExact(raf, end, checkedBytes(count, 4))
                    TYPE_UINT64,
                    TYPE_INT64,
                    TYPE_FLOAT64 ->
                        skipExact(raf, end, checkedBytes(count, 8))
                    TYPE_STRING -> repeatUlong(count) {
                        val length = readU64(raf, end)
                        require(length <= MAX_SKIPPED_STRING_BYTES.toULong())
                        skipExact(raf, end, length.toLong())
                    }
                    TYPE_ARRAY -> repeatUlong(count) {
                        val nestedType = readU32(raf, end)
                        val nestedCount = readU64(raf, end)
                        require(nestedCount <= MAX_ARRAY_ELEMENTS.toULong())
                        skipArrayElements(
                            raf,
                            end,
                            nestedType,
                            nestedCount,
                            depth + 1
                        )
                    }
                    else -> error("invalid GGUF array element type: $elementType")
                }
            }
            else -> error("invalid GGUF metadata value type: $type")
        }
    }

    private fun skipArrayElements(
        raf: RandomAccessFile,
        end: Long,
        elementType: Long,
        count: ULong,
        depth: Int
    ) {
        require(depth < 16)
        when (elementType) {
            TYPE_UINT8,
            TYPE_INT8,
            TYPE_BOOL -> skipExact(raf, end, checkedBytes(count, 1))
            TYPE_UINT16,
            TYPE_INT16 -> skipExact(raf, end, checkedBytes(count, 2))
            TYPE_UINT32,
            TYPE_INT32,
            TYPE_FLOAT32 -> skipExact(raf, end, checkedBytes(count, 4))
            TYPE_UINT64,
            TYPE_INT64,
            TYPE_FLOAT64 -> skipExact(raf, end, checkedBytes(count, 8))
            TYPE_STRING -> repeatUlong(count) {
                val length = readU64(raf, end)
                require(length <= MAX_SKIPPED_STRING_BYTES.toULong())
                skipExact(raf, end, length.toLong())
            }
            TYPE_ARRAY -> repeatUlong(count) {
                val nestedType = readU32(raf, end)
                val nestedCount = readU64(raf, end)
                require(nestedCount <= MAX_ARRAY_ELEMENTS.toULong())
                skipArrayElements(
                    raf,
                    end,
                    nestedType,
                    nestedCount,
                    depth + 1
                )
            }
            else -> error("invalid nested GGUF array element type: $elementType")
        }
    }

    private fun checkedCount(
        raw: ULong,
        maximum: Int,
        label: String
    ): Int {
        require(raw <= maximum.toULong()) {
            "$label exceeds mobile tokenizer limit"
        }
        return raw.toInt()
    }

    private fun checkedBytes(count: ULong, width: Int): Long {
        require(count <= Long.MAX_VALUE.toULong() / width.toULong())
        return count.toLong() * width.toLong()
    }

    private fun readString(
        raf: RandomAccessFile,
        end: Long,
        label: String,
        maxBytes: Int
    ): String {
        val length = readU64(raf, end)
        require(length <= maxBytes.toULong()) {
            "$label exceeds tokenizer reader limit"
        }
        val bytes = readBytes(raf, end, length.toInt())
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun readBytes(
        raf: RandomAccessFile,
        end: Long,
        length: Int
    ): ByteArray {
        require(length >= 0)
        val target = Math.addExact(raf.filePointer, length.toLong())
        require(target <= end) { "preserved tokenizer metadata is truncated" }
        return ByteArray(length).also(raf::readFully)
    }

    private fun skipExact(
        raf: RandomAccessFile,
        end: Long,
        length: Long
    ) {
        require(length >= 0L)
        val target = Math.addExact(raf.filePointer, length)
        require(target <= end) { "preserved tokenizer metadata is truncated" }
        raf.seek(target)
    }

    private fun readU8(raf: RandomAccessFile, end: Long): Int {
        require(raf.filePointer < end) {
            "preserved tokenizer metadata is truncated"
        }
        val value = raf.read()
        if (value < 0) throw EOFException("preserved tokenizer metadata is truncated")
        return value
    }

    private fun readU16(raf: RandomAccessFile, end: Long): Int {
        val b0 = readU8(raf, end)
        val b1 = readU8(raf, end)
        return b0 or (b1 shl 8)
    }

    private fun readU32(raf: RandomAccessFile, end: Long): Long {
        var value = 0L
        repeat(4) { index ->
            value = value or
                ((readU8(raf, end).toLong() and 0xffL) shl (8 * index))
        }
        return value
    }

    private fun readU64(raf: RandomAccessFile, end: Long): ULong {
        var value = 0UL
        repeat(8) { index ->
            value = value or
                ((readU8(raf, end).toULong() and 0xffUL) shl (8 * index))
        }
        return value
    }

    private inline fun repeatUlong(count: ULong, action: () -> Unit) {
        var index = 0UL
        while (index < count) {
            action()
            index += 1UL
        }
    }

    private companion object {
        const val TYPE_UINT8 = 0L
        const val TYPE_INT8 = 1L
        const val TYPE_UINT16 = 2L
        const val TYPE_INT16 = 3L
        const val TYPE_UINT32 = 4L
        const val TYPE_INT32 = 5L
        const val TYPE_FLOAT32 = 6L
        const val TYPE_BOOL = 7L
        const val TYPE_STRING = 8L
        const val TYPE_ARRAY = 9L
        const val TYPE_UINT64 = 10L
        const val TYPE_INT64 = 11L
        const val TYPE_FLOAT64 = 12L

        const val MAX_ARRAY_ELEMENTS = 64_000_000
        const val MAX_SKIPPED_STRING_BYTES = 1_073_741_824L
    }
}

object AmiTokenTypes {
    const val NORMAL = 1
    const val UNKNOWN = 2
    const val CONTROL = 3
    const val USER_DEFINED = 4
    const val UNUSED = 5
    const val BYTE = 6
}

data class AmiDetokenizeConfig(
    val skipControlTokens: Boolean = true,
    val skipBosToken: Boolean = true,
    val skipEosToken: Boolean = true
)

/**
 * Token-id to UTF-8 conversion for the two tokenizer representations most commonly preserved by
 * GGUF mobile models.
 *
 * This does not guess an encoder. Unknown tokenizer models fail closed.
 */
object AmiDetokenizer {
    fun decode(
        lexicon: AmiTokenizerLexicon,
        tokenIds: IntArray,
        config: AmiDetokenizeConfig = AmiDetokenizeConfig()
    ): String {
        require(tokenIds.all { it in 0 until lexicon.vocabularySize })
        val model = lexicon.model?.lowercase()
            ?: error("AMI tokenizer model metadata is missing")
        return when {
            model == "llama" || model == "spm" || model == "sentencepiece" ->
                decodeSentencePiece(lexicon, tokenIds, config)
            model == "gpt2" || model == "bpe" ->
                decodeGpt2Bytes(lexicon, tokenIds, config)
            else -> error("unsupported AMI detokenizer model: $model")
        }
    }

    private fun decodeSentencePiece(
        lexicon: AmiTokenizerLexicon,
        tokenIds: IntArray,
        config: AmiDetokenizeConfig
    ): String {
        val bytes = ArrayList<Byte>()
        val text = StringBuilder()

        fun flushBytes() {
            if (bytes.isNotEmpty()) {
                text.append(bytes.toByteArray().toString(StandardCharsets.UTF_8))
                bytes.clear()
            }
        }

        tokenIds.forEach { tokenId ->
            if (shouldSkip(lexicon, tokenId, config)) return@forEach
            val piece = lexicon.piece(tokenId)
            val byte = parseByteFallback(piece)
            if (byte != null) {
                bytes += byte
            } else {
                flushBytes()
                text.append(piece.replace("▁", " "))
            }
        }
        flushBytes()
        return text.toString().removePrefix(" ")
    }

    private fun decodeGpt2Bytes(
        lexicon: AmiTokenizerLexicon,
        tokenIds: IntArray,
        config: AmiDetokenizeConfig
    ): String {
        val output = ArrayList<Byte>()
        tokenIds.forEach { tokenId ->
            if (shouldSkip(lexicon, tokenId, config)) return@forEach
            val piece = lexicon.piece(tokenId)
            val directByte = parseByteFallback(piece)
            if (directByte != null) {
                output += directByte
                return@forEach
            }
            val codePoints = piece.codePoints().toArray()
            codePoints.forEach { codePoint ->
                val byte = GPT2_CODE_POINT_TO_BYTE[codePoint]
                    ?: error(
                        "GPT-2 token contains unmapped byte-unicode code point U+" +
                            codePoint.toString(16)
                    )
                output += byte.toByte()
            }
        }
        return output.toByteArray().toString(StandardCharsets.UTF_8)
    }

    private fun shouldSkip(
        lexicon: AmiTokenizerLexicon,
        tokenId: Int,
        config: AmiDetokenizeConfig
    ): Boolean {
        if (config.skipBosToken && tokenId == lexicon.special.bosTokenId) return true
        if (config.skipEosToken && tokenId == lexicon.special.eosTokenId) return true
        if (
            config.skipControlTokens &&
            lexicon.tokenType(tokenId) == AmiTokenTypes.CONTROL
        ) {
            return true
        }
        return false
    }

    private fun parseByteFallback(piece: String): Byte? {
        if (
            piece.length == 6 &&
            piece.startsWith("<0x") &&
            piece.endsWith(">")
        ) {
            val hex = piece.substring(3, 5)
            return hex.toIntOrNull(16)?.toByte()
        }
        return null
    }

    private val GPT2_CODE_POINT_TO_BYTE: Map<Int, Int> = buildMap {
        val direct = ArrayList<Int>()
        for (value in 33..126) direct += value
        for (value in 161..172) direct += value
        for (value in 174..255) direct += value

        val used = direct.toHashSet()
        var extra = 0
        for (byte in 0..255) {
            if (byte in used) {
                put(byte, byte)
            } else {
                put(256 + extra, byte)
                extra += 1
            }
        }
    }
}
