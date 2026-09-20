package io.amper.neuroos.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmiTensorGraphAndHardwareProfileTest {
    @Test
    fun decodedTensorGraphBindsIndexToFoundation() {
        val output = File.createTempFile("amper-graph-", ".ami")
        try {
            GgufToAmiCompiler().compile(
                ByteArrayModelArtifactSource(
                    AmiTestFixtures.compilerReadyGguf(
                        tensorBytes = byteArrayOf(1, 2, 3, 4)
                    )
                ),
                output
            ).getOrThrow()
            val loaded = AmiBinaryReader().read(output).getOrThrow()

            val graph = AmiTensorGraphReader().read(loaded).getOrThrow()
            val tensor = graph.tensor("token_embd.weight")!!

            assertEquals(AmiArchitectureId("llama"), graph.architecture)
            assertEquals(1, graph.tensors.size)
            assertEquals(listOf(1UL), tensor.dimensions)
            assertEquals(0L, tensor.sourceEncodingType)
            assertEquals(0L, tensor.foundationOffset)
            assertEquals(4L, tensor.storageBytes)
            assertEquals(1UL, tensor.elementCount)
            assertEquals(
                AmiSectionType.FOUNDATION_WEIGHTS,
                graph.foundationSection.type
            )
        } finally {
            output.setWritable(true)
            output.delete()
            File(output.parentFile, output.name + ".partial").delete()
        }
    }

    @Test
    fun selectorUsesSmallestAdequateContextThenSupportedAcceleration() {
        val hardware = AmiHardwareSnapshot(
            features = setOf(
                AmiHardwareFeature.ARM64,
                AmiHardwareFeature.NEON,
                AmiHardwareFeature.DOTPROD
            ),
            logicalProcessors = 8,
            memoryClassMb = 512,
            lowRamDevice = false
        )
        val profiles = listOf(
            profile(1, 1024, setOf(AmiHardwareFeature.ARM64, AmiHardwareFeature.NEON)),
            profile(2, 2048, setOf(AmiHardwareFeature.ARM64, AmiHardwareFeature.NEON)),
            profile(
                3,
                2048,
                setOf(
                    AmiHardwareFeature.ARM64,
                    AmiHardwareFeature.NEON,
                    AmiHardwareFeature.DOTPROD
                )
            ),
            profile(
                4,
                2048,
                setOf(
                    AmiHardwareFeature.ARM64,
                    AmiHardwareFeature.NEON,
                    AmiHardwareFeature.I8MM
                )
            )
        )

        assertEquals(
            1,
            AmiExecutionProfileSelector.select(
                profiles,
                hardware,
                requiredContextTokens = 512
            )?.profileId
        )
        assertEquals(
            3,
            AmiExecutionProfileSelector.select(
                profiles,
                hardware,
                requiredContextTokens = 1500
            )?.profileId
        )
    }

    @Test
    fun selectorRejectsProfilesWhoseHardwareFeaturesAreUnavailable() {
        val hardware = AmiHardwareSnapshot(
            features = setOf(
                AmiHardwareFeature.ARM64,
                AmiHardwareFeature.NEON
            ),
            logicalProcessors = 4,
            memoryClassMb = 256,
            lowRamDevice = false
        )
        val profiles = listOf(
            profile(
                1,
                2048,
                setOf(
                    AmiHardwareFeature.ARM64,
                    AmiHardwareFeature.NEON,
                    AmiHardwareFeature.I8MM
                )
            )
        )

        assertNull(
            AmiExecutionProfileSelector.select(
                profiles,
                hardware,
                requiredContextTokens = 1024
            )
        )
    }

    private fun profile(
        id: Int,
        context: Int,
        features: Set<AmiHardwareFeature>
    ) = AmiExecutionProfile(
        profileId = id,
        requiredFeatures = features,
        precisionPolicy = AmiPrecisionPolicy.SOURCE_EXACT,
        contextTier = context,
        preferredThreads = 4
    )
}
