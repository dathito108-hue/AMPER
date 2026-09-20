package io.amper.neuroos.core

internal object AmiTestFixtures {
    fun compilerReadyGguf(
        tensorBytes: ByteArray = byteArrayOf(1, 2, 3, 4),
        architecture: String = "llama",
        tokens: List<String> = listOf("<s>", "hello", "world")
    ): ByteArray {
        require(tensorBytes.size == 4)
        require(tokens.isNotEmpty())
        val out = GgufTestFixtures.header(
            version = 3L,
            tensorCount = 1UL,
            metadataCount = 3UL
        )

        GgufTestFixtures.writeString(out, "general.alignment")
        GgufTestFixtures.writeU32(out, 4L)
        GgufTestFixtures.writeU32(out, 32L)

        GgufTestFixtures.writeString(out, "general.architecture")
        GgufTestFixtures.writeU32(out, 8L)
        GgufTestFixtures.writeString(out, architecture)

        GgufTestFixtures.writeString(out, "tokenizer.ggml.tokens")
        GgufTestFixtures.writeU32(out, 9L)
        GgufTestFixtures.writeU32(out, 8L)
        GgufTestFixtures.writeU64(out, tokens.size.toULong())
        tokens.forEach { token ->
            GgufTestFixtures.writeString(out, token)
        }

        GgufTestFixtures.writeString(out, "token_embd.weight")
        GgufTestFixtures.writeU32(out, 1L)
        GgufTestFixtures.writeU64(out, 1UL)
        GgufTestFixtures.writeU32(out, 0L)
        GgufTestFixtures.writeU64(out, 0UL)

        GgufTestFixtures.padTo(out, 32)
        out.write(tensorBytes)
        return out.toByteArray()
    }
}
