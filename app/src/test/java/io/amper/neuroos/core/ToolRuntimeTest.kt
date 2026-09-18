package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRuntimeTest {
    private class EchoProvider : ToolProvider {
        override val descriptor = ToolDescriptor(
            id = ToolId("echo"),
            name = "Echo",
            capability = CapabilityId("echo"),
            sideEffect = ToolSideEffect.READ_ONLY
        )
        override fun execute(input: String): Result<String> = Result.success("echo:$input")
    }

    @Test
    fun deniedToolCallIsAuditedAndNotExecuted() {
        val registry = InMemoryToolRegistry().apply { register(EchoProvider()) }
        val audit = InMemoryToolAuditLog()
        val fabric = AuditedToolFabric(DenyByDefaultAuthorityGate(), registry, audit)
        assertTrue(fabric.invoke(CapabilityId("echo"), "hello", "test denied").isFailure)
        val entry = audit.snapshot().single()
        assertFalse(entry.authorized)
        assertFalse(entry.success)
    }

    @Test
    fun explicitlyGrantedToolCallExecutesAndIsAudited() {
        val capability = CapabilityId("echo")
        val registry = InMemoryToolRegistry().apply { register(EchoProvider()) }
        val audit = InMemoryToolAuditLog()
        val fabric = AuditedToolFabric(
            DenyByDefaultAuthorityGate(setOf(capability)),
            registry,
            audit
        )
        val result = fabric.invoke(capability, "hello", "explicit test grant")
        assertEquals("echo:hello", result.getOrThrow())
        val entry = audit.snapshot().single()
        assertTrue(entry.authorized)
        assertTrue(entry.success)
        assertEquals("echo", entry.toolId?.value)
    }
}
