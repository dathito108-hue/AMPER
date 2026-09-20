package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmiTokenizerLexiconTest {
    @Test
    fun sentencePieceDetokenizerRestoresWordBoundaryAndByteFallback() {
        val lexicon = AmiTokenizerLexicon(
            model = "llama",
            preTokenizer = null,
            tokens = listOf(
                "<s>",
                "▁Hello",
                "▁world",
                "<0x21>",
                "</s>"
            ),
            scores = null,
            tokenTypes = intArrayOf(
                AmiTokenTypes.CONTROL,
                AmiTokenTypes.NORMAL,
                AmiTokenTypes.NORMAL,
                AmiTokenTypes.BYTE,
                AmiTokenTypes.CONTROL
            ),
            merges = emptyList(),
            special = AmiTokenizerSpecialTokens(
                bosTokenId = 0,
                eosTokenId = 4,
                unknownTokenId = null,
                paddingTokenId = null,
                addBosToken = true,
                addEosToken = false
            )
        )

        val decoded = AmiDetokenizer.decode(
            lexicon,
            intArrayOf(0, 1, 2, 3, 4)
        )

        assertEquals("Hello world!", decoded)
    }

    @Test
    fun gpt2ByteUnicodeDetokenizerRestoresUtf8Bytes() {
        val lexicon = AmiTokenizerLexicon(
            model = "gpt2",
            preTokenizer = "gpt2",
            tokens = listOf(
                "H",
                "i",
                "Ġ",
                "â",
                "Ĥ",
                "¬"
            ),
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

        // Ġ maps to byte 0x20. âĤ¬ is the GPT-2 byte-unicode form of UTF-8 E2 82 AC (€).
        val decoded = AmiDetokenizer.decode(
            lexicon,
            intArrayOf(0, 1, 2, 3, 4, 5)
        )

        assertEquals("Hi €", decoded)
    }

    @Test
    fun controlTokensCanBePreservedWhenExplicitlyRequested() {
        val lexicon = AmiTokenizerLexicon(
            model = "llama",
            preTokenizer = null,
            tokens = listOf("<CTRL>", "▁ok"),
            scores = null,
            tokenTypes = intArrayOf(
                AmiTokenTypes.CONTROL,
                AmiTokenTypes.NORMAL
            ),
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

        assertEquals(
            "<CTRL> ok",
            AmiDetokenizer.decode(
                lexicon,
                intArrayOf(0, 1),
                AmiDetokenizeConfig(skipControlTokens = false)
            )
        )
    }

    @Test(expected = IllegalStateException::class)
    fun unknownTokenizerModelFailsClosed() {
        val lexicon = AmiTokenizerLexicon(
            model = "unknown-family",
            preTokenizer = null,
            tokens = listOf("x"),
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

        AmiDetokenizer.decode(lexicon, intArrayOf(0))
    }

    @Test
    fun lexiconRejectsOutOfVocabularySpecialId() {
        val result = runCatching {
            AmiTokenizerLexicon(
                model = "llama",
                preTokenizer = null,
                tokens = listOf("x"),
                scores = null,
                tokenTypes = null,
                merges = emptyList(),
                special = AmiTokenizerSpecialTokens(
                    bosTokenId = 5,
                    eosTokenId = null,
                    unknownTokenId = null,
                    paddingTokenId = null,
                    addBosToken = null,
                    addEosToken = null
                )
            )
        }

        assertTrue(result.isFailure)
    }
}
