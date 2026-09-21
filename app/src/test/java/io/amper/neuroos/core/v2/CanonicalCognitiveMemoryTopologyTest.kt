package io.amper.neuroos.core.v2

import io.amper.neuroos.core.AmperRuntime
import io.amper.neuroos.core.InMemoryMemoryOs
import io.amper.neuroos.core.InMemoryWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalCognitiveMemoryTopologyTest {
    @Test
    fun canonicalTopologyContainsExactlyFourMemoryDomains() {
        val topology = CanonicalCognitiveMemoryTopology.bind(
            InMemoryMemoryOs(),
            InMemoryWorkspace()
        )

        assertEquals(CognitiveMemoryDomain.entries.toSet(), topology.domains.map { it.domain }.toSet())
        assertEquals(4, topology.domains.size)
        assertEquals(1, topology.durableMemoryCount)
        assertEquals(0, topology.modelBackendSpecificSilos)
    }

    @Test
    fun allDurableDomainsShareOneMemoryOsAndCarryNoAuthority() {
        val memory = InMemoryMemoryOs()
        val workspace = InMemoryWorkspace()
        val topology = CanonicalCognitiveMemoryTopology.bind(memory, workspace)

        assertTrue(topology.usesDurableMemory(memory))
        assertFalse(topology.usesDurableMemory(InMemoryMemoryOs()))
        assertTrue(topology.usesWorkingWorkspace(workspace))
        assertFalse(topology.usesWorkingWorkspace(InMemoryWorkspace()))
        assertTrue(topology.domains.none { it.authorityBearing })
        assertTrue(
            topology.domains.all {
                it.durableBacking == CognitiveMemoryDomainContract.DURABLE_BACKING
            }
        )
    }

    @Test
    fun workingMemoryIsTransientWorkspaceWithDurableContinuity() {
        val topology = CanonicalCognitiveMemoryTopology.bind(
            InMemoryMemoryOs(),
            InMemoryWorkspace()
        )
        val working = topology.domains.single { it.domain == CognitiveMemoryDomain.WORKING }

        assertEquals(
            CognitiveMemoryDurability.TRANSIENT_WITH_DURABLE_CONTINUITY,
            working.durability
        )
        assertEquals(CognitiveMemoryDomainContract.WORKING_BACKING, working.transientBacking)
        assertTrue("NativeSystem2WorkingStateStore" in working.canonicalOwners)
    }

    @Test
    fun remainingDomainsAreDurableMemoryOsDomains() {
        val topology = CanonicalCognitiveMemoryTopology.bind(
            InMemoryMemoryOs(),
            InMemoryWorkspace()
        )
        topology.domains
            .filterNot { it.domain == CognitiveMemoryDomain.WORKING }
            .forEach {
                assertEquals(CognitiveMemoryDurability.DURABLE, it.durability)
                assertEquals(null, it.transientBacking)
            }
    }

    @Test
    fun runtimePublishesTopologyWithoutASecondMemorySilo() {
        val topology = AmperRuntime.reference().cognitiveMemoryTopology

        assertEquals(1, topology.durableMemoryCount)
        assertEquals(0, topology.modelBackendSpecificSilos)
        assertEquals(4, topology.domains.size)
    }
}
