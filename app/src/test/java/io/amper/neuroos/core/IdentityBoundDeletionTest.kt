package io.amper.neuroos.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityBoundDeletionTest {
    @Test
    fun boundDeletionRejectsSymlinkAndPreservesVictim() {
        val dir = Files.createTempDirectory("amper-phase54-delete-link").toFile()
        val victim = File(dir, "victim")
        val link = File(dir, "managed-sidecar")
        try {
            victim.writeText("preserve-me\n")
            Files.createSymbolicLink(link.toPath(), victim.toPath())

            val failure = runCatching {
                DescriptorBoundFileIo.deleteIfExistsBound(link)
            }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertEquals("preserve-me\n", victim.readText())
        } finally {
            Files.deleteIfExists(link.toPath())
            dir.deleteRecursively()
        }
    }

    @Test
    fun expectedIdentityRejectsRegularFileRebindBeforeDelete() {
        val dir = Files.createTempDirectory("amper-phase54-delete-rebind").toFile()
        val file = File(dir, "memory.journal.replace-pending")
        val displaced = File(dir, "displaced-marker")
        try {
            file.writeText("old-marker\n")
            val expected = SovereignPathIdentity.snapshot(file)
            Files.move(file.toPath(), displaced.toPath(), StandardCopyOption.ATOMIC_MOVE)
            file.writeText("replacement-marker\n")

            val failure = runCatching {
                DescriptorBoundFileIo.deleteIfExistsBound(file, expected)
            }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals("replacement-marker\n", file.readText())
            assertEquals("old-marker\n", displaced.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun decodedMarkerIdentityCannotCommitAReboundMarker() {
        val dir = Files.createTempDirectory("amper-phase54-marker-rebind").toFile()
        val marker = File(dir, "memory.head.replace-pending")
        val displaced = File(dir, "decoded-marker")
        try {
            marker.writeText("R1|new\n")
            val bound = DescriptorBoundFileIo.readUtf8TextBound(marker)
            assertEquals("R1|new\n", bound.text)

            Files.move(marker.toPath(), displaced.toPath(), StandardCopyOption.ATOMIC_MOVE)
            marker.writeText("R9|replacement\n")

            val failure = runCatching {
                DescriptorBoundFileIo.deleteIfExistsBound(marker, bound.identity)
            }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals("R9|replacement\n", marker.readText())
            assertEquals("R1|new\n", displaced.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun directorySyncRejectsSymlinkDirectory() {
        val root = Files.createTempDirectory("amper-phase54-dir-sync").toFile()
        val real = File(root, "real")
        val link = File(root, "linked")
        try {
            assertTrue(real.mkdir())
            Files.createSymbolicLink(link.toPath(), real.toPath())

            val failure = runCatching {
                DurableJournalIo.syncDirectory(link)
            }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(real.isDirectory)
            assertTrue(Files.isSymbolicLink(link.toPath()))
        } finally {
            Files.deleteIfExists(link.toPath())
            root.deleteRecursively()
        }
    }

    @Test
    fun durableTreeCreationRejectsSymlinkAncestor() {
        val root = Files.createTempDirectory("amper-phase54-tree-link").toFile()
        val real = File(root, "real")
        val alias = File(root, "state")
        val requested = File(alias, "nested")
        try {
            assertTrue(real.mkdir())
            Files.createSymbolicLink(alias.toPath(), real.toPath())

            val failure = runCatching {
                DurableJournalIo.ensureDirectoryExistsDurably(requested)
            }.exceptionOrNull()

            assertNotNull(failure)
            assertFalse(File(real, "nested").exists())
            assertTrue(Files.isSymbolicLink(alias.toPath()))
        } finally {
            Files.deleteIfExists(alias.toPath())
            root.deleteRecursively()
        }
    }

    @Test
    fun durableFileCreateRejectsSymlinkLeafWithoutTouchingVictim() {
        val dir = Files.createTempDirectory("amper-phase54-file-link").toFile()
        val victim = File(dir, "victim")
        val link = File(dir, "memory.journal")
        try {
            victim.writeText("victim-data\n")
            Files.createSymbolicLink(link.toPath(), victim.toPath())

            val failure = runCatching {
                DurableJournalIo.ensureFileExistsDurably(link)
            }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals("victim-data\n", victim.readText())
            assertTrue(Files.isSymbolicLink(link.toPath()))
        } finally {
            Files.deleteIfExists(link.toPath())
            dir.deleteRecursively()
        }
    }
}
