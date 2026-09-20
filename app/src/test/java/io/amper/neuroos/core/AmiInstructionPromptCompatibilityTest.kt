package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmiInstructionPromptCompatibilityTest {
    @Test
    fun chatMlUsesTrustedControlTokenIdsAndAssistantGenerationCue() {
        val lexicon = byteFallbackLexicon(
            specialPieces = listOf("<|im_start|>", "<|im_end|>")
        )
        val imStart = lexicon.tokens.indexOf("<|im_start|>")
        val imEnd = lexicon.tokens.indexOf("<|im_end|>")
        val metadata = AmiGgufMetadataSnapshot(
            mapOf(
                "tokenizer.chat_template" to AmiGgufMetadataScalar.Text(
                    "{% for message in messages %}<|im_start|>{{ message['role'] }}\n" +
                        "{{ message['content'] }}<|im_end|>\n{% endfor %}" +
                        "{% if add_generation_prompt %}<|im_start|>assistant\n{% endif %}"
                )
            )
        )

        val prepared = AmiInstructionPromptCompatibility.prepare(
            metadata = metadata,
            lexicon = lexicon,
            amperPrompt = "<CURRENT_USER_REQUEST_FINAL>\nBạn là ai?\n</CURRENT_USER_REQUEST_FINAL>"
        )

        assertEquals(AmiInstructionPromptMode.CHATML, prepared.mode)
        assertEquals(3, prepared.tokenIds.count { it == imStart })
        assertEquals(2, prepared.tokenIds.count { it == imEnd })
        assertTrue(imEnd in prepared.stopTokenIds)
        assertTrue(lexicon.special.eosTokenId in prepared.stopTokenIds)
        assertEquals(imStart, prepared.tokenIds.last { it == imStart })
    }

    @Test
    fun llama3TemplateUsesHeaderAndEotControlIds() {
        val lexicon = byteFallbackLexicon(
            specialPieces = listOf(
                "<|begin_of_text|>",
                "<|start_header_id|>",
                "<|end_header_id|>",
                "<|eot_id|>"
            ),
            addBos = false
        )
        val begin = lexicon.tokens.indexOf("<|begin_of_text|>")
        val start = lexicon.tokens.indexOf("<|start_header_id|>")
        val end = lexicon.tokens.indexOf("<|end_header_id|>")
        val eot = lexicon.tokens.indexOf("<|eot_id|>")
        val metadata = AmiGgufMetadataSnapshot(
            mapOf(
                "tokenizer.chat_template" to AmiGgufMetadataScalar.Text(
                    "<|begin_of_text|><|start_header_id|>system<|end_header_id|>" +
                        "<|eot_id|><|start_header_id|>assistant<|end_header_id|>"
                )
            )
        )

        val prepared = AmiInstructionPromptCompatibility.prepare(
            metadata = metadata,
            lexicon = lexicon,
            amperPrompt = "question"
        )

        assertEquals(AmiInstructionPromptMode.LLAMA3_HEADER, prepared.mode)
        assertEquals(begin, prepared.tokenIds.first())
        assertEquals(3, prepared.tokenIds.count { it == start })
        assertEquals(3, prepared.tokenIds.count { it == end })
        assertEquals(2, prepared.tokenIds.count { it == eot })
        assertTrue(eot in prepared.stopTokenIds)
    }

    @Test
    fun plainFallbackStillAddsAmperIdentityAndAssistantCue() {
        val lexicon = byteFallbackLexicon(
            specialPieces = emptyList(),
            addBos = true
        )
        val prepared = AmiInstructionPromptCompatibility.prepare(
            metadata = AmiGgufMetadataSnapshot(emptyMap()),
            lexicon = lexicon,
            amperPrompt = "hello"
        )

        assertEquals(
            AmiInstructionPromptMode.PLAIN_ASSISTANT_CUE,
            prepared.mode
        )
        assertEquals(lexicon.special.bosTokenId, prepared.tokenIds.first())
        assertTrue(prepared.tokenIds.size > 32)
    }

    private fun byteFallbackLexicon(
        specialPieces: List<String>,
        addBos: Boolean = false
    ): AmiTokenizerLexicon {
        val base = mutableListOf("<s>", "</s>")
        base += specialPieces
        val byteOffset = base.size
        repeat(256) { value ->
            base += "<0x" + value.toString(16).uppercase().padStart(2, '0') + ">"
        }

        val tokenTypes = IntArray(base.size) { index ->
            when {
                index < byteOffset -> AmiTokenTypes.CONTROL
                else -> AmiTokenTypes.BYTE
            }
        }
        val scores = FloatArray(base.size) { -100f }

        return AmiTokenizerLexicon(
            model = "llama",
            preTokenizer = "default",
            tokens = base,
            scores = scores,
            tokenTypes = tokenTypes,
            merges = emptyList(),
            special = AmiTokenizerSpecialTokens(
                bosTokenId = 0,
                eosTokenId = 1,
                unknownTokenId = null,
                paddingTokenId = null,
                addBosToken = addBos,
                addEosToken = false
            )
        )
    }
}
