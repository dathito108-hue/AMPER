package io.amper.neuroos.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface MemoryLineCipher {
    val keyId: String
    fun encrypt(plaintext: String): String
    fun decrypt(envelope: String): String
}

class AesGcmMemoryLineCipher(
    private val key: SecretKey,
    override val keyId: String,
    private val random: SecureRandom = SecureRandom()
) : MemoryLineCipher {
    init { require(keyId.isNotBlank() && '|' !in keyId) }

    override fun encrypt(plaintext: String): String {
        require(plaintext.isNotEmpty())
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return listOf(VERSION, keyId, enc(nonce), enc(ciphertext)).joinToString("|")
    }

    override fun decrypt(envelope: String): String {
        val parts = envelope.split('|')
        require(parts.size == 4 && parts[0] == VERSION) { "unsupported memory envelope" }
        require(parts[1] == keyId) { "memory key mismatch: expected $keyId, found ${parts[1]}" }
        val nonce = dec(parts[2])
        require(nonce.size == NONCE_BYTES) { "invalid AES-GCM nonce" }
        val ciphertext = dec(parts[3])
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad())
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun aad(): ByteArray = "AMPER-MEMORY|$VERSION|$keyId".toByteArray(StandardCharsets.UTF_8)
    private fun enc(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun dec(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    companion object {
        private const val VERSION = "E1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
    }
}

class AndroidKeystoreMemoryLineCipher(
    override val keyId: String = DEFAULT_KEY_ID
) : MemoryLineCipher {
    private val delegate: AesGcmMemoryLineCipher by lazy { AesGcmMemoryLineCipher(loadOrCreateKey(), keyId) }
    override fun encrypt(plaintext: String): String = delegate.encrypt(plaintext)
    override fun decrypt(envelope: String): String = delegate.decrypt(envelope)

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyId, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(keyId, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_KEY_ID = "amper-sovereign-memory-v1"
        private const val KEYSTORE = "AndroidKeyStore"
    }
}

/**
 * Encrypted journal. E1 authenticates B1/C1 frames; C1 binds sequence and predecessor;
 * F1 frames payloads; H1 anchors the latest accepted head. Phase 37 optionally adds a
 * K1-managed dual-key transition so journal and H1 can be rewrapped crash-safely.
 */
class EncryptedFileMemoryJournal private constructor(
    private val file: File,
    private var cipher: MemoryLineCipher,
    private val keyRotation: MemoryKeyRotationContext?
) : MemoryJournal {
    constructor(file: File, cipher: MemoryLineCipher) : this(file, cipher, null)

    private val lock = Any()
    private val processLock = MemoryJournalProcessLock(file)
    private var headAnchor = EncryptedMemoryJournalHeadAnchor(headFileFor(file), cipher)
    private var chainState = MemoryJournalChainState.GENESIS

    init {
        DurableJournalIo.ensureFileExistsDurably(file)
        synchronized(lock) {
            processLock.exclusive {
                migrateAndLoadChainLocked()
                chainState = verifyCurrentHeadLocked()
                resumeKeyRotationLocked()
            }
        }
    }

    override fun append(record: MemoryRecord) = synchronized(lock) {
        processLock.exclusive {
            chainState = verifyCurrentHeadLocked()
            appendEncryptedLocked(MemoryJournalCodec.encodeRecord(record))
        }
    }

    override fun tombstone(id: MemoryId) = synchronized(lock) {
        processLock.exclusive {
            chainState = verifyCurrentHeadLocked()
            appendEncryptedLocked(MemoryJournalCodec.encodeTombstone(id))
        }
    }

    override fun replay(): List<MemoryRecord> = synchronized(lock) {
        processLock.exclusive {
            val segment = readEncryptedSegmentLocked()
            chainState = MemoryJournalHeadVerifier.verifyAndReconcile(segment.entries, headAnchor, segment.base)
            liveRecords(segment.entries)
        }
    }

    override fun compact(): Int = synchronized(lock) {
        processLock.exclusive {
            val physicalLines = DurableJournalIo.readCompleteUtf8LinesRecoveringTornTail(file)
            if (physicalLines.isEmpty()) return@exclusive 0

            val segment = readEncryptedSegmentLocked(physicalLines)
            val oldHead = MemoryJournalHeadVerifier.verifyAndReconcile(segment.entries, headAnchor, segment.base)
            val live = liveRecords(segment.entries).sortedBy { it.id.value }

            var state = oldHead
            val snapshotEntries = live.map { record ->
                MemoryJournalChainCodec.encode(MemoryJournalCodec.encodeRecord(record), state).also {
                    state = MemoryJournalChainState(it.sequence, it.digest)
                }
            }
            val plaintextLines = buildList {
                add(MemoryJournalBaseCodec.encode(oldHead))
                snapshotEntries.forEach { add(it.encoded) }
            }
            if (plaintextLines.size >= physicalLines.size) return@exclusive 0

            DurableJournalIo.rewriteUtf8LinesAtomically(
                file,
                ".compacting-encrypted",
                plaintextLines.map(cipher::encrypt)
            )
            headAnchor.store(state)
            chainState = state
            physicalLines.size - plaintextLines.size
        }
    }

    /**
     * Rewraps E1 journal and H1 under [newKeyId] without changing inner B1/C1/F1 bytes.
     * Available only for journals opened through [managed]. K1 is written in transition
     * mode before either durable encrypted file changes, so restart can accept both keys.
     */
    fun rotateEncryption(newKeyId: String): Int {
        require(newKeyId.isNotBlank())
        val context = requireNotNull(keyRotation) {
            "memory key rotation requires a managed encrypted journal"
        }
        return synchronized(lock) {
            processLock.exclusive {
                resumeKeyRotationLocked()
                val current = context.loadManifest()
                require(!current.rotationInProgress) { "memory key rotation is already in progress" }
                if (current.activeKeyId == newKeyId) return@exclusive 0

                // Resolve before publishing K1 so obviously invalid providers fail early.
                context.provider.cipher(newKeyId)
                val transition = MemoryKeyManifest(
                    activeKeyId = newKeyId,
                    fallbackKeyIds = setOf(current.activeKeyId)
                )
                context.storeManifest(transition)
                installCipher(context.resolve(transition))

                val rewritten = rewrapStorageLocked()
                val retirement = MemoryKeyManifest(
                    activeKeyId = newKeyId,
                    retirePendingKeyIds = transition.fallbackKeyIds
                )
                context.storeManifest(retirement)
                installCipher(context.resolve(retirement))

                // Prove both durable files are readable with the new key alone before
                // attempting irreversible retirement of the previous alias.
                chainState = verifyCurrentHeadLocked()
                retirePendingKeysLocked(retirement)
                rewritten
            }
        }
    }

    private fun resumeKeyRotationLocked() {
        val context = keyRotation ?: return
        var manifest = context.loadManifest()

        if (manifest.rotationInProgress) {
            installCipher(context.resolve(manifest))
            chainState = verifyCurrentHeadLocked()
            rewrapStorageLocked()

            manifest = MemoryKeyManifest(
                activeKeyId = manifest.activeKeyId,
                retirePendingKeyIds = manifest.fallbackKeyIds
            )
            context.storeManifest(manifest)
            installCipher(context.resolve(manifest))
            chainState = verifyCurrentHeadLocked()
        }

        if (manifest.retirePendingKeyIds.isNotEmpty()) {
            retirePendingKeysLocked(manifest)
        }
    }

    private fun retirePendingKeysLocked(manifest: MemoryKeyManifest) {
        val context = requireNotNull(keyRotation)
        val remaining = manifest.retirePendingKeyIds.filterNot(context.provider::retire).toSet()
        if (remaining != manifest.retirePendingKeyIds) {
            context.storeManifest(manifest.copy(retirePendingKeyIds = remaining))
        }
    }

    private fun rewrapStorageLocked(): Int {
        val stored = DurableJournalIo.readCompleteUtf8LinesRecoveringTornTail(file)
        val plaintext = stored.map { envelope ->
            require(envelope.startsWith("E1|")) { "unencrypted entry found during memory key rotation" }
            cipher.decrypt(envelope)
        }
        DurableJournalIo.rewriteUtf8LinesAtomically(
            file,
            ".rekeying",
            plaintext.map(cipher::encrypt)
        )

        val anchored = headAnchor.load()
        if (anchored != null) headAnchor.store(anchored)
        return stored.size + if (anchored != null) 1 else 0
    }

    private fun installCipher(next: MemoryLineCipher) {
        cipher = next
        headAnchor = EncryptedMemoryJournalHeadAnchor(headFileFor(file), next)
    }

    private fun verifyCurrentHeadLocked(): MemoryJournalChainState {
        val segment = readEncryptedSegmentLocked()
        return MemoryJournalHeadVerifier.verifyAndReconcile(segment.entries, headAnchor, segment.base)
    }

    private fun appendEncryptedLocked(payload: String) {
        validateMutationPayload(payload)
        val entry = MemoryJournalChainCodec.encode(payload, chainState)
        DurableJournalIo.appendUtf8Line(file, cipher.encrypt(entry.encoded))
        chainState = MemoryJournalChainState(entry.sequence, entry.digest)
        headAnchor.store(chainState)
    }

    private fun readEncryptedSegmentLocked(
        lines: List<String> = DurableJournalIo.readCompleteUtf8LinesRecoveringTornTail(file)
    ): MemoryJournalSegment {
        val plaintextLines = lines.map { stored ->
            require(stored.isNotBlank()) { "blank complete encrypted memory journal line" }
            require(stored.startsWith("E1|")) { "unencrypted entry found in encrypted memory journal" }
            cipher.decrypt(stored)
        }
        val segment = MemoryJournalSegmentCodec.decodePlaintext(plaintextLines).getOrThrow()
        segment.entries.forEach { validateMutationPayload(it.payload) }
        return segment
    }

    private fun liveRecords(entries: List<MemoryJournalChainEntry>): List<MemoryRecord> {
        val live = linkedMapOf<MemoryId, MemoryRecord>()
        entries.forEach { entry ->
            val payload = entry.payload
            when {
                payload.startsWith("R|") -> {
                    val record = requireNotNull(MemoryJournalCodec.decodeRecord(payload)) {
                        "invalid complete encrypted memory record"
                    }
                    live[record.id] = record
                }
                payload.startsWith("D|") -> {
                    val recordId = requireNotNull(MemoryJournalCodec.decodeTombstone(payload)) {
                        "invalid complete encrypted memory tombstone"
                    }
                    live.remove(recordId)
                }
                else -> error("unrecognized complete decrypted memory journal payload")
            }
        }
        return live.values.toList()
    }

    private fun validateMutationPayload(payload: String) {
        when {
            payload.startsWith("R|") -> require(MemoryJournalCodec.decodeRecord(payload) != null) {
                "invalid complete memory journal record"
            }
            payload.startsWith("D|") -> require(MemoryJournalCodec.decodeTombstone(payload) != null) {
                "invalid complete memory journal tombstone"
            }
            else -> error("unrecognized complete memory journal payload")
        }
    }

    private data class MigrationEntry(
        val plaintext: String,
        val encrypted: Boolean
    )

    private fun migrateAndLoadChainLocked(): MemoryJournalChainState {
        val lines = DurableJournalIo.readCompleteUtf8LinesRecoveringTornTail(file)
        if (lines.isEmpty()) return MemoryJournalChainState.GENESIS

        val decoded = lines.map { stored ->
            require(stored.isNotBlank()) { "blank complete memory journal line" }
            val encrypted = stored.startsWith("E1|")
            val plaintext = when {
                encrypted -> cipher.decrypt(stored)
                MemoryJournalBaseCodec.isBase(stored) || MemoryJournalChainCodec.isChained(stored) ||
                    MemoryJournalFrameCodec.isFramed(stored) || stored.startsWith("R|") || stored.startsWith("D|") -> stored
                else -> error("cannot migrate unknown complete memory journal line")
            }
            MigrationEntry(plaintext, encrypted)
        }

        val plaintext = decoded.map { it.plaintext }
        if (MemoryJournalBaseCodec.isBase(plaintext.first())) {
            require(plaintext.drop(1).none(MemoryJournalBaseCodec::isBase)) {
                "memory journal base checkpoint must appear only first"
            }
            val segment = MemoryJournalSegmentCodec.decodePlaintext(plaintext).getOrThrow()
            segment.entries.forEach { validateMutationPayload(it.payload) }
            if (decoded.any { !it.encrypted }) {
                DurableJournalIo.rewriteUtf8LinesAtomically(
                    file,
                    ".encrypting-compacted-segment",
                    plaintext.map(cipher::encrypt)
                )
            }
            return segment.head
        }
        require(plaintext.none(MemoryJournalBaseCodec::isBase)) {
            "memory journal base checkpoint must appear only first"
        }

        val chainedCount = plaintext.count(MemoryJournalChainCodec::isChained)
        if (chainedCount == plaintext.size) {
            val entries = MemoryJournalChainCodec.decodeSequence(plaintext).getOrThrow()
            entries.forEach { validateMutationPayload(it.payload) }
            if (decoded.any { !it.encrypted }) {
                DurableJournalIo.rewriteUtf8LinesAtomically(
                    file,
                    ".encrypting-chain",
                    plaintext.map(cipher::encrypt)
                )
            }
            return MemoryJournalChainCodec.stateOf(entries)
        }
        require(chainedCount == 0) { "mixed chained and legacy encrypted memory journal framing" }

        val payloads = plaintext.map { value ->
            MemoryJournalFrameCodec.unwrapFramedOrLegacy(value).getOrThrow().also(::validateMutationPayload)
        }
        var state = MemoryJournalChainState.GENESIS
        val migrated = payloads.map { payload ->
            val entry = MemoryJournalChainCodec.encode(payload, state)
            state = MemoryJournalChainState(entry.sequence, entry.digest)
            cipher.encrypt(entry.encoded)
        }
        DurableJournalIo.rewriteUtf8LinesAtomically(file, ".encrypting-chain", migrated)
        return state
    }

    companion object {
        fun managed(
            file: File,
            provider: MemoryLineCipherProvider = AndroidKeystoreMemoryLineCipherProvider(),
            defaultKeyId: String = AndroidKeystoreMemoryLineCipher.DEFAULT_KEY_ID
        ): EncryptedFileMemoryJournal {
            val store = MemoryKeyManifestStore(keyManifestFileFor(file), defaultKeyId)
            val context = MemoryKeyRotationContext(store, provider)
            val manifest = context.loadManifest()
            return EncryptedFileMemoryJournal(file, context.resolve(manifest), context)
        }

        internal fun headFileFor(file: File): File = File(file.parentFile, file.name + ".head")
        internal fun lockFileFor(file: File): File = File(file.parentFile, file.name + ".lock")
        internal fun keyManifestFileFor(file: File): File = File(file.parentFile, file.name + ".keys")
    }
}
