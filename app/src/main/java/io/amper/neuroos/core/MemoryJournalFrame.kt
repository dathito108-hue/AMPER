package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Versioned integrity frame around one plaintext MemoryJournalCodec mutation.
 * SHA-256 here detects accidental/storage corruption; encrypted journals still rely
 * on AES-GCM for cryptographic authenticity.
 */
internal object MemoryJournalFrameCodec {
    private const val VERSION = "F1"
    private val HASH = Regex("[0-9a-f]{64}")

    fun encode(payload: String): String {
        require(payload.isNotEmpty())
        require('\n' !in payload && '\r' !in payload)
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        return listOf(
            VERSION,
            bytes.size.toString(),
            sha256(bytes),
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        ).joinToString("|")
    }

    fun decode(frame: String): Result<String> = runCatching {
        val parts = frame.split('|')
        require(parts.size == 4 && parts[0] == VERSION) { "unsupported memory journal frame" }
        val expectedLength = parts[1].toInt()
        require(expectedLength >= 0) { "invalid memory journal frame length" }
        val expectedHash = parts[2]
        require(expectedHash.matches(HASH)) { "invalid memory journal frame digest" }
        val bytes = Base64.getUrlDecoder().decode(parts[3])
        require(bytes.size == expectedLength) { "memory journal frame length mismatch" }
        require(sha256(bytes) == expectedHash) { "memory journal frame digest mismatch" }
        String(bytes, StandardCharsets.UTF_8)
    }

    fun unwrapFramedOrLegacy(line: String): Result<String> = when {
        line.startsWith("$VERSION|") -> decode(line)
        line.startsWith("R|") || line.startsWith("D|") -> Result.success(line)
        else -> Result.failure(IllegalArgumentException("unrecognized memory journal payload framing"))
    }

    fun isFramed(line: String): Boolean = line.startsWith("$VERSION|")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
