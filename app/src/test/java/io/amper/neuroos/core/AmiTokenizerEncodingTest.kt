package io.amper.neuroos.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AmiTokenizerEncodingTest {
    @Test
    fun gpt2BpeEncoderUsesPreservedMergeRanksAndRoundTrips() {
        val lexicon = AmiTokenizerLexicon(
            model = "gpt2",
            preTokenizer = "gpt2",
            tokens = listOf(
                "H",
                "i",
                "Hi",
                "Ġ",
                "w",
                "o",
                "r",
                "l",
                "d",
                "wo",
                "wor",
                "worl",
                "world",
                "Ġworld"
            ),
            scores = null,
            tokenTypes = null,
            merges = listOf(
                "H i",
                "w o",
                "wo r",
                "wor l",
                "worl d",
                "Ġ world"
            ),
            special = AmiTokenizerSpecialTokens(
                bosTokenId = null,
                eosTokenId = null,
                unknownTokenId = null,
                paddingTokenId = null,
                addBosToken = false,
                addEosToken = false
            )
        )

        val ids = AmiTokenizerEncoder.encode(
            lexicon = lexicon,
            text = "Hi world"
        )

        assertArrayEquals(
            intArrayOf(2, 13),
            ids
        )
        assertEquals(
            "Hi world",
            AmiDetokenizer.decode(lexicon, ids)
        )
    }

    @Test
    fun sentencePieceUnigramUsesScoresAndByteFallbackThenRoundTrips() {
        val lexicon = AmiTokenizerLexicon(
            model = "llama",
            preTokenizer = "default",
            tokens = listOf(
                "<s>",
                "</s>",
                "▁hello",
                "▁world",
                "<0x21>",
                "▁",
                "hello",
                "world"
            ),
            scores = floatArrayOf(
                -100f,
                -100f,
                5f,
                4f,
                -20f,
                -5f,
                1f,
                1f
            ),
            tokenTypes = intArrayOf(
                AmiTokenTypes.CONTROL,
                AmiTokenTypes.CONTROL,
                AmiTokenTypes.NORMAL,
                AmiTokenTypes.NORMAL,
                AmiTokenTypes.BYTE,
                AmiTokenTypes.NORMAL,
                AmiTokenTypes.NORMAL,
                AmiTokenTypes.NORMAL
            ),
            merges = emptyList(),
            special = AmiTokenizerSpecialTokens(
                bosTokenId = 0,
                eosTokenId = 1,
                unknownTokenId = null,
                paddingTokenId = null,
                addBosToken = true,
                addEosToken = false
            )
        )

        val ids = AmiTokenizerEncoder.encode(
            lexicon = lexicon,
            text = "hello world!"
        )

        assertArrayEquals(
            intArrayOf(0, 2, 3, 4),
            ids
        )
        assertEquals(
            "hello world!",
            AmiDetokenizer.decode(lexicon, ids)
        )
    }

    @Test
    fun explicitBosEosPolicyOverridesStoredDefaults() {
        val lexicon = AmiTokenizerLexicon(
            model = "llama",
            preTokenizer = "default",
            tokens = listOf(
                "<s>",
                "</s>",
                "▁ok"
            ),
            scores = floatArrayOf(-100f, -100f, 2f),
            tokenTypes = intArrayOf(
                AmiTokenTypes.CONTROL,
                AmiTokenTypes.CONTROL,
                AmiTokenTypes.NORMAL
            ),
            merges = emptyList(),
            special = AmiTokenizerSpecialTokens(
                bosTokenId = 0,
                eosTokenId = 1,
                unknownTokenId = null,
                paddingTokenId = null,
                addBosToken = false,
                addEosToken = false
            )
        )

        val ids = AmiTokenizerEncoder.encode(
            lexicon = lexicon,
            text = "ok",
            config = AmiTokenizerEncodeConfig(
                addBosToken = true,
                addEosToken = true
            )
        )

        assertArrayEquals(intArrayOf(0, 2, 1), ids)
    }

    @Test(expected = IllegalArgumentException::class)
    fun gpt2EncoderFailsClosedWithoutMergeTable() {
        val lexicon = AmiTokenizerLexicon(
            model = "gpt2",
            preTokenizer = "gpt2",
            tokens = listOf("a"),
            scores = null,
            tokenTypes = null,
            merges = emptyList(),
            special = AmiTokenizerSpecialTokens(
                bosTokenId = null,
                eosTokenId = null,
                unknownTokenId = null,
                paddingTokenId = null,
                addBosToken = null,
                addEosToken = null
            )
        )

        AmiTokenizerEncoder.encode(lexicon, "a")
    }
}
