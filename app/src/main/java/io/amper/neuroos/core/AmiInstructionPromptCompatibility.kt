package io.amper.neuroos.core

enum class AmiInstructionPromptMode {
    LLAMA3_HEADER,
    CHATML,
    GEMMA_TURN,
    PHI_ROLE,
    MISTRAL_INST,
    PLAIN_ASSISTANT_CUE
}

data class AmiPreparedInstructionPrompt(
    val tokenIds: IntArray,
    val stopTokenIds: Set<Int>,
    val mode: AmiInstructionPromptMode
) {
    init {
        require(tokenIds.isNotEmpty())
        require(stopTokenIds.all { it >= 0 })
    }
}

/**
 * Converts AMPER's trusted internal prompt into the instruction/chat framing expected by the
 * imported foundation.
 *
 * GGUF chat-template metadata is preserved in AMI, but Phase613 previously ignored it and fed the
 * internal prompt as raw completion text. Instruction-tuned weights can then continue the prompt
 * instead of answering the user's request.
 *
 * This formatter never concatenates user-controlled text with trusted control token spellings.
 * Known control boundaries are inserted by exact token id; user/system text is encoded with special
 * insertion disabled.
 */
object AmiInstructionPromptCompatibility {
    const val SYSTEM_IDENTITY =
        "You are AMPER, the application's single local AI intelligence core. " +
            "Your foundation weights execute through AMI and the AMNE native runtime. " +
            "Answer the current user request directly, accurately, and concisely. " +
            "Do not claim to be another assistant, model service, or runtime."

    fun detect(
        metadata: AmiGgufMetadataSnapshot,
        lexicon: AmiTokenizerLexicon
    ): AmiInstructionPromptMode {
        val template = metadata.text("tokenizer.chat_template").orEmpty()
        val pieces = lexicon.tokens.toHashSet()

        return when {
            (
                template.contains("<|start_header_id|>") ||
                    (
                        "<|start_header_id|>" in pieces &&
                            "<|end_header_id|>" in pieces &&
                            "<|eot_id|>" in pieces
                    )
                ) -> AmiInstructionPromptMode.LLAMA3_HEADER

            (
                template.contains("<|im_start|>") ||
                    ("<|im_start|>" in pieces && "<|im_end|>" in pieces)
                ) -> AmiInstructionPromptMode.CHATML

            (
                template.contains("<start_of_turn>") ||
                    ("<start_of_turn>" in pieces && "<end_of_turn>" in pieces)
                ) -> AmiInstructionPromptMode.GEMMA_TURN

            (
                template.contains("<|assistant|>") &&
                    template.contains("<|user|>")
                ) || (
                "<|assistant|>" in pieces &&
                    "<|user|>" in pieces &&
                    "<|end|>" in pieces
                ) -> AmiInstructionPromptMode.PHI_ROLE

            template.contains("[INST]") ||
                ("[INST]" in pieces && "[/INST]" in pieces) ->
                AmiInstructionPromptMode.MISTRAL_INST

            else -> AmiInstructionPromptMode.PLAIN_ASSISTANT_CUE
        }
    }

    fun prepare(
        metadata: AmiGgufMetadataSnapshot,
        lexicon: AmiTokenizerLexicon,
        amperPrompt: String
    ): AmiPreparedInstructionPrompt {
        require(amperPrompt.isNotBlank())
        val mode = detect(metadata, lexicon)
        val ids = ArrayList<Int>()
        val stop = linkedSetOf<Int>()

        fun tokenId(piece: String): Int? =
            lexicon.tokens.indexOf(piece).takeIf { it >= 0 }

        fun appendExact(piece: String) {
            ids += requireNotNull(tokenId(piece)) {
                "AMI instruction template requires missing tokenizer control token: $piece"
            }
        }

        fun appendText(text: String) {
            if (text.isEmpty()) return
            AmiTokenizerEncoder.encode(
                lexicon = lexicon,
                text = text,
                config = AmiTokenizerEncodeConfig(
                    addBosToken = false,
                    addEosToken = false
                )
            ).forEach(ids::add)
        }

        fun appendBosIfConfigured() {
            if (lexicon.special.addBosToken == true) {
                ids += requireNotNull(lexicon.special.bosTokenId) {
                    "AMI tokenizer requests BOS but BOS token id is missing"
                }
            }
        }

        lexicon.special.eosTokenId?.let(stop::add)

        when (mode) {
            AmiInstructionPromptMode.LLAMA3_HEADER -> {
                tokenId("<|begin_of_text|>")
                    ?.let(ids::add)
                    ?: appendBosIfConfigured()

                appendExact("<|start_header_id|>")
                appendText("system")
                appendExact("<|end_header_id|>")
                appendText("\n\n$SYSTEM_IDENTITY")
                appendExact("<|eot_id|>")

                appendExact("<|start_header_id|>")
                appendText("user")
                appendExact("<|end_header_id|>")
                appendText("\n\n$amperPrompt")
                appendExact("<|eot_id|>")

                appendExact("<|start_header_id|>")
                appendText("assistant")
                appendExact("<|end_header_id|>")
                appendText("\n\n")

                tokenId("<|eot_id|>")?.let(stop::add)
            }

            AmiInstructionPromptMode.CHATML -> {
                appendBosIfConfigured()

                appendExact("<|im_start|>")
                appendText("system\n$SYSTEM_IDENTITY")
                appendExact("<|im_end|>")
                appendText("\n")

                appendExact("<|im_start|>")
                appendText("user\n$amperPrompt")
                appendExact("<|im_end|>")
                appendText("\n")

                appendExact("<|im_start|>")
                appendText("assistant\n")

                tokenId("<|im_end|>")?.let(stop::add)
            }

            AmiInstructionPromptMode.GEMMA_TURN -> {
                appendBosIfConfigured()

                appendExact("<start_of_turn>")
                appendText("user\n$SYSTEM_IDENTITY\n\n$amperPrompt")
                appendExact("<end_of_turn>")
                appendText("\n")

                appendExact("<start_of_turn>")
                appendText("model\n")

                tokenId("<end_of_turn>")?.let(stop::add)
            }

            AmiInstructionPromptMode.PHI_ROLE -> {
                appendBosIfConfigured()

                tokenId("<|system|>")?.let(ids::add)
                    ?: appendText("System:\n")
                appendText(SYSTEM_IDENTITY + "\n")
                tokenId("<|end|>")?.let(ids::add)

                appendExact("<|user|>")
                appendText("\n$amperPrompt\n")
                tokenId("<|end|>")?.let(ids::add)

                appendExact("<|assistant|>")
                appendText("\n")

                tokenId("<|end|>")?.let(stop::add)
            }

            AmiInstructionPromptMode.MISTRAL_INST -> {
                appendBosIfConfigured()

                tokenId("[INST]")?.let(ids::add)
                    ?: appendText("[INST]")
                appendText(
                    " <<SYS>>\n$SYSTEM_IDENTITY\n<</SYS>>\n\n" +
                        amperPrompt +
                        " "
                )
                tokenId("[/INST]")?.let(ids::add)
                    ?: appendText("[/INST]")
            }

            AmiInstructionPromptMode.PLAIN_ASSISTANT_CUE -> {
                appendBosIfConfigured()
                appendText(
                    "System:\n$SYSTEM_IDENTITY\n\n" +
                        "User:\n$amperPrompt\n\n" +
                        "Assistant:\n"
                )
            }
        }

        require(ids.isNotEmpty()) {
            "AMI instruction prompt produced no tokens"
        }

        return AmiPreparedInstructionPrompt(
            tokenIds = ids.toIntArray(),
            stopTokenIds = stop,
            mode = mode
        )
    }
}
