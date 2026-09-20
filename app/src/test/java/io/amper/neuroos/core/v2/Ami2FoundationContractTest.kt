package io.amper.neuroos.core.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ami2FoundationContractTest {
    @Test
    fun ami2IsSingleFoundationAndGgufIsWeightImportOnly() {
        assertEquals("AMI2", Ami2FoundationContract.magicAscii)
        assertEquals(2, Ami2FoundationContract.majorVersion)
        assertEquals(1, Ami2FoundationContract.foundationSlots)
        assertEquals(
            setOf(Ami2ImportSource.GGUF_WEIGHTS),
            Ami2MigrationContract.acceptedWeightImportSources
        )
        assertFalse(Ami2MigrationContract.ggufDirectProductionRuntimeAllowed)
        assertFalse(Ami2MigrationContract.multipleFoundationRoutingAllowed)
    }

    @Test
    fun devicePackCannotChangeFoundationSemanticIdentity() {
        val foundation = foundation()
        val compatible = Ami2DevicePack(
            packId = "arm64-neon",
            foundationId = foundation.foundationId,
            foundationSemanticSha256 = foundation.semanticSha256,
            packSha256 = "8".repeat(64),
            requiredFeatures = setOf(Ami2HardwareFeature.ARM64, Ami2HardwareFeature.NEON)
        )

        val bundle = Ami2FoundationBundle(
            foundation = foundation,
            artifacts = Ami2FoundationContract.mandatoryArtifacts + Ami2ArtifactRole.DEVICE_PACK,
            devicePacks = listOf(compatible)
        )
        assertEquals(1, bundle.devicePacks.size)

        val incompatible = compatible.copy(foundationSemanticSha256 = "9".repeat(64))
        val failure = runCatching {
            Ami2FoundationBundle(
                foundation = foundation,
                artifacts = Ami2FoundationContract.mandatoryArtifacts + Ami2ArtifactRole.DEVICE_PACK,
                devicePacks = listOf(incompatible)
            )
        }
        assertTrue(failure.isFailure)
    }

    @Test
    fun migrationBoundaryTargetsAmi2AndAmne2Only() {
        assertEquals("AMI2", Ami2MigrationContract.productionFormat)
        assertEquals("AMNE2", Ami2MigrationContract.productionExecutionEngine)
        assertFalse(Ami2MigrationContract.legacyAmi1DirectProductionRuntimeAllowed)
        assertEquals(
            setOf(Ami2ImportSource.LEGACY_AMI1),
            Ami2MigrationContract.acceptedLegacyMigrationSources
        )
    }

    private fun foundation() = Ami2FoundationIdentity(
        foundationId = "amper-foundation",
        architectureId = "llama",
        lineage = Ami2SourceLineage(
            source = Ami2ImportSource.GGUF_WEIGHTS,
            sourceSha256 = "1".repeat(64),
            sourceByteLength = 1024L
        ),
        tokenizerSha256 = "2".repeat(64),
        chatProtocolSha256 = "3".repeat(64),
        logicalGraphSha256 = "4".repeat(64),
        tensorIndexSha256 = "5".repeat(64),
        canonicalWeightsSha256 = "6".repeat(64),
        semanticSha256 = "7".repeat(64),
        tensorCount = 128,
        vocabularySize = 32_000
    )
}
