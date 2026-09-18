package io.amper.neuroos.core

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64

/** Resolves named E1 keys and optionally retires aliases after a verified rewrap. */
interface MemoryLineCipherProvider {
    fun cipher(keyId: String): MemoryLineCipher
    fun retire(keyId: String): Boolean = false
}

class AndroidKeystoreMemoryLineCipherProvider : MemoryLineCipherProvider {
    override fun cipher(keyId: String): MemoryLineCipher = AndroidKeystoreMemoryLineCipher(keyId)

    override fun retire(keyId: String): Boolean = runCatching {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(keyId)) keyStore.deleteEntry(keyId)
        true
    }.getOrDefault(false)

    companion object { private const val KEYSTORE = "AndroidKeyStore" }
}

/**
 * Encrypts with [active] while accepting E1 envelopes from the active or fallback key ids.
 * Envelope key id routing happens before authentication; the selected cipher still performs
 * its own AES-GCM authentication and exact key-id check.
 */
class MemoryLineCipherRing(
    private val active: MemoryLineCipher,
    fallbacks: Collection<MemoryLineCipher> = emptyList()
) : MemoryLineCipher {
    private val readers: Map<String, MemoryLineCipher>

    init {
        val all = listOf(active) + fallbacks
        require(all.map { it.keyId }.distinct().size == all.size) { "duplicate memory cipher key id" }
        readers = all.associateBy { it.keyId }
    }

    override val keyId: String get() = active.keyId
    val acceptedKeyIds: Set<String> get() = readers.keys

    override fun encrypt(plaintext: String): String = active.encrypt(plaintext)

    override fun decrypt(envelope: String): String {
        val keyId = envelopeKeyId(envelope)
        val selected = readers[keyId] ?: error("memory key $keyId is not accepted by active key ring")
        return selected.decrypt(envelope)
    }

    companion object {
        internal fun envelopeKeyId(envelope: String): String {
            val parts = envelope.split('|', limit = 3)
            require(parts.size == 3 && parts[0] == "E1" && parts[1].isNotBlank()) {
                "unsupported memory envelope"
            }
            return parts[1]
        }
    }
}

internal data class MemoryKeyManifest(
    val activeKeyId: String,
    val fallbackKeyIds: Set<String> = emptySet(),
    val retirePendingKeyIds: Set<String> = emptySet()
) {
    init {
        require(activeKeyId.isNotBlank())
        require(fallbackKeyIds.none { it.isBlank() })
        require(retirePendingKeyIds.none { it.isBlank() })
        require(activeKeyId !in fallbackKeyIds)
        require(activeKeyId !in retirePendingKeyIds)
        require(fallbackKeyIds.intersect(retirePendingKeyIds).isEmpty())
    }

    val rotationInProgress: Boolean get() = fallbackKeyIds.isNotEmpty()
}

/** K1 stores non-secret key aliases needed to recover an interrupted rewrap. */
internal object MemoryKeyManifestCodec {
    private const val VERSION = "K1"
    private val HASH = Regex("[0-9a-f]{64}")

    fun encode(manifest: MemoryKeyManifest): String {
        val canonical = listOf(
            VERSION,
            enc(manifest.activeKeyId),
            encSet(manifest.fallbackKeyIds),
            encSet(manifest.retirePendingKeyIds)
        ).joinToString("|")
        return "$canonical|${sha256(canonical)}"
    }

    fun decode(value: String): Result<MemoryKeyManifest> = runCatching {
        val parts = value.split('|')
        require(parts.size == 5 && parts[0] == VERSION) { "unsupported memory key manifest" }
        val checksum = parts[4]
        require(checksum.matches(HASH)) { "invalid memory key manifest checksum" }
        val canonical = parts.take(4).joinToString("|")
        require(sha256(canonical) == checksum) { "memory key manifest checksum mismatch" }
        MemoryKeyManifest(
            activeKeyId = dec(parts[1]),
            fallbackKeyIds = decSet(parts[2]),
            retirePendingKeyIds = decSet(parts[3])
        )
    }

    private fun encSet(values: Set<String>): String =
        if (values.isEmpty()) "~" else values.sorted().joinToString(",") { enc(it) }

    private fun decSet(value: String): Set<String> =
        if (value == "~") emptySet() else value.split(',').map(::dec).toSet()

    private fun enc(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String = String(
        Base64.getUrlDecoder().decode(value),
        StandardCharsets.UTF_8
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal class MemoryKeyManifestStore(
    private val file: File,
    private val defaultKeyId: String
) {
    init {
        require(defaultKeyId.isNotBlank())
        SovereignPathIdentity.requireManagedFile(file)
        file.parentFile?.let { SovereignPathIdentity.requireDirectory(it) }
    }

    fun loadOrBootstrap(): MemoryKeyManifest {
        SovereignPathIdentity.requireManagedNamespace(file)
        DurableJournalIo.recoverInterruptedReplace(file)
        SovereignPathIdentity.requireManagedNamespace(file)
        if (!file.exists()) {
            val initial = MemoryKeyManifest(activeKeyId = defaultKeyId)
            store(initial)
            return initial
        }
        val lines = DescriptorBoundFileIo.readCompleteUtf8LinesRecoveringTornTail(file)
        SovereignPathIdentity.requireManagedNamespace(file)
        require(lines.size == 1 && lines.single().isNotBlank()) { "invalid memory key manifest file" }
        return MemoryKeyManifestCodec.decode(lines.single()).getOrThrow()
    }

    fun load(): MemoryKeyManifest = loadOrBootstrap()

    fun store(manifest: MemoryKeyManifest) {
        SovereignPathIdentity.requireManagedNamespace(file)
        DurableJournalIo.rewriteUtf8LinesAtomically(
            file,
            ".updating",
            listOf(MemoryKeyManifestCodec.encode(manifest))
        )
        SovereignPathIdentity.requireManagedNamespace(file)
    }
}

internal class MemoryKeyRotationContext(
    private val manifestStore: MemoryKeyManifestStore,
    val provider: MemoryLineCipherProvider
) {
    fun loadManifest(): MemoryKeyManifest = manifestStore.loadOrBootstrap()
    fun storeManifest(manifest: MemoryKeyManifest) = manifestStore.store(manifest)

    fun resolve(manifest: MemoryKeyManifest): MemoryLineCipherRing = MemoryLineCipherRing(
        active = provider.cipher(manifest.activeKeyId),
        fallbacks = manifest.fallbackKeyIds.sorted().map(provider::cipher)
    )
}
