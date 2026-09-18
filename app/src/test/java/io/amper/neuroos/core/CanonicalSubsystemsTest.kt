package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalSubsystemsTest {
    @Test
    fun capabilityRegistryPrefersAvailableLocalHighPriorityProvider() {
        val capability = CapabilityId("vision")
        val registry = InMemoryCapabilityRegistry().apply {
            register(CapabilityProviderDescriptor(
                id = ProviderId("remote"),
                name = "remote vision",
                capabilities = setOf(capability),
                priority = 100,
                local = false
            ))
            register(CapabilityProviderDescriptor(
                id = ProviderId("local"),
                name = "local vision",
                capabilities = setOf(capability),
                priority = 10,
                local = true
            ))
        }
        assertEquals("local", registry.route(capability)?.id?.value)
    }

    @Test
    fun perceptionPublishesToWorkspaceAndWorldWithProvenance() {
        val workspace = InMemoryWorkspace()
        val world = CanonicalWorldModel()
        val bus = CanonicalPerceptionBus(workspace, world)
        bus.ingest(Percept(
            modality = PerceptionModality.SCREEN,
            payload = "visible application state",
            salience = 0.8,
            provenance = Provenance(source = "screen", producer = "screen-adapter", confidence = 0.9)
        ))
        assertEquals(1, workspace.snapshot().size)
        assertEquals(1, world.size())
        assertEquals("screen", world.query("visible").first().provenance.source)
    }

    @Test
    fun learningConsolidationPreservesEvidenceLineage() {
        val memory = InMemoryMemoryOs()
        val learner = EvidenceLearningConsolidator(memory)
        repeat(2) { index ->
            learner.record(LearningEpisode(
                topic = "routing",
                observation = "model route $index succeeded",
                outcomeScore = 0.8,
                provenance = Provenance(source = "runtime", producer = "test", confidence = 0.95)
            ))
        }
        val result = learner.consolidate("routing", minEvidence = 2)
        assertEquals(2, result.evidenceCount)
        assertNotNull(result.memory)
        assertEquals(2, result.memory!!.provenance.parents.size)
        assertTrue(memory.recall("routing").any { it.kind == "consolidated-pattern" })
    }
}
