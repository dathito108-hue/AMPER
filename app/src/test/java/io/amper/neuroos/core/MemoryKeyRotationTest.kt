package io.amper.neuroos.core

import java.io.File
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryKeyRotationTest {
    private fun record(id: String, content: String = "secret-$id") = MemoryRecord(
        id = MemoryId(id),
        kind = "key-rotation-test",
        content = content,
        importance = 1.0,
        provenance = Provenance(source = "test", producer = "phase37", confidence = 1.0)
    )

    private class TestProvider : MemoryLineCipherProvider {
        private val ciphers = linkedMapOf<String, MemoryLineCipher>()
        val retired = linkedSetOf<String>()

        fun add(id: String, seed: Int): TestProvider = apply {
            val key = SecretKeySpec(ByteArray(32) { index -> (seed + index).toByte() }, "AES")
            ciphers[id] = AesGcmMemoryLineCipher(key, id)
        }

        override fun cipher(keyId: String): MemoryLineCipher =
            requireNotNull(ciphers[keyId]) { "test key $keyId unavailable" }

        override fun retire(keyId: String): Boolean {
            retired += keyId
            ciphers.remove(keyId)
            return true
        }
    }

    @Test
    fun managedRotationRewrapsE1WithoutChangingInnerLineage() {
        val file = File.createTempFile("amper-key-rotate", ".journal")
        val provider = TestProvider().add("old-key", 11).add("new-key", 71)
        try {
            val journal = EncryptedFileMemoryJournal.managed(file, provider, "old-key")
            journal.append(record("a", "a-v1"))
            journal.append(record("b"))
            journal.append(record("c"))
            journal.tombstone(MemoryId("b"))
            journal.append(record("a", "a-v2"))
            assertTrue(journal.compact() > 0)

            val oldCipher = provider.cipher("old-key")
            val beforeInner = file.readLines().map(oldCipher::decrypt)
            assertTrue(MemoryJournalBaseCodec.isBase(beforeInner.first()))

            val rewritten = journal.rotateEncryption("new-key")

            assertTrue(rewritten > 0)
            assertTrue(file.readLines().all { MemoryLineCipherRing.envelopeKeyId(it) == "new-key" })
            assertTrue(
                EncryptedFileMemoryJournal.headFileFor(file).readLines()
                    .all { MemoryLineCipherRing.envelopeKeyId(it) == "new-key" }
            )
            val newCipher = provider.cipher("new-key")
            assertEquals(beforeInner, file.readLines().map(newCipher::decrypt))
            assertTrue("old-key" in provider.retired)

            val manifest = MemoryKeyManifestStore(
                EncryptedFileMemoryJournal.keyManifestFileFor(file),
                "old-key"
            ).load()
            assertEquals("new-key", manifest.activeKeyId)
            assertTrue(manifest.fallbackKeyIds.isEmpty())
            assertTrue(manifest.retirePendingKeyIds.isEmpty())

            val reopened = EncryptedFileMemoryJournal.managed(file, provider, "old-key")
            assertEquals(listOf("a", "c"), reopened.replay().map { it.id.value }.sorted())
            assertEquals("a-v2", reopened.replay().first { it.id.value == "a" }.content)
            assertEquals(0, reopened.rotateEncryption("new-key"))
        } finally {
            cleanup(file)
        }
    }

    @Test
    fun transitionManifestRecoversCrashBetweenJournalAndHeadRewrap() {
        val file = File.createTempFile("amper-key-rotate-crash", ".journal")
        val provider = TestProvider().add("old-key", 21).add("new-key", 91)
        try {
            val original = EncryptedFileMemoryJournal.managed(file, provider, "old-key")
            original.append(record("a"))
            original.append(record("b"))

            val oldCipher = provider.cipher("old-key")
            val newCipher = provider.cipher("new-key")
            val innerBefore = file.readLines().map(oldCipher::decrypt)
            val headBefore = EncryptedFileMemoryJournal.headFileFor(file).readLines().single()
            assertEquals("old-key", MemoryLineCipherRing.envelopeKeyId(headBefore))

            val store = MemoryKeyManifestStore(
                EncryptedFileMemoryJournal.keyManifestFileFor(file),
                "old-key"
            )
            store.store(
                MemoryKeyManifest(
                    activeKeyId = "new-key",
                    fallbackKeyIds = setOf("old-key")
                )
            )

            // Simulate a crash after the journal atomically moved to the new key but before H1.
            DurableJournalIo.rewriteUtf8LinesAtomically(
                file,
                ".test-partial-rekey",
                innerBefore.map(newCipher::encrypt)
            )
            assertTrue(file.readLines().all { MemoryLineCipherRing.envelopeKeyId(it) == "new-key" })
            assertEquals(
                "old-key",
                MemoryLineCipherRing.envelopeKeyId(
                    EncryptedFileMemoryJournal.headFileFor(file).readLines().single()
                )
            )

            val recovered = EncryptedFileMemoryJournal.managed(file, provider, "old-key")

            assertEquals(listOf("a", "b"), recovered.replay().map { it.id.value }.sorted())
            assertTrue(file.readLines().all { MemoryLineCipherRing.envelopeKeyId(it) == "new-key" })
            assertEquals(
                "new-key",
                MemoryLineCipherRing.envelopeKeyId(
                    EncryptedFileMemoryJournal.headFileFor(file).readLines().single()
                )
            )
            val manifest = store.load()
            assertEquals("new-key", manifest.activeKeyId)
            assertTrue(manifest.fallbackKeyIds.isEmpty())
            assertTrue(manifest.retirePendingKeyIds.isEmpty())
            assertTrue("old-key" in provider.retired)
            assertEquals(innerBefore, file.readLines().map(provider.cipher("new-key")::decrypt))
        } finally {
            cleanup(file)
        }
    }

    @Test
    fun corruptK1ManifestFailsClosedBeforeMemoryOpen() {
        val file = File.createTempFile("amper-key-manifest-corrupt", ".journal")
        val provider = TestProvider().add("old-key", 31)
        try {
            EncryptedFileMemoryJournal.managed(file, provider, "old-key").append(record("a"))
            val manifestFile = EncryptedFileMemoryJournal.keyManifestFileFor(file)
            val line = manifestFile.readLines().single()
            val replacement = if (line.last() == 'a') 'b' else 'a'
            manifestFile.writeText(line.dropLast(1) + replacement + "\n")

            val result = runCatching {
                EncryptedFileMemoryJournal.managed(file, provider, "old-key")
            }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("manifest"))
        } finally {
            cleanup(file)
        }
    }

    @Test
    fun unmanagedCipherPathRejectsRotation() {
        val file = File.createTempFile("amper-key-unmanaged", ".journal")
        val key = SecretKeySpec(ByteArray(32) { index -> (41 + index).toByte() }, "AES")
        try {
            val journal = EncryptedFileMemoryJournal(file, AesGcmMemoryLineCipher(key, "single-key"))
            journal.append(record("a"))

            val result = runCatching { journal.rotateEncryption("other-key") }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("managed"))
            assertFalse(EncryptedFileMemoryJournal.keyManifestFileFor(file).exists())
        } finally {
            cleanup(file)
        }
    }

    private fun cleanup(file: File) {
        file.delete()
        EncryptedFileMemoryJournal.headFileFor(file).delete()
        EncryptedFileMemoryJournal.lockFileFor(file).delete()
        EncryptedFileMemoryJournal.keyManifestFileFor(file).delete()
    }
}
