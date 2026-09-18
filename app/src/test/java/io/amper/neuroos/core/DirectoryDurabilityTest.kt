package io.amper.neuroos.core

import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryDurabilityTest {
    @Test
    fun durableCreatePersistsFileAndDirectoryBoundary() {
        val dir = Files.createTempDirectory("amper-directory-create").toFile()
        val file = File(dir, "memory.journal")
        try {
            assertFalse(file.exists())

            DurableJournalIo.ensureFileExistsDurably(file)

            assertTrue(file.exists())
            assertEquals(0L, file.length())
            DurableJournalIo.syncDirectory(dir)
        } finally {
            file.delete()
            dir.delete()
        }
    }

    @Test
    fun durableCreateMaterializesMissingDirectoryTree() {
        val root = Files.createTempDirectory("amper-directory-tree").toFile()
        val level1 = File(root, "sovereign")
        val level2 = File(level1, "memory")
        val level3 = File(level2, "journal")
        val file = File(level3, "memory.journal")
        try {
            assertFalse(level1.exists())
            assertFalse(level2.exists())
            assertFalse(level3.exists())

            DurableJournalIo.ensureFileExistsDurably(file)

            assertTrue(level1.isDirectory)
            assertTrue(level2.isDirectory)
            assertTrue(level3.isDirectory)
            assertTrue(file.exists())
            DurableJournalIo.syncDirectory(root)
            DurableJournalIo.syncDirectory(level1)
            DurableJournalIo.syncDirectory(level2)
            DurableJournalIo.syncDirectory(level3)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun atomicRewriteLeavesOnlySyncedDestination() {
        val dir = Files.createTempDirectory("amper-directory-rewrite").toFile()
        val file = File(dir, "memory.head")
        val temp = File(dir, "memory.head.updating")
        try {
            DurableJournalIo.rewriteUtf8LinesAtomically(
                file,
                ".updating",
                listOf("H1|0|${"0".repeat(64)}|test")
            )

            assertTrue(file.exists())
            assertFalse(temp.exists())
            assertEquals(listOf("H1|0|${"0".repeat(64)}|test"), file.readLines())
            DurableJournalIo.syncDirectory(dir)
        } finally {
            temp.delete()
            file.delete()
            dir.delete()
        }
    }

    @Test
    fun atomicRewriteMaterializesMissingDirectoryTree() {
        val root = Files.createTempDirectory("amper-directory-rewrite-tree").toFile()
        val parent = File(root, "state/checkpoints/head")
        val file = File(parent, "memory.head")
        val temp = File(parent, "memory.head.updating")
        try {
            assertFalse(parent.exists())

            DurableJournalIo.rewriteUtf8LinesAtomically(
                file,
                ".updating",
                listOf("phase40-directory-tree")
            )

            assertTrue(parent.isDirectory)
            assertTrue(file.exists())
            assertFalse(temp.exists())
            assertEquals(listOf("phase40-directory-tree"), file.readLines())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recoverableFallbackCommitsAndCleansTransactionSidecars() {
        val dir = Files.createTempDirectory("amper-replace-fallback").toFile()
        val file = File(dir, "memory.head")
        try {
            file.writeText("old\n")

            DurableJournalIo.rewriteUtf8LinesWithoutAtomicMove(
                file,
                ".updating",
                listOf("new")
            )

            assertEquals(listOf("new"), file.readLines())
            assertFalse(DurableJournalIo.replacementMarkerFileFor(file).exists())
            assertFalse(File(dir, file.name + ".updating").exists())
            assertTrue(
                dir.listFiles().orEmpty().none {
                    it.name.startsWith(file.name + ".replace-")
                }
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun interruptedExistingReplacementRollsBackOnlyBoundTransaction() {
        val dir = Files.createTempDirectory("amper-replace-existing-crash").toFile()
        val file = File(dir, "memory.head")
        val transactionId = DurableJournalIo.newReplacementTransactionId()
        val backup = DurableJournalIo.replacementBackupFileFor(file, transactionId)
        val marker = DurableJournalIo.replacementMarkerFileFor(file)
        val staged = DurableJournalIo.replacementStagedFileFor(file, transactionId)
        val oldState = MemoryJournalChainState.GENESIS
        val oldLine = MemoryJournalHeadCodec.encode(oldState)
        try {
            file.writeText("corrupted-new-target\n")
            backup.writeText(oldLine + "\n")
            staged.writeText("new-target\n")
            marker.writeText(
                DurableJournalIo.encodeExistingReplacementMarker(
                    transactionId,
                    DurableJournalIo.digestFile(staged),
                    DurableJournalIo.digestFile(backup)
                ) + "\n"
            )

            val recovered = PlaintextMemoryJournalHeadAnchor(file).load()

            assertEquals(oldState, recovered)
            assertEquals(listOf(oldLine), file.readLines())
            assertFalse(marker.exists())
            assertFalse(backup.exists())
            assertFalse(staged.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun interruptedNewReplacementRestoresFileAbsence() {
        val dir = Files.createTempDirectory("amper-replace-new-crash").toFile()
        val file = File(dir, "memory.keys")
        val transactionId = DurableJournalIo.newReplacementTransactionId()
        val marker = DurableJournalIo.replacementMarkerFileFor(file)
        val staged = DurableJournalIo.replacementStagedFileFor(file, transactionId)
        try {
            file.writeText("partially-published-new-file")
            staged.writeText("complete-staged-file")
            marker.writeText(
                DurableJournalIo.encodeNewReplacementMarker(
                    transactionId,
                    DurableJournalIo.digestFile(staged)
                ) + "\n"
            )

            DurableJournalIo.recoverInterruptedReplace(file)

            assertFalse(file.exists())
            assertFalse(marker.exists())
            assertFalse(staged.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun staleBackupFromDifferentTransactionCannotBeUsed() {
        val dir = Files.createTempDirectory("amper-replace-stale-transaction").toFile()
        val file = File(dir, "memory.head")
        val activeTransaction = DurableJournalIo.newReplacementTransactionId()
        val staleTransaction = DurableJournalIo.newReplacementTransactionId()
        val activeStaged = DurableJournalIo.replacementStagedFileFor(file, activeTransaction)
        val staleBackup = DurableJournalIo.replacementBackupFileFor(file, staleTransaction)
        val marker = DurableJournalIo.replacementMarkerFileFor(file)
        try {
            file.writeText("published-but-uncommitted\n")
            activeStaged.writeText("new-authority\n")
            staleBackup.writeText("stale-old-authority\n")
            marker.writeText(
                DurableJournalIo.encodeExistingReplacementMarker(
                    activeTransaction,
                    DurableJournalIo.digestFile(activeStaged),
                    DurableJournalIo.digestFile(staleBackup)
                ) + "\n"
            )

            assertTrue(runCatching { DurableJournalIo.recoverInterruptedReplace(file) }.isFailure)
            assertEquals("published-but-uncommitted\n", file.readText())
            assertTrue(marker.exists())
            assertTrue(staleBackup.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rollbackDigestMismatchFailsClosedBeforeTargetMutation() {
        val dir = Files.createTempDirectory("amper-replace-digest-mismatch").toFile()
        val file = File(dir, "memory.head")
        val transactionId = DurableJournalIo.newReplacementTransactionId()
        val staged = DurableJournalIo.replacementStagedFileFor(file, transactionId)
        val backup = DurableJournalIo.replacementBackupFileFor(file, transactionId)
        val marker = DurableJournalIo.replacementMarkerFileFor(file)
        try {
            file.writeText("published-but-uncommitted\n")
            staged.writeText("new-authority\n")
            backup.writeText("real-old-authority\n")
            marker.writeText(
                DurableJournalIo.encodeExistingReplacementMarker(
                    transactionId,
                    DurableJournalIo.digestFile(staged),
                    "0".repeat(64)
                ) + "\n"
            )

            assertTrue(runCatching { DurableJournalIo.recoverInterruptedReplace(file) }.isFailure)
            assertEquals("published-but-uncommitted\n", file.readText())
            assertTrue(marker.exists())
            assertTrue(backup.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun markerChecksumMismatchFailsClosed() {
        val dir = Files.createTempDirectory("amper-replace-marker-checksum").toFile()
        val file = File(dir, "memory.head")
        val transactionId = DurableJournalIo.newReplacementTransactionId()
        val staged = DurableJournalIo.replacementStagedFileFor(file, transactionId)
        val marker = DurableJournalIo.replacementMarkerFileFor(file)
        try {
            file.writeText("current-authority\n")
            staged.writeText("new-authority\n")
            val valid = DurableJournalIo.encodeNewReplacementMarker(
                transactionId,
                DurableJournalIo.digestFile(staged)
            )
            val corrupted = valid.dropLast(1) + if (valid.last() == '0') "1" else "0"
            marker.writeText(corrupted + "\n")

            assertTrue(runCatching { DurableJournalIo.recoverInterruptedReplace(file) }.isFailure)
            assertEquals("current-authority\n", file.readText())
            assertTrue(marker.exists())
            assertTrue(staged.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun keyManifestBootstrapRecoversTransactionBoundReplacement() {
        val dir = Files.createTempDirectory("amper-replace-k1-crash").toFile()
        val file = File(dir, "memory.journal.keys")
        val transactionId = DurableJournalIo.newReplacementTransactionId()
        val backup = DurableJournalIo.replacementBackupFileFor(file, transactionId)
        val staged = DurableJournalIo.replacementStagedFileFor(file, transactionId)
        val marker = DurableJournalIo.replacementMarkerFileFor(file)
        val old = MemoryKeyManifest(activeKeyId = "phase42-old-key")
        val oldLine = MemoryKeyManifestCodec.encode(old)
        try {
            file.writeText("broken-replacement\n")
            backup.writeText(oldLine + "\n")
            staged.writeText("replacement-k1\n")
            marker.writeText(
                DurableJournalIo.encodeExistingReplacementMarker(
                    transactionId,
                    DurableJournalIo.digestFile(staged),
                    DurableJournalIo.digestFile(backup)
                ) + "\n"
            )

            val recovered = MemoryKeyManifestStore(file, "phase42-bootstrap-key").loadOrBootstrap()

            assertEquals(old, recovered)
            assertEquals(listOf(oldLine), file.readLines())
            assertFalse(marker.exists())
            assertFalse(backup.exists())
            assertFalse(staged.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun phase41MarkerRemainsRecoverableAcrossUpgrade() {
        val dir = Files.createTempDirectory("amper-replace-r1-upgrade").toFile()
        val file = File(dir, "memory.head")
        val backup = File(dir, file.name + ".replace-backup")
        val staged = File(dir, file.name + ".replace-staged")
        val marker = DurableJournalIo.replacementMarkerFileFor(file)
        val oldState = MemoryJournalChainState.GENESIS
        val oldLine = MemoryJournalHeadCodec.encode(oldState)
        try {
            file.writeText("phase41-partial-target\n")
            backup.writeText(oldLine + "\n")
            staged.writeText("phase41-staged\n")
            marker.writeText("R1|existing\n")

            val recovered = PlaintextMemoryJournalHeadAnchor(file).load()

            assertEquals(oldState, recovered)
            assertFalse(marker.exists())
            assertFalse(backup.exists())
            assertFalse(staged.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun unknownRecoveryMarkerFailsClosed() {
        val dir = Files.createTempDirectory("amper-replace-unknown-marker").toFile()
        val file = File(dir, "memory.head")
        val marker = DurableJournalIo.replacementMarkerFileFor(file)
        try {
            file.writeText("authoritative-old-data\n")
            marker.writeText("R9|unknown\n")

            assertTrue(runCatching { DurableJournalIo.recoverInterruptedReplace(file) }.isFailure)
            assertEquals("authoritative-old-data\n", file.readText())
            assertTrue(marker.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun keyManifestBootstrapUsesRecursiveDurableDirectoryPath() {
        val root = Files.createTempDirectory("amper-directory-key-tree").toFile()
        val file = File(root, "identity/keys/memory.journal.keys")
        try {
            assertFalse(file.parentFile.exists())

            val manifest = MemoryKeyManifestStore(file, "phase40-key").loadOrBootstrap()

            assertEquals("phase40-key", manifest.activeKeyId)
            assertTrue(file.exists())
            assertTrue(file.parentFile.isDirectory)
            assertTrue(file.readLines().single().startsWith("K1|"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun processLockUsesRecursiveDurableDirectoryPath() {
        val root = Files.createTempDirectory("amper-directory-lock-tree").toFile()
        val journal = File(root, "runtime/locks/memory.journal")
        val lockFile = File(journal.parentFile, journal.name + ".lock")
        try {
            assertFalse(journal.parentFile.exists())

            val result = MemoryJournalProcessLock(journal).exclusive { "locked" }

            assertEquals("locked", result)
            assertTrue(journal.parentFile.isDirectory)
            assertTrue(lockFile.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun plaintextJournalUsesDurableCreationPath() {
        val dir = Files.createTempDirectory("amper-directory-journal").toFile()
        val file = File(dir, "memory.journal")
        try {
            val journal = FileMemoryJournal(file)
            journal.append(
                MemoryRecord(
                    id = MemoryId("durable-directory-record"),
                    kind = "durability-test",
                    content = "directory metadata durable",
                    importance = 1.0,
                    provenance = Provenance(source = "test", producer = "phase38")
                )
            )

            assertEquals("durable-directory-record", FileMemoryJournal(file).replay().single().id.value)
        } finally {
            file.delete()
            FileMemoryJournal.headFileFor(file).delete()
            FileMemoryJournal.lockFileFor(file).delete()
            dir.delete()
        }
    }

    @Test
    fun encryptedJournalUsesDurableCreationPath() {
        val dir = Files.createTempDirectory("amper-directory-encrypted-journal").toFile()
        val file = File(dir, "memory.journal")
        val key = SecretKeySpec(ByteArray(32) { index -> (73 + index).toByte() }, "AES")
        fun cipher(): MemoryLineCipher = AesGcmMemoryLineCipher(key, "phase39-test-key")
        try {
            assertFalse(file.exists())

            val journal = EncryptedFileMemoryJournal(file, cipher())
            journal.append(
                MemoryRecord(
                    id = MemoryId("durable-encrypted-record"),
                    kind = "durability-test",
                    content = "encrypted directory metadata durable",
                    importance = 1.0,
                    provenance = Provenance(source = "test", producer = "phase39")
                )
            )

            assertTrue(file.exists())
            assertTrue(file.readLines().filter { it.isNotBlank() }.all { it.startsWith("E1|") })
            assertEquals(
                "durable-encrypted-record",
                EncryptedFileMemoryJournal(file, cipher()).replay().single().id.value
            )
        } finally {
            file.delete()
            EncryptedFileMemoryJournal.headFileFor(file).delete()
            EncryptedFileMemoryJournal.lockFileFor(file).delete()
            dir.delete()
        }
    }
}
