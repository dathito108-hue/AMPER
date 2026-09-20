package io.amper.neuroos.core.v2

import io.amper.neuroos.core.AmiArchitectureId
import io.amper.neuroos.core.AmiContainerIndex
import io.amper.neuroos.core.AmiExecutionProfile
import io.amper.neuroos.core.AmiHardwareFeature
import io.amper.neuroos.core.AmiManifest
import io.amper.neuroos.core.AmiPrecisionPolicy
import io.amper.neuroos.core.AmiSectionDescriptor
import io.amper.neuroos.core.AmiSectionType
import io.amper.neuroos.core.AmiSourceFormat
import io.amper.neuroos.core.AmiSourceLineage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ami2CompilationPlannerTest {
    @Test
    fun legacyContainerHashDoesNotChangeFoundationSemantics() {
        val first = Ami2CompilationPlanner.fromLegacyAmi1(
            index = legacyIndex(),
            legacyContainerSha256 = "1".repeat(64),
            preservedChatTemplate = "{{ messages }}<|assistant|>"
        )
        val second = Ami2CompilationPlanner.fromLegacyAmi1(
            index = legacyIndex(),
            legacyContainerSha256 = "2".repeat(64),
            preservedChatTemplate = "{{ messages }}<|assistant|>"
        )

        assertEquals(first.foundation, second.foundation)
        assertTrue(first.migrationEvidence != second.migrationEvidence)
        assertEquals(
            Ami2FoundationContract.mandatoryArtifacts,
            first.artifacts
        )
        assertFalse(Ami2ArtifactRole.DEVICE_PACK in first.artifacts)
    }

    @Test
    fun chatProtocolIsPartOfCanonicalSemanticIdentity() {
        val first = Ami2CompilationPlanner.fromLegacyAmi1(
            index = legacyIndex(),
            legacyContainerSha256 = "1".repeat(64),
            preservedChatTemplate = "template-a"
        )
        val second = Ami2CompilationPlanner.fromLegacyAmi1(
            index = legacyIndex(),
            legacyContainerSha256 = "1".repeat(64),
            preservedChatTemplate = "template-b"
        )

        assertTrue(first.foundation.chatProtocolSha256 != second.foundation.chatProtocolSha256)
        assertTrue(first.foundation.semanticSha256 != second.foundation.semanticSha256)
    }

    @Test
    fun legacyExecutionProfilesCannotChangeAmi2FoundationIdentity() {
        val first = Ami2CompilationPlanner.fromLegacyAmi1(
            index = legacyIndex(executionProfileDigest = "1".repeat(64)),
            legacyContainerSha256 = "7".repeat(64),
            preservedChatTemplate = null
        )
        val second = Ami2CompilationPlanner.fromLegacyAmi1(
            index = legacyIndex(executionProfileDigest = "2".repeat(64)),
            legacyContainerSha256 = "8".repeat(64),
            preservedChatTemplate = null
        )

        assertEquals(first.foundation, second.foundation)
        assertEquals(
            Ami2CompilationPlanner.FALLBACK_CHAT_PROTOCOL,
            Ami2CompilationPlanner.FALLBACK_CHAT_PROTOCOL
        )
    }

    @Test
    fun unsupportedLegacySourceLineageFailsClosed() {
        val result = runCatching {
            Ami2CompilationPlanner.fromLegacyAmi1(
                index = legacyIndex(sourceFormat = AmiSourceFormat.OTHER),
                legacyContainerSha256 = "1".repeat(64),
                preservedChatTemplate = null
            )
        }

        assertTrue(result.isFailure)
    }

    private fun legacyIndex(
        sourceFormat: AmiSourceFormat = AmiSourceFormat.GGUF,
        executionProfileDigest: String? = null
    ): AmiContainerIndex {
        val sections = mutableListOf(
            section(AmiSectionType.MANIFEST, 8_192L, 64L, 64, "a"),
            section(AmiSectionType.TOKENIZER, 8_256L, 64L, 64, "b"),
            section(AmiSectionType.GRAPH_IR, 8_320L, 64L, 64, "c"),
            section(AmiSectionType.TENSOR_INDEX, 8_384L, 64L, 64, "d"),
            section(AmiSectionType.FOUNDATION_WEIGHTS, 12_288L, 4_096L, 4_096, "e"),
            section(AmiSectionType.INTEGRITY, 16_384L, 64L, 64, "f")
        )
        val profiles = mutableListOf<AmiExecutionProfile>()

        if (executionProfileDigest != null) {
            sections += AmiSectionDescriptor(
                type = AmiSectionType.EXECUTION_PROFILE,
                offset = 20_480L,
                length = 4_096L,
                alignmentBytes = 4_096,
                sha256 = executionProfileDigest,
                profileId = 1
            )
            profiles += AmiExecutionProfile(
                profileId = 1,
                requiredFeatures = setOf(AmiHardwareFeature.ARM64, AmiHardwareFeature.NEON),
                precisionPolicy = AmiPrecisionPolicy.SOURCE_EXACT,
                contextTier = 2_048,
                preferredThreads = 4
            )
        }

        return AmiContainerIndex(
            manifest = AmiManifest(
                architecture = AmiArchitectureId("llama"),
                tensorCount = 128,
                vocabularySize = 32_000,
                source = AmiSourceLineage(
                    sourceFormat = sourceFormat,
                    sourceSha256 = "0".repeat(64),
                    sourceByteLength = 1_048_576L,
                    sourcePrecisionPreserved = true
                ),
                canonicalPrecisionPolicy = AmiPrecisionPolicy.SOURCE_EXACT
            ),
            sections = sections,
            executionProfiles = profiles
        )
    }

    private fun section(
        type: AmiSectionType,
        offset: Long,
        length: Long,
        alignment: Int,
        digestNibble: String
    ) = AmiSectionDescriptor(
        type = type,
        offset = offset,
        length = length,
        alignmentBytes = alignment,
        sha256 = digestNibble.repeat(64)
    )
}
