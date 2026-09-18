package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCapabilityProfileTest {
    @Test
    fun defaultImportedProfileIsReasoningOnly() {
        val profile = ModelCapabilityProfile()

        assertEquals(setOf(TitanCapabilities.REASONING), profile.capabilities)
    }

    @Test
    fun codeGenerationMustBeExplicitlyDeclared() {
        val profile = ModelCapabilityProfile(codeGeneration = true)

        assertEquals(
            setOf(TitanCapabilities.REASONING, TitanCapabilities.CODE_GENERATION),
            profile.capabilities
        )
        assertFalse(TitanCapabilities.PLANNING in profile.capabilities)
    }

    @Test
    fun planningMustBeExplicitlyDeclared() {
        val profile = ModelCapabilityProfile(planning = true)

        assertEquals(
            setOf(TitanCapabilities.REASONING, TitanCapabilities.PLANNING),
            profile.capabilities
        )
        assertFalse(TitanCapabilities.CODE_GENERATION in profile.capabilities)
    }

    @Test
    fun combinedSpecialistProfileRetainsMandatoryReasoning() {
        val profile = ModelCapabilityProfile(codeGeneration = true, planning = true)

        assertTrue(TitanCapabilities.REASONING in profile.capabilities)
        assertTrue(TitanCapabilities.CODE_GENERATION in profile.capabilities)
        assertTrue(TitanCapabilities.PLANNING in profile.capabilities)
        assertEquals(3, profile.capabilities.size)
    }

    @Test
    fun legacyUntypedSpecialistClaimsAreReducedToReasoningBaseline() {
        val legacy = setOf(
            TitanCapabilities.REASONING,
            TitanCapabilities.CODE_GENERATION,
            TitanCapabilities.PLANNING,
            CapabilityId("unverified-specialist")
        )

        val profile = ModelCapabilityProfile.fromLegacyUntyped(legacy)

        assertEquals(setOf(TitanCapabilities.REASONING), profile.capabilities)
    }

    @Test
    fun explicitProfilesDriveExistingSpecialistCandidateSelectionWithoutChangingRegistry() {
        val general = ModelDescriptor(
            id = ModelId("general"),
            format = "gguf",
            capabilities = ModelCapabilityProfile().capabilities,
            local = true
        )
        val coder = ModelDescriptor(
            id = ModelId("coder"),
            format = "gguf",
            capabilities = ModelCapabilityProfile(codeGeneration = true).capabilities,
            local = true
        )
        val registry = InMemoryModelRegistry().apply {
            register(general)
            register(coder)
        }

        assertEquals(
            listOf(ModelId("coder")),
            registry.candidates(
                setOf(TitanCapabilities.REASONING, TitanCapabilities.CODE_GENERATION)
            ).map { it.id }
        )
        assertEquals(
            listOf(ModelId("coder"), ModelId("general")),
            registry.candidates(setOf(TitanCapabilities.REASONING)).map { it.id }
        )
    }
}
