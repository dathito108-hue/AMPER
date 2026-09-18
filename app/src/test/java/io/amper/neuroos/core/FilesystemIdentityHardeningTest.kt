package io.amper.neuroos.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class FilesystemIdentityHardeningTest {
    @Test
    fun journalLeafSymlinkIsRejectedBeforeVictimMutation() {
        val dir = Files.createTempDirectory("amper-path-journal-link").toFile()
        val victim = File(dir, "victim.txt")
        val journal = File(dir, "memory.journal")
        try {
            victim.writeText("victim\n")
            Files.createSymbolicLink(journal.toPath(), victim.toPath())

            val failure = runCatching { FileMemoryJournal(journal) }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("symbolic link"))
            assertEquals("victim\n", victim.readText())
        } finally {
            Files.deleteIfExists(journal.toPath())
            dir.deleteRecursively()
        }
    }

    @Test
    fun symbolicParentIsRejectedBeforeJournalCreation() {
        val root = Files.createTempDirectory("amper-path-parent-link").toFile()
        val realParent = File(root, "real").apply { mkdir() }
        val alias = File(root, "alias")
        val journal = File(alias, "memory.journal")
        try {
            Files.createSymbolicLink(alias.toPath(), realParent.toPath())

            val failure = runCatching { FileMemoryJournal(journal) }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("symbolic link"))
            assertFalse(File(realParent, "memory.journal").exists())
        } finally {
            Files.deleteIfExists(alias.toPath())
            root.deleteRecursively()
        }
    }

    @Test
    fun headAnchorSymlinkIsRejected() {
        val dir = Files.createTempDirectory("amper-path-head-link").toFile()
        val victim = File(dir, "victim-head")
        val head = File(dir, "memory.journal.head")
        try {
            victim.writeText("do-not-touch\n")
            Files.createSymbolicLink(head.toPath(), victim.toPath())

            val failure = runCatching { PlaintextMemoryJournalHeadAnchor(head).load() }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("symbolic link"))
            assertEquals("do-not-touch\n", victim.readText())
        } finally {
            Files.deleteIfExists(head.toPath())
            dir.deleteRecursively()
        }
    }

    @Test
    fun keyManifestSymlinkIsRejectedBeforeBootstrap() {
        val dir = Files.createTempDirectory("amper-path-keys-link").toFile()
        val victim = File(dir, "victim-keys")
        val keys = File(dir, "memory.journal.keys")
        try {
            victim.writeText("not-a-manifest\n")
            Files.createSymbolicLink(keys.toPath(), victim.toPath())

            val failure = runCatching {
                MemoryKeyManifestStore(keys, "phase50-key").loadOrBootstrap()
            }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("symbolic link"))
            assertEquals("not-a-manifest\n", victim.readText())
        } finally {
            Files.deleteIfExists(keys.toPath())
            dir.deleteRecursively()
        }
    }

    @Test
    fun processLockRejectsSymlinkedRecoveryAuthorityInJournalNamespace() {
        val dir = Files.createTempDirectory("amper-path-authority-link").toFile()
        val journal = File(dir, "memory.journal")
        val victim = File(dir, "victim-authority")
        val authority = File(dir, journal.name + ".recovery-epochs.authority")
        try {
            journal.writeText("safe\n")
            victim.writeText("authority-victim\n")
            Files.createSymbolicLink(authority.toPath(), victim.toPath())

            val lock = MemoryJournalProcessLock(journal)
            val failure = runCatching { lock.exclusive { Unit } }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("symbolic link"))
            assertEquals("authority-victim\n", victim.readText())
        } finally {
            Files.deleteIfExists(authority.toPath())
            dir.deleteRecursively()
        }
    }

    @Test
    fun processLockRejectsTransactionScopedRewriteSymlink() {
        val dir = Files.createTempDirectory("amper-path-rewrite-link").toFile()
        val journal = File(dir, "memory.journal")
        val victim = File(dir, "victim-rewrite")
        val transactionId = "a".repeat(32)
        val rewrite = File(dir, "${journal.name}.rewrite-$transactionId.updating")
        try {
            journal.writeText("safe\n")
            victim.writeText("rewrite-victim\n")
            Files.createSymbolicLink(rewrite.toPath(), victim.toPath())

            val lock = MemoryJournalProcessLock(journal)
            val failure = runCatching { lock.exclusive { Unit } }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("symbolic link"))
            assertEquals("rewrite-victim\n", victim.readText())
        } finally {
            Files.deleteIfExists(rewrite.toPath())
            dir.deleteRecursively()
        }
    }

    @Test
    fun processLockFileIsNeverFollowedWhenItIsASymlink() {
        val dir = Files.createTempDirectory("amper-path-lock-link").toFile()
        val journal = File(dir, "memory.journal")
        val victim = File(dir, "victim-lock")
        val lockFile = File(dir, journal.name + ".lock")
        try {
            journal.writeText("safe\n")
            victim.writeText("lock-victim\n")
            Files.createSymbolicLink(lockFile.toPath(), victim.toPath())

            val lock = MemoryJournalProcessLock(journal)
            val failure = runCatching { lock.exclusive { Unit } }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("symbolic link"))
            assertEquals("lock-victim\n", victim.readText())
        } finally {
            Files.deleteIfExists(lockFile.toPath())
            dir.deleteRecursively()
        }
    }

    @Test
    fun fileKeyDetectsPathSubstitutionWhenProviderExposesIdentity() {
        val dir = Files.createTempDirectory("amper-path-filekey").toFile()
        val file = File(dir, "memory.journal")
        val replacement = File(dir, "replacement.journal")
        try {
            file.writeText("first\n")
            replacement.writeText("replacement\n")
            val identity = SovereignPathIdentity.snapshot(file)
            val replacementIdentity = SovereignPathIdentity.snapshot(replacement)
            assumeTrue(
                "filesystem provider does not expose distinct fileKey identities",
                identity.fileKey != null && replacementIdentity.fileKey != null &&
                    identity.fileKey != replacementIdentity.fileKey
            )

            assertTrue(file.delete())
            Files.move(replacement.toPath(), file.toPath())

            val failure = runCatching {
                SovereignPathIdentity.requireSameIdentity(identity, file)
            }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("identity changed"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
