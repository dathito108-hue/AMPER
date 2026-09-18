package io.amper.neuroos.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DescriptorBoundFileIoTest {
    @Test
    fun singleLineReadRejectsSymlinkWithoutReadingVictim() {
        val dir = Files.createTempDirectory("amper-descriptor-read-link").toFile()
        val victim = File(dir, "victim.txt")
        val link = File(dir, "head")
        try {
            victim.writeText("secret\n")
            Files.createSymbolicLink(link.toPath(), victim.toPath())

            val failure = runCatching {
                DescriptorBoundFileIo.readSingleCompleteUtf8Line(link, "test metadata")
            }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("symbolic link"))
            assertEquals("secret\n", victim.readText())
        } finally {
            Files.deleteIfExists(link.toPath())
            dir.deleteRecursively()
        }
    }

    @Test
    fun tornTailRepairRejectsSymlinkWithoutTruncatingVictim() {
        val dir = Files.createTempDirectory("amper-descriptor-tail-link").toFile()
        val victim = File(dir, "victim.txt")
        val link = File(dir, "manifest")
        try {
            victim.writeText("complete\npartial")
            Files.createSymbolicLink(link.toPath(), victim.toPath())

            val failure = runCatching {
                DescriptorBoundFileIo.readCompleteUtf8LinesRecoveringTornTail(link)
            }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals("complete\npartial", victim.readText())
        } finally {
            Files.deleteIfExists(link.toPath())
            dir.deleteRecursively()
        }
    }

    @Test
    fun tornTailRepairTruncatesOnlyBoundRegularFile() {
        val dir = Files.createTempDirectory("amper-descriptor-tail").toFile()
        val file = File(dir, "manifest")
        try {
            file.writeText("first\nsecond\npartial")

            val lines = DescriptorBoundFileIo.readCompleteUtf8LinesRecoveringTornTail(file)

            assertEquals(listOf("first", "second"), lines)
            assertEquals("first\nsecond\n", file.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun exclusiveLockNeverFollowsSymlinkLeaf() {
        val dir = Files.createTempDirectory("amper-descriptor-lock-link").toFile()
        val victim = File(dir, "victim-lock")
        val link = File(dir, "memory.lock")
        try {
            victim.writeText("do-not-touch")
            Files.createSymbolicLink(link.toPath(), victim.toPath())

            var entered = false
            val failure = runCatching {
                DescriptorBoundFileIo.withExclusiveLock(link) { entered = true }
            }.exceptionOrNull()

            assertNotNull(failure)
            assertFalse(entered)
            assertEquals("do-not-touch", victim.readText())
        } finally {
            Files.deleteIfExists(link.toPath())
            dir.deleteRecursively()
        }
    }

    @Test
    fun exclusiveLockCreatesAndKeepsRegularLeaf() {
        val dir = Files.createTempDirectory("amper-descriptor-lock-create").toFile()
        val lock = File(dir, "memory.lock")
        try {
            var entered = false

            DescriptorBoundFileIo.withExclusiveLock(lock) { entered = true }

            assertTrue(entered)
            assertTrue(lock.isFile)
            assertFalse(Files.isSymbolicLink(lock.toPath()))
            assertEquals(0L, lock.length())
        } finally {
            dir.deleteRecursively()
        }
    }
}
