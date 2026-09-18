package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolManifestProtocolTest {
    @Test
    fun protocolAdvertisesOnlyWhitelistedTypedManifests() {
        val device = DeviceStatusToolProvider(DeviceStatusSource {
            DeviceStatusSnapshot(
                batteryPercent = 50,
                charging = false,
                availableMemoryMb = 1024,
                lowMemory = false,
                appStorageFreeMb = 2048,
                appStorageTotalMb = 4096,
                thermalStatus = 0,
                processors = 8,
                sdkInt = 35,
                supportedAbis = listOf("arm64-v8a")
            )
        })
        val sovereign = SovereignStatusToolProvider(SovereignStatusSource {
            SovereignStatusSnapshot(emptyList(), emptyList(), ResourceBudget())
        })
        val registry = InMemoryToolRegistry().also {
            it.register(device)
            it.register(sovereign)
        }

        val prompt = TitanActionProtocol.instructions(
            capabilities = listOf(DeviceStatusToolContract.capability),
            descriptors = registry.descriptors()
        )

        assertTrue(prompt.contains("TOOL device.status.read"))
        assertTrue(prompt.contains("input_values=status|summary"))
        assertTrue(prompt.contains("max_input_chars=16"))
        assertFalse(prompt.contains("TOOL sovereign.status.read"))
    }

    @Test
    fun actionLoopRejectsInputOutsideDeclaredContractBeforeFabricInvocation() {
        val memory = InMemoryMemoryOs()
        val workspace = InMemoryWorkspace()
        val registry = InMemoryToolRegistry().also {
            it.register(DeviceStatusToolProvider(DeviceStatusSource {
                error("provider must not execute for malformed input")
            }))
        }
        val audit = InMemoryToolAuditLog()
        val loop = SovereignActionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(setOf(DeviceStatusToolContract.capability)),
                registry = registry,
                audit = audit
            ),
            memory = memory,
            workspace = workspace
        )

        val outcome = loop.evaluate(
            ActionProposal(
                capability = DeviceStatusToolContract.capability,
                reason = "Read current device status",
                input = "imei"
            )
        )

        assertEquals(ActionStatus.MALFORMED, outcome.status)
        assertTrue(outcome.detail.orEmpty().contains("outside declared contract"))
        assertEquals(0, audit.snapshot().size)
        assertTrue(memory.recall("device.status.read", 4).any { it.kind == "tool-action" })
    }
}
