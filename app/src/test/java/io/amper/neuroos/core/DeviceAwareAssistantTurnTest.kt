package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAwareAssistantTurnTest {
    @Test
    fun deviceStatusUsesReflexFastPathWithZeroLlmPasses() {
        val runtime = AmperRuntime.reference()
        val registry = InMemoryToolRegistry().also {
            it.register(
                DeviceStatusToolProvider(
                    DeviceStatusSource {
                        DeviceStatusSnapshot(
                            batteryPercent = 61,
                            charging = false,
                            availableMemoryMb = 1536,
                            lowMemory = false,
                            appStorageFreeMb = 4096,
                            appStorageTotalMb = 16384,
                            thermalStatus = 1,
                            processors = 8,
                            sdkInt = 35,
                            supportedAbis = listOf("arm64-v8a")
                        )
                    }
                )
            )
        }
        val audit = InMemoryToolAuditLog()
        val fabric = AuditedToolFabric(
            gate = DenyByDefaultAuthorityGate(setOf(DeviceStatusToolContract.capability)),
            registry = registry,
            audit = audit
        )
        var calls = 0
        val inference = CognitiveInferencePort {
            calls += 1
            error("high-confidence device status must not call System-2")
        }
        val assistant = SovereignAssistantTurnCoordinator(
            runtime = runtime,
            inference = inference,
            actions = runtime.actionLoop(registry, fabric),
            advertisedCapabilities = setOf(DeviceStatusToolContract.capability),
            maxOutputTokens = 128
        )

        val result = assistant.respond(
            runtime.conversations.primary(),
            "How much battery and RAM do I have right now?"
        ).getOrThrow()

        val final = result as SovereignAssistantTurnResult.Final
        assertEquals(0, final.inferencePasses)
        assertEquals(ActionStatus.EXECUTED, final.actionOutcome?.status)
        assertEquals(0, calls)
        assertEquals(1, audit.snapshot().size)
        assertEquals(ReflexDecisionRuntimeContract.BACKEND_ID, final.response.backendId)
        assertTrue(final.response.text.contains("Battery 61%"))
        assertTrue(final.response.text.contains("RAM available 1536 MB"))
        assertEquals(null, runtime.conversations.latestAssistantModelId(runtime.conversations.primary()))
    }
}
