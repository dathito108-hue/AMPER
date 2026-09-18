package io.amper.neuroos.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryEpochAuthorityRotationTest {
    @Test
    fun tornCheckpointSelfHealsFromG3AndMatchingPeer() {
        val dir = Files.createTempDirectory("amper-recovery-quorum-heal-torn").toFile()
        val target = File(dir, "memory.head")
        val journal = RecoveryEpochJournal(target)
        val bound = journal.compactEveryForTest()
        try {
            repeat(bound) { journal.advance() }
            val checkpoints = journal.checkpointFilesForTest()
            checkpoints.first().writeText("G2|torn")

            assertEquals(bound.toLong(), RecoveryEpochJournal(target).current())
            assertTrue(checkpoints.all { it.readLines().single().startsWith("G2|$bound|") })
            assertTrue(journal.authorityFileForTest().readLines().single().startsWith("G3|$bound|"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingCheckpointSelfHealsFromG3AndMatchingPeer() {
        val dir = Files.createTempDirectory("amper-recovery-quorum-heal-missing").toFile()
        val target = File(dir, "memory.head")
        val journal = RecoveryEpochJournal(target)
        val bound = journal.compactEveryForTest()
        try {
            repeat(bound) { journal.advance() }
            val checkpoints = journal.checkpointFilesForTest()
            assertTrue(checkpoints.first().delete())

            assertEquals(bound.toLong(), RecoveryEpochJournal(target).current())
            assertTrue(checkpoints.first().isFile)
            assertTrue(checkpoints.all { it.readLines().single().startsWith("G2|$bound|") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun olderRotationSurvivorIsHealedAfterNewG3AndPeerAreDurable() {
        val dir = Files.createTempDirectory("amper-recovery-quorum-rotation-survivor").toFile()
        val target = File(dir, "memory.head")
        val journal = RecoveryEpochJournal(target)
        val bound = journal.compactEveryForTest()
        try {
            repeat(bound) { journal.advance() }
            val checkpoints = journal.checkpointFilesForTest()
            val oldRetainedBytes = checkpoints.last().readText()

            repeat(bound) { journal.advance() }
            val currentEpoch = (bound * 2).toLong()
            assertEquals(currentEpoch, journal.current())

            // Model the crash boundary after G3 + one G2 were advanced and G1 was synced
            // empty, but before the deliberately retained older G2 slot was rotated forward.
            checkpoints.last().writeText(oldRetainedBytes)

            assertEquals(currentEpoch, RecoveryEpochJournal(target).current())
            assertTrue(checkpoints.all { it.readLines().single().startsWith("G2|$currentEpoch|") })
            assertTrue(journal.authorityFileForTest().readLines().single().startsWith("G3|$currentEpoch|"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rolledBackG3AndOneG2CannotOutvoteNewerCheckpoint() {
        val dir = Files.createTempDirectory("amper-recovery-quorum-two-file-replay").toFile()
        val target = File(dir, "memory.head")
        val journal = RecoveryEpochJournal(target)
        val bound = journal.compactEveryForTest()
        try {
            repeat(bound) { journal.advance() }
            val checkpoints = journal.checkpointFilesForTest()
            val staleAuthority = journal.authorityFileForTest().readText()
            val staleCheckpoint = checkpoints.first().readText()

            repeat(bound) { journal.advance() }
            val currentEpoch = (bound * 2).toLong()
            assertEquals(currentEpoch, journal.current())
            assertTrue(checkpoints.last().readLines().single().startsWith("G2|$currentEpoch|"))

            // Roll back G3 together with one G2. The remaining newer G2 must block repair;
            // otherwise two stale local files could erase evidence of the newer generation.
            journal.authorityFileForTest().writeText(staleAuthority)
            checkpoints.first().writeText(staleCheckpoint)

            assertTrue(runCatching { RecoveryEpochJournal(target).current() }.isFailure)
            assertTrue(checkpoints.last().readLines().single().startsWith("G2|$currentEpoch|"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
