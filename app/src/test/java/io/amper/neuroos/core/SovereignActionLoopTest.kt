package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignActionLoopTest {
    private class RecordingProvider(
        capability: String,
        sideEffect: ToolSideEffect
    ) : ToolProvider {
        override val descriptor = ToolDescriptor(
            id = ToolId("tool-$capability"),
            name = "Test $capability",
            capability = CapabilityId(capability),
            sideEffect = sideEffect
        )
        val inputs = mutableListOf<String>()
        override fun execute(input: String): Result<String> {
            inputs += input
            return Result.success("result:$input")
        }
    }

    private data class Fixture(
        val loop: SovereignActionLoop,
        val provider: RecordingProvider,
        val audit: InMemoryToolAuditLog,
        val memory: InMemoryMemoryOs
    )

    private fun fixture(
        capability: String,
        sideEffect: ToolSideEffect,
        granted: Boolean
    ): Fixture {
        val provider = RecordingProvider(capability, sideEffect)
        val registry = InMemoryToolRegistry().also { it.register(provider) }
        val audit = InMemoryToolAuditLog()
        val grantedCapabilities = if (granted) setOf(CapabilityId(capability)) else emptySet()
        val fabric = AuditedToolFabric(
            gate = DenyByDefaultAuthorityGate(grantedCapabilities),
            registry = registry,
            audit = audit
        )
        val memory = InMemoryMemoryOs()
        return Fixture(
            loop = SovereignActionLoop(
                registry = registry,
                fabric = fabric,
                memory = memory,
                workspace = InMemoryWorkspace()
            ),
            provider = provider,
            audit = audit,
            memory = memory
        )
    }

    private fun envelope(capability: String, reason: String = "read requested data", input: String = "scope=today") = """
        <AMPER_ACTION_V1>
        capability=$capability
        reason=$reason
        input=$input
        </AMPER_ACTION_V1>
    """.trimIndent()

    @Test
    fun ordinaryAssistantTextNeverInvokesTools() {
        val f = fixture("device.read", ToolSideEffect.READ_ONLY, granted = true)
        val outcome = f.loop.evaluateModelOutput("Here is a normal answer without any action request.")

        assertEquals(ActionStatus.NO_ACTION, outcome.status)
        assertTrue(f.provider.inputs.isEmpty())
        assertTrue(f.audit.snapshot().isEmpty())
    }

    @Test
    fun quotedOrSurroundedEnvelopeIsMalformedAndNeverExecutes() {
        val f = fixture("device.read", ToolSideEffect.READ_ONLY, granted = true)
        val outcome = f.loop.evaluateModelOutput("Example only:\n${envelope("device.read")}")

        assertEquals(ActionStatus.MALFORMED, outcome.status)
        assertTrue(f.provider.inputs.isEmpty())
        assertTrue(f.audit.snapshot().isEmpty())
    }

    @Test
    fun grantedReadOnlyActionCanExecuteThroughAuditedFabric() {
        val f = fixture("device.read", ToolSideEffect.READ_ONLY, granted = true)
        val outcome = f.loop.evaluateModelOutput(envelope("device.read", input = "scope=battery"))

        assertEquals(ActionStatus.EXECUTED, outcome.status)
        assertEquals(listOf("scope=battery"), f.provider.inputs)
        assertEquals("result:scope=battery", outcome.output)
        assertEquals(1, f.audit.snapshot().size)
        assertTrue(f.audit.snapshot().single().authorized)
        assertTrue(f.audit.snapshot().single().success)
        assertTrue(f.memory.recall("device.read", 8).any { it.kind == "tool-action" })
    }

    @Test
    fun deniedReadOnlyActionFailsClosedAndIsAudited() {
        val f = fixture("device.read", ToolSideEffect.READ_ONLY, granted = false)
        val outcome = f.loop.evaluateModelOutput(envelope("device.read"))

        assertEquals(ActionStatus.DENIED, outcome.status)
        assertTrue(f.provider.inputs.isEmpty())
        assertEquals(1, f.audit.snapshot().size)
        assertFalse(f.audit.snapshot().single().authorized)
    }

    @Test
    fun sideEffectNeverRunsBeforeExplicitApproval() {
        val f = fixture("network.send", ToolSideEffect.EXTERNAL, granted = true)
        val proposed = f.loop.evaluateModelOutput(
            envelope("network.send", reason = "send user-approved payload", input = "payload=hello")
        )

        assertEquals(ActionStatus.REQUIRES_CONFIRMATION, proposed.status)
        assertTrue(f.provider.inputs.isEmpty())
        assertTrue(f.audit.snapshot().isEmpty())

        val approved = f.loop.approve(proposed.proposal!!)
        assertEquals(ActionStatus.EXECUTED, approved.status)
        assertEquals(listOf("payload=hello"), f.provider.inputs)
        assertEquals(1, f.audit.snapshot().size)
    }

    @Test
    fun governedToolOutcomeUpdatesDescriptiveCompetenceLedger() {
        val capability = CapabilityId("device.read")
        val provider = RecordingProvider(capability.value, ToolSideEffect.READ_ONLY)
        val registry = InMemoryToolRegistry().also { it.register(provider) }
        val audit = InMemoryToolAuditLog()
        val memory = InMemoryMemoryOs()
        val competence = MemoryBackedCapabilityCompetenceModel(memory) { 1234L }
        val loop = SovereignActionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(setOf(capability)),
                registry = registry,
                audit = audit
            ),
            memory = memory,
            workspace = InMemoryWorkspace(),
            competence = competence
        )

        val outcome = loop.evaluateModelOutput(
            envelope(capability.value, input = "scope=battery")
        )

        assertEquals(ActionStatus.EXECUTED, outcome.status)
        val snapshot = requireNotNull(competence.snapshot(capability))
        assertEquals(1, snapshot.executed)
        assertEquals(0, snapshot.failed)
        assertEquals(1, snapshot.executionAttempts)
        assertEquals(1.0, snapshot.executionSuccessRate!!, 0.0001)
    }

    @Test
    fun unknownCapabilityNeverExecutes() {
        val registry = InMemoryToolRegistry()
        val audit = InMemoryToolAuditLog()
        val memory = InMemoryMemoryOs()
        val loop = SovereignActionLoop(
            registry = registry,
            fabric = AuditedToolFabric(DenyByDefaultAuthorityGate(), registry, audit),
            memory = memory,
            workspace = InMemoryWorkspace()
        )

        val outcome = loop.evaluateModelOutput(envelope("missing.capability"))
        assertEquals(ActionStatus.UNAVAILABLE, outcome.status)
        assertTrue(audit.snapshot().isEmpty())
    }
}
