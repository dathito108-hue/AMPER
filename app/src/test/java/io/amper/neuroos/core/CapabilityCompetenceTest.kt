package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityCompetenceTest {
    @Test
    fun governedOutcomesBuildDurableDescriptiveCompetenceWithoutMisclassifyingAuthority() {
        val memory = InMemoryMemoryOs()
        var now = 1_000L
        val model = MemoryBackedCapabilityCompetenceModel(memory) { now++ }
        val capability = CapabilityId("device.read")
        val proposal = ActionProposal(
            capability = capability,
            reason = "read device state",
            input = "battery"
        )

        model.observe(
            ActionOutcome(
                status = ActionStatus.EXECUTED,
                proposal = proposal,
                toolId = ToolId("device-status"),
                sideEffect = ToolSideEffect.READ_ONLY,
                output = "ok"
            )
        )
        model.observe(
            ActionOutcome(
                status = ActionStatus.FAILED,
                proposal = proposal,
                toolId = ToolId("device-status"),
                sideEffect = ToolSideEffect.READ_ONLY,
                detail = "provider failed"
            )
        )
        model.observe(
            ActionOutcome(
                status = ActionStatus.DENIED,
                proposal = proposal,
                toolId = ToolId("device-status"),
                sideEffect = ToolSideEffect.READ_ONLY,
                detail = "authority denied"
            )
        )
        model.observe(
            ActionOutcome(
                status = ActionStatus.REQUIRES_CONFIRMATION,
                proposal = proposal,
                toolId = ToolId("device-status"),
                sideEffect = ToolSideEffect.LOCAL_STATE
            )
        )

        val snapshot = requireNotNull(model.snapshot(capability))
        assertEquals(1, snapshot.executed)
        assertEquals(1, snapshot.failed)
        assertEquals(1, snapshot.denied)
        assertEquals(1, snapshot.requiresConfirmation)
        assertEquals(2, snapshot.executionAttempts)
        assertEquals(0.5, snapshot.executionSuccessRate!!, 0.0001)
        assertEquals(2.0 / 6.0, snapshot.evidenceConfidence, 0.0001)

        // A fresh model instance reconstructs the same live aggregate from Memory OS.
        val reloaded = MemoryBackedCapabilityCompetenceModel(memory)
        val durable = requireNotNull(reloaded.snapshot(capability))
        assertEquals(snapshot.executed, durable.executed)
        assertEquals(snapshot.failed, durable.failed)
        assertEquals(snapshot.denied, durable.denied)
        assertEquals(snapshot.requiresConfirmation, durable.requiresConfirmation)
        assertTrue(reloaded.all(8).any { it.capability == capability })
    }

    @Test
    fun boundedIndexReturnsMostRecentlyObservedCapabilities() {
        val memory = InMemoryMemoryOs()
        var now = 10L
        val model = MemoryBackedCapabilityCompetenceModel(memory) { now++ }

        repeat(70) { index ->
            val capability = CapabilityId("skill.$index")
            model.observe(
                ActionOutcome(
                    status = ActionStatus.EXECUTED,
                    proposal = ActionProposal(
                        capability = capability,
                        reason = "test",
                        input = "x"
                    ),
                    toolId = ToolId("tool-$index"),
                    sideEffect = ToolSideEffect.READ_ONLY,
                    output = "ok"
                )
            )
        }

        val recent = model.all(64)
        assertEquals(64, recent.size)
        assertEquals(CapabilityId("skill.69"), recent.first().capability)
        assertTrue(recent.none { it.capability == CapabilityId("skill.0") })
    }
}
