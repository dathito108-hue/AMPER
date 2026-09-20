package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmperMobileIntelligenceFormatTest {
    private val digest = "a".repeat(64)

    private fun manifest() = AmiManifest(
        architecture = AmiArchitectureId("llama"),
        tensorCount = 320,
        vocabularySize = 32000,
        source = AmiSourceLineage(
            sourceFormat = AmiSourceFormat.GGUF,
            sourceSha256 = digest,
            sourceByteLength = 1_500_000_000L,
            sourcePrecisionPreserved = true
        )
    )

    private fun section(
        type: AmiSectionType,
        page: Long,
        pages: Long = 1,
        profileId: Int = 0
    ) = AmiSectionDescriptor(
        type = type,
        offset = page * AmperMobileIntelligenceFormat.PAGE_ALIGNMENT_BYTES,
        length = pages * AmperMobileIntelligenceFormat.PAGE_ALIGNMENT_BYTES,
        alignmentBytes = if (
            type == AmiSectionType.FOUNDATION_WEIGHTS ||
            type == AmiSectionType.EXECUTION_PROFILE ||
            type == AmiSectionType.ADAPTATION_DELTA
        ) {
            AmperMobileIntelligenceFormat.PAGE_ALIGNMENT_BYTES
        } else {
            64
        },
        sha256 = digest,
        profileId = profileId
    )

    private fun mandatorySections(extra: List<AmiSectionDescriptor> = emptyList()) = listOf(
        section(AmiSectionType.MANIFEST, 1),
        section(AmiSectionType.TOKENIZER, 2),
        section(AmiSectionType.GRAPH_IR, 3),
        section(AmiSectionType.TENSOR_INDEX, 4),
        section(AmiSectionType.FOUNDATION_WEIGHTS, 5, pages = 4),
        section(AmiSectionType.INTEGRITY, 9)
    ) + extra

    @Test
    fun amiV1RequiresCanonicalFoundationWeights() {
        val index = AmiContainerIndex(
            manifest = manifest(),
            sections = mandatorySections()
        )

        assertEquals(
            1,
            index.sections.count { it.type == AmiSectionType.FOUNDATION_WEIGHTS }
        )
        assertEquals(AmiPrecisionPolicy.SOURCE_EXACT, index.manifest.canonicalPrecisionPolicy)
        assertTrue(index.manifest.source.sourcePrecisionPreserved)
    }

    @Test(expected = IllegalArgumentException::class)
    fun executionProfileCanNeverStandInForMissingFoundationWeights() {
        AmiContainerIndex(
            manifest = manifest(),
            sections = mandatorySections()
                .filterNot { it.type == AmiSectionType.FOUNDATION_WEIGHTS } +
                section(AmiSectionType.EXECUTION_PROFILE, 10, profileId = 1),
            executionProfiles = listOf(
                AmiExecutionProfile(
                    profileId = 1,
                    requiredFeatures = setOf(
                        AmiHardwareFeature.ARM64,
                        AmiHardwareFeature.NEON
                    ),
                    precisionPolicy = AmiPrecisionPolicy.SOURCE_EXACT,
                    contextTier = 2048,
                    preferredThreads = 4
                )
            )
        )
    }

    @Test
    fun hardwareProfileIsOptionalAccelerationLayerOverCanonicalIntelligence() {
        val profile = AmiExecutionProfile(
            profileId = 1,
            requiredFeatures = setOf(
                AmiHardwareFeature.ARM64,
                AmiHardwareFeature.NEON,
                AmiHardwareFeature.DOTPROD
            ),
            precisionPolicy = AmiPrecisionPolicy.SOURCE_EXACT,
            contextTier = 2048,
            preferredThreads = 4
        )
        val index = AmiContainerIndex(
            manifest = manifest(),
            sections = mandatorySections(
                listOf(section(AmiSectionType.EXECUTION_PROFILE, 10, pages = 2, profileId = 1))
            ),
            executionProfiles = listOf(profile)
        )

        assertEquals(profile, index.executionProfiles.single())
        assertEquals(
            1,
            index.sections.count { it.type == AmiSectionType.FOUNDATION_WEIGHTS }
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun overlappingSectionsFailClosedBeforeNativeMapping() {
        val sections = mandatorySections().toMutableList()
        sections[1] = AmiSectionDescriptor(
            type = AmiSectionType.TOKENIZER,
            offset = 4096L,
            length = 8192L,
            alignmentBytes = 64,
            sha256 = digest
        )
        AmiContainerIndex(manifest(), sections)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sourceExactModeRejectsLossyFoundationDeclaration() {
        AmiManifest(
            architecture = AmiArchitectureId("qwen2"),
            tensorCount = 100,
            vocabularySize = 1000,
            source = AmiSourceLineage(
                sourceFormat = AmiSourceFormat.GGUF,
                sourceSha256 = digest,
                sourceByteLength = 1000,
                sourcePrecisionPreserved = false
            ),
            canonicalPrecisionPolicy = AmiPrecisionPolicy.SOURCE_EXACT
        )
    }
}
