package io.amper.neuroos.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RewriteTempReclamationTest {
    private val legacySuffixes = listOf(
        ".updating",
        ".compacting",
        ".chaining",
        ".compacting-encrypted",
        ".rekeying",
        ".encrypting-compacted-segment",
        ".encrypting-chain"
    )

    @Test
    fun rewriteTempsAreTransactionScopedInsteadOfStaticPurposeNames() {
        val dir = Files.createTempDirectory("amper-rewrite-temp-scope").toFile()
        val target = File(dir, "memory.journal")
        try {
            val first = DurableJournalIo.rewriteTempFileFor(
                target,
                ".compacting",
                "a".repeat(32)
            )
            val second = DurableJournalIo.rewriteTempFileFor(
                target,
                ".compacting",
                "b".repeat(32)
            )

            assertNotEquals(first.name, second.name)
            assertEquals(
                "memory.journal.rewrite-${"a".repeat(32)}.compacting",
                first.name
            )
            assertEquals(
                "memory.journal.rewrite-${"b".repeat(32)}.compacting",
                second.name
            )
            assertNotEquals("memory.journal.compacting", first.name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun recoveryReclaimsScopedAndLegacyRewriteTempsWithoutTouchingDurableFiles() {
        val dir = Files.createTempDirectory("amper-rewrite-temp-reclaim").toFile()
        val target = File(dir, "memory.journal")
        val durableHead = File(dir, "memory.journal.head")
        val durableKeys = File(dir, "memory.journal.keys")
        try {
            target.writeText("stable\n")
            durableHead.writeText("head-authority\n")
            durableKeys.writeText("key-authority\n")

            val scoped = listOf(
                DurableJournalIo.rewriteTempFileFor(target, ".compacting", "1".repeat(32)),
                DurableJournalIo.rewriteTempFileFor(target, ".chaining", "2".repeat(32)),
                DurableJournalIo.rewriteTempFileFor(target, ".rekeying", "3".repeat(32))
            )
            scoped.forEach { it.writeText("orphan\n") }
            val legacy = legacySuffixes.map { suffix -> File(dir, target.name + suffix) }
            legacy.forEach { it.writeText("legacy-orphan\n") }

            DurableJournalIo.recoverInterruptedReplace(target)

            assertEquals("stable\n", target.readText())
            assertTrue(durableHead.isFile)
            assertEquals("head-authority\n", durableHead.readText())
            assertTrue(durableKeys.isFile)
            assertEquals("key-authority\n", durableKeys.readText())
            assertTrue(scoped.none(File::exists))
            assertTrue(legacy.none(File::exists))
            assertTrue(DurableJournalIo.rewriteLockFileFor(target).isFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fallbackRewriteReclaimsKilledWriterTempThenMakesForwardProgress() {
        val dir = Files.createTempDirectory("amper-rewrite-temp-progress").toFile()
        val target = File(dir, "memory.head")
        try {
            target.writeText("old\n")
            val orphan = DurableJournalIo.rewriteTempFileFor(
                target,
                ".updating",
                "f".repeat(32)
            )
            orphan.writeText("killed-writer-temp\n")
            assertTrue(orphan.isFile)

            DurableJournalIo.rewriteUtf8LinesWithoutAtomicMove(
                target,
                ".updating",
                listOf("new")
            )

            assertEquals("new\n", target.readText())
            assertEquals(1L, DurableJournalIo.currentReplacementEpoch(target))
            assertFalse(orphan.exists())
            assertTrue(
                dir.listFiles().orEmpty().none {
                    it.name.startsWith(target.name + ".rewrite-") && it.name != target.name + ".rewrite-lock"
                }
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun invalidRewritePurposeCannotEscapeTransactionTempNamespace() {
        val dir = Files.createTempDirectory("amper-rewrite-temp-purpose").toFile()
        val target = File(dir, "memory.journal")
        try {
            val invalid = listOf("compacting", ".../escape", ".bad/name", ".bad.name", ".")
            invalid.forEach { suffix ->
                assertTrue(
                    "suffix=$suffix",
                    runCatching {
                        DurableJournalIo.rewriteTempFileFor(target, suffix, "a".repeat(32))
                    }.isFailure
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
