package io.amper.neuroos.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignNamespaceOwnershipTest {
    @Test
    fun fixedSidecarNamesCannotBecomeJournalRootsOfMaterializedOwner() {
        val dir = Files.createTempDirectory("amper-namespace-fixed").toFile()
        File(dir, "memory").writeText("owner")
        val reserved = listOf(
            ".head",
            ".keys",
            ".lock",
            ".rewrite-lock",
            ".replace-pending",
            ".replace-backup",
            ".replace-staged",
            ".recovery-epochs",
            ".recovery-epochs.a",
            ".recovery-epochs.b",
            ".recovery-epochs.authority",
            ".recovery-epochs.active",
            ".updating",
            ".compacting",
            ".chaining",
            ".compacting-encrypted",
            ".rekeying",
            ".encrypting-compacted-segment",
            ".encrypting-chain"
        )

        reserved.forEach { suffix ->
            val candidate = File(dir, "memory$suffix")
            val rejected = runCatching { MemoryJournalProcessLock(candidate) }.isFailure
            assertTrue("expected namespace collision rejection for $suffix", rejected)
            assertFalse("collision must fail before bootstrap creates it", candidate.exists())
        }
    }

    @Test
    fun transactionSidecarNamesCannotBecomeJournalRootsOfMaterializedOwner() {
        val dir = Files.createTempDirectory("amper-namespace-scoped").toFile()
        File(dir, "memory").writeText("owner")
        val tx = "0123456789abcdef0123456789abcdef"
        val candidates = listOf(
            File(dir, "memory.replace-$tx.backup"),
            File(dir, "memory.replace-$tx.staged"),
            File(dir, "memory.rewrite-$tx.compacting"),
            File(dir, "memory.rewrite-$tx.encrypting-chain")
        )

        candidates.forEach { candidate ->
            assertTrue(runCatching { MemoryJournalProcessLock(candidate) }.isFailure)
            assertFalse(candidate.exists())
        }
    }

    @Test
    fun sidecarShapedStandaloneNamesRemainValidWithoutOwnerEvidence() {
        val dir = Files.createTempDirectory("amper-namespace-standalone").toFile()
        val tx = "0123456789abcdef0123456789abcdef"
        val candidates = listOf(
            File(dir, "notes.head"),
            File(dir, "notes.lock"),
            File(dir, "notes.recovery-epochs"),
            File(dir, "notes.replace-$tx.backup"),
            File(dir, "notes.rewrite-$tx.compacting")
        )

        candidates.forEach { candidate ->
            assertTrue(runCatching { MemoryJournalProcessLock(candidate) }.isSuccess)
            SovereignPathIdentity.requireManagedNamespace(candidate)
        }
    }

    @Test
    fun nearMissNamesRemainValidEvenBesideUnrelatedOwner() {
        val dir = Files.createTempDirectory("amper-namespace-near-miss").toFile()
        File(dir, "memory").writeText("owner")
        val candidates = listOf(
            File(dir, "memory.head.snapshot"),
            File(dir, "memory.locked"),
            File(dir, "memory.recovery-epochs-v2"),
            File(dir, "memory.replace-not-a-transaction.backup"),
            File(dir, "memory.rewrite-short.compacting")
        )

        candidates.forEach { candidate ->
            assertTrue(runCatching { MemoryJournalProcessLock(candidate) }.isSuccess)
            SovereignPathIdentity.requireManagedNamespace(candidate)
        }
    }

    @Test
    fun existingSidecarShapedLeafFailsClosedAsJournalRootWhenOwnerMaterializes() {
        val dir = Files.createTempDirectory("amper-namespace-existing").toFile()
        val candidate = File(dir, "memory.head").apply { writeText("foreign-state") }
        File(dir, "memory").writeText("owner")

        assertTrue(runCatching { SovereignPathIdentity.requireNamespaceRootName(candidate) }.isFailure)
        assertTrue(candidate.readText() == "foreign-state")
        // It remains a legitimate managed sidecar path; only treating it as a new root is forbidden.
        SovereignPathIdentity.requireManagedNamespace(candidate)
    }

    @Test
    fun siblingSidecarEvidenceAlsoReservesOwnerNamespace() {
        val dir = Files.createTempDirectory("amper-namespace-sibling").toFile()
        File(dir, "memory.keys").writeText("manifest")
        val candidate = File(dir, "memory.head")

        assertTrue(runCatching { MemoryJournalProcessLock(candidate) }.isFailure)
        assertFalse(candidate.exists())
    }
}
