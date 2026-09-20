package io.amper.neuroos.core.v2

import io.amper.neuroos.core.AmiTestFixtures
import io.amper.neuroos.core.ByteArrayModelArtifactSource
import io.amper.neuroos.core.GgufToAmiCompiler
import io.amper.neuroos.core.InstalledModel
import io.amper.neuroos.core.ModelArtifactResolver
import io.amper.neuroos.core.ModelDescriptor
import io.amper.neuroos.core.ModelId
import io.amper.neuroos.core.TitanCapabilities
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAmi2CompilationServiceTest {
    @Test
    fun newInstalledGgufPublishesDirectCanonicalAmi2AndReusesVerifiedCache() {
        val root = Files.createTempDirectory("amper-phase629-ami2").toFile()
        val bytes = AmiTestFixtures.compilerReadyGguf(
            tensorBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        )
        val model = installed(bytes)
        val source = ByteArrayModelArtifactSource(bytes, displayName = model.displayName)
        val service = AndroidAmi2CompilationService(
            rootDir = root,
            modelArtifacts = resolverFor(model, source)
        )

        try {
            val first = service.compileDirect(model).getOrThrow()
            val second = service.compileDirect(model).getOrThrow()

            assertTrue(first.file.exists())
            assertEquals(first.file.canonicalFile, second.file.canonicalFile)
            assertEquals(first.semanticSha256, second.semanticSha256)
            assertEquals(model.sha256, first.loaded.bundle.foundation.lineage.sourceSha256)
            assertEquals(
                Ami2ImportSource.GGUF_WEIGHTS,
                first.loaded.bundle.foundation.lineage.source
            )
            assertNull(first.loaded.migrationEvidence)
            assertFalse(first.migratedFromLegacyAmi1)
            assertEquals(first.semanticSha256, service.existing(model)?.semanticSha256)
        } finally {
            root.walkBottomUp().forEach { file ->
                file.setWritable(true)
                file.delete()
            }
        }
    }

    @Test
    fun legacyAmi1UsesExplicitMigrationRouteAndRetainsEvidence() {
        val root = Files.createTempDirectory("amper-phase629-legacy").toFile()
        val bytes = AmiTestFixtures.compilerReadyGguf()
        val model = installed(bytes)
        val legacy = File.createTempFile("amper-phase629-source-", ".ami")

        try {
            GgufToAmiCompiler().compile(
                ByteArrayModelArtifactSource(bytes),
                legacy
            ).getOrThrow()

            val service = AndroidAmi2CompilationService(
                rootDir = root,
                modelArtifacts = resolverFor(
                    model,
                    ByteArrayModelArtifactSource(bytes)
                )
            )
            val stored = service.migrateLegacy(model, legacy).getOrThrow()

            assertTrue(stored.migratedFromLegacyAmi1)
            assertTrue(stored.loaded.migrationEvidence != null)
            assertEquals(model.sha256, stored.loaded.bundle.foundation.lineage.sourceSha256)
            assertEquals(
                stored.loaded.migrationEvidence?.legacyAmi1Sha256,
                service.existing(model)?.loaded?.migrationEvidence?.legacyAmi1Sha256
            )
        } finally {
            legacy.setWritable(true)
            legacy.delete()
            File(legacy.parentFile, legacy.name + ".partial").delete()
            root.walkBottomUp().forEach { file ->
                file.setWritable(true)
                file.delete()
            }
        }
    }

    private fun resolverFor(
        model: InstalledModel,
        source: ByteArrayModelArtifactSource
    ): ModelArtifactResolver = object : ModelArtifactResolver {
        override fun resolve(modelToResolve: InstalledModel) =
            source.takeIf { modelToResolve.descriptor.id == model.descriptor.id }
    }

    private fun installed(bytes: ByteArray): InstalledModel = InstalledModel(
        descriptor = ModelDescriptor(
            id = ModelId("phase629"),
            format = "gguf",
            capabilities = setOf(TitanCapabilities.REASONING),
            local = true
        ),
        displayName = "phase629.gguf",
        locator = "memory://phase629.gguf",
        lengthBytes = bytes.size.toLong(),
        sha256 = sha256(bytes),
        ggufVersion = 3L,
        tensorCount = 1UL,
        metadataKeyValueCount = 3UL,
        installedAtEpochMs = 1L
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
