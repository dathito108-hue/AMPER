package io.amper.neuroos.core.v2

import io.amper.neuroos.core.AmiHardwareFeature
import io.amper.neuroos.core.AmiHardwareSnapshot
import io.amper.neuroos.core.AmiTestFixtures
import io.amper.neuroos.core.AmneKernelRegistry
import io.amper.neuroos.core.ByteArrayModelArtifactSource
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Amne2ExecutionAdmissionTest {
    @Test
    fun verifiedAmi2FoundationAdmitsAgainstExistingReferenceKernelRegistry() {
        withCanonicalAmi2 { file, loaded ->
            val admission = Amne2ExecutionAdmission(
                registryProvider = { AmneKernelRegistry() }
            ).admit(
                artifactFile = file,
                hardware = arm64Hardware()
            ).getOrThrow()

            assertEquals(loaded.bundle.foundation.foundationId, admission.foundationId)
            assertEquals(loaded.bundle.foundation.semanticSha256, admission.semanticSha256)
            assertEquals(loaded.fileSha256, admission.artifactSha256)
            assertEquals(
                loaded.bundle.foundation.lineage.sourceSha256,
                admission.sourceSha256
            )
            assertEquals(
                Amne2ExecutionTier.REFERENCE_VALIDATION,
                admission.executionTier
            )
            assertTrue(admission.acceleratedPrimitives.isEmpty())
            assertTrue("amne-reference-cpu-v1" in admission.registeredKernelBackends)
            assertTrue(Ami2HardwareFeature.ARM64 in admission.hardware.features)
            assertTrue(Ami2HardwareFeature.NEON in admission.hardware.features)
        }
    }

    @Test
    fun nonArm64HardwareCannotEnterAmne2MobileExecution() {
        withCanonicalAmi2 { file, _ ->
            val result = Amne2ExecutionAdmission(
                registryProvider = { AmneKernelRegistry() }
            ).admit(
                artifactFile = file,
                hardware = AmiHardwareSnapshot(
                    features = emptySet(),
                    logicalProcessors = 4,
                    memoryClassMb = 256,
                    lowRamDevice = false
                )
            )

            assertTrue(result.isFailure)
        }
    }

    @Test
    fun tamperedAmi2IsRejectedBeforeKernelAdmission() {
        withCanonicalAmi2 { file, loaded ->
            val tokenizer = loaded.sections.single {
                it.role == Ami2ArtifactRole.TOKENIZER
            }
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(tokenizer.offset)
                val original = raf.read()
                require(original >= 0)
                raf.seek(tokenizer.offset)
                raf.write(original xor 0x01)
            }

            val result = Amne2ExecutionAdmission(
                registryProvider = { AmneKernelRegistry() }
            ).admit(file, arm64Hardware())

            assertTrue(result.isFailure)
        }
    }

    @Test
    fun legacyAmi1MigrationEvidenceDoesNotCreateSecondExecutionIdentity() {
        withCanonicalAmi2 { file, loaded ->
            val admission = Amne2ExecutionAdmission(
                registryProvider = { AmneKernelRegistry() }
            ).admit(file, arm64Hardware()).getOrThrow()

            assertEquals(
                loaded.bundle.foundation.semanticSha256,
                admission.semanticSha256
            )
            assertEquals(1, Ami2FoundationContract.foundationSlots)
        }
    }

    private fun arm64Hardware() = AmiHardwareSnapshot(
        features = setOf(
            AmiHardwareFeature.ARM64,
            AmiHardwareFeature.NEON,
            AmiHardwareFeature.DOTPROD,
            AmiHardwareFeature.FP16
        ),
        logicalProcessors = 8,
        memoryClassMb = 512,
        lowRamDevice = false
    )

    private inline fun withCanonicalAmi2(
        block: (File, Ami2LoadedBinaryArtifact) -> Unit
    ) {
        val output = File.createTempFile("amper-phase630-", ".ami")
        try {
            output.delete()
            GgufToAmi2StreamingCompiler().compile(
                source = ByteArrayModelArtifactSource(
                    AmiTestFixtures.compilerReadyGguf()
                ),
                destination = output
            ).getOrThrow()
            val loaded = Ami2CanonicalBinaryReader().read(output).getOrThrow()
            block(output, loaded)
        } finally {
            output.setWritable(true)
            output.delete()
            File(output.parentFile, output.name + ".partial").delete()
        }
    }
}
