package io.amper.neuroos.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DescriptorBoundMutationIoTest {
    @Test
    fun durableAppendUsesBoundRegularFile() {
        val dir = Files.createTempDirectory("amper-phase55-append").toFile()
        val file = File(dir, "memory.journal")
        try {
            file.writeText("one\n")

            DurableJournalIo.appendUtf8Line(file, "two")

            assertEquals("one\ntwo\n", file.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun durableAppendRejectsSymlinkWithoutMutatingVictim() {
        val dir = Files.createTempDirectory("amper-phase55-append-link").toFile()
        val victim = File(dir, "victim")
        val link = File(dir, "memory.journal")
        try {
            victim.writeText("victim\n")
            Files.createSymbolicLink(link.toPath(), victim.toPath())

            val failure = runCatching {
                DurableJournalIo.appendUtf8Line(link, "must-not-append")
            }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals("victim\n", victim.readText())
        } finally {
            Files.deleteIfExists(link.toPath())
            dir.deleteRecursively()
        }
    }

    @Test
    fun atomicPublicationMovesExactPreparedIdentityOverExistingTarget() {
        val dir = Files.createTempDirectory("amper-phase55-atomic-publish").toFile()
        val source = File(dir, "memory.journal.rewrite-${"a".repeat(32)}.updating")
        val target = File(dir, "memory.journal")
        try {
            source.writeText("new\n")
            target.writeText("old\n")
            val sourceIdentity = SovereignPathIdentity.snapshot(source)

            DescriptorBoundMutationIo.publishAtomicReplacing(source, target, sourceIdentity)

            assertFalse(source.exists())
            assertEquals("new\n", target.readText())
            val targetIdentity = SovereignPathIdentity.snapshot(target)
            assertNotNull(sourceIdentity.fileKey)
            assertEquals(sourceIdentity.fileKey, targetIdentity.fileKey)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun atomicPublicationRejectsSymlinkTargetWithoutTouchingVictim() {
        val dir = Files.createTempDirectory("amper-phase55-atomic-target-link").toFile()
        val source = File(dir, "prepared")
        val victim = File(dir, "victim")
        val target = File(dir, "memory.journal")
        try {
            source.writeText("new\n")
            victim.writeText("victim\n")
            Files.createSymbolicLink(target.toPath(), victim.toPath())

            val failure = runCatching {
                DescriptorBoundMutationIo.publishAtomicReplacing(source, target)
            }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(source.isFile)
            assertEquals("new\n", source.readText())
            assertEquals("victim\n", victim.readText())
            assertTrue(Files.isSymbolicLink(target.toPath()))
        } finally {
            Files.deleteIfExists(target.toPath())
            dir.deleteRecursively()
        }
    }

    @Test
    fun canonicalAtomicRewriteRejectsSymlinkTargetWithoutTouchingVictim() {
        val dir = Files.createTempDirectory("amper-phase55-rewrite-link").toFile()
        val victim = File(dir, "victim")
        val target = File(dir, "memory.head")
        try {
            victim.writeText("victim\n")
            Files.createSymbolicLink(target.toPath(), victim.toPath())

            val failure = runCatching {
                DurableJournalIo.rewriteUtf8LinesAtomically(target, ".updating", listOf("new"))
            }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals("victim\n", victim.readText())
            assertTrue(Files.isSymbolicLink(target.toPath()))
            val scopedTemp = Regex(
                "^${Regex.escape(target.name)}\\.rewrite-[0-9a-f]{32}\\.[A-Za-z0-9_-]+$"
            )
            assertTrue(dir.listFiles().orEmpty().none { scopedTemp.matches(it.name) })
        } finally {
            Files.deleteIfExists(target.toPath())
            dir.deleteRecursively()
        }
    }
}
