package io.amper.neuroos.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalKernelTest {
    @Test
    fun sovereignTickPublishesSelfGoalWorldAgentReflectsRemembersAndRoutes() {
        val report = AmperRuntime.reference().tick("preserve canonical architecture")
        assertEquals("READY", report.kernelState)
        assertEquals("AMPER", report.selfIdentity)
        assertEquals(6, report.workspaceEvents)
        assertEquals(1, report.memoryRecords)
        assertEquals(2, report.activeGoals)
        assertEquals(1, report.worldFacts)
        assertEquals("titan-cortex-contract", report.modelRoute)
    }

    @Test
    fun authorityIsDenyByDefault() {
        val tools = GatedToolFabric(DenyByDefaultAuthorityGate())
        assertTrue(tools.invoke(CapabilityId("network"), "ping", "test").isFailure)
    }

    @Test
    fun persistentMemoryReplaysProvenanceAndTombstones() {
        val file = File.createTempFile("amper-memory-", ".journal")
        try {
            val record = MemoryRecord(
                kind = "semantic",
                content = "canonical memory survives runtime recreation",
                importance = 0.95,
                provenance = Provenance(source = "test", producer = "memory-os", confidence = 0.99)
            )
            PersistentMemoryOs(FileMemoryJournal(file)).remember(record)

            val restored = PersistentMemoryOs(FileMemoryJournal(file))
            assertEquals(1, restored.size())
            assertEquals("test", restored.get(record.id)?.provenance?.source)
            assertNotNull(restored.recall("canonical").firstOrNull())
            assertTrue(restored.forget(record.id))

            val afterDelete = PersistentMemoryOs(FileMemoryJournal(file))
            assertEquals(0, afterDelete.size())
        } finally {
            file.delete()
        }
    }

    @Test
    fun selfModelKeepsCanonicalIdentityAcrossIntentChanges() {
        val self = CanonicalSelfModel()
        self.observeIntent("learn a new capability")
        val first = self.snapshot()
        self.observeIntent("switch model backend")
        val second = self.snapshot()
        assertEquals(first.identity, second.identity)
        assertEquals(first.invariants, second.invariants)
        assertEquals("AMPER", second.identity)
    }

    @Test
    fun dynamicAgentsAreBoundedEphemeralAndModelRouted() {
        val models = InMemoryModelRegistry().apply {
            register(ModelDescriptor(
                id = ModelId("reasoner"),
                format = "test",
                capabilities = setOf(CapabilityId("reasoning")),
                local = true
            ))
        }
        val governor = MobileResourceGovernor(ResourceBudget(maxConcurrentAgents = 1))
        val fabric = EphemeralAgentFabric(governor, models)
        val first = fabric.spawn(AgentSpec(
            role = "reasoner",
            requiredCapabilities = setOf(CapabilityId("reasoning")),
            purpose = "test bounded lease"
        ))
        assertTrue(first.isSuccess)
        assertEquals(1, fabric.active().size)
        assertTrue(fabric.spawn(AgentSpec(
            role = "second",
            requiredCapabilities = setOf(CapabilityId("reasoning")),
            purpose = "must be gated"
        )).isFailure)
        assertTrue(fabric.release(first.getOrThrow().id))
        assertEquals(0, fabric.active().size)
    }
}
