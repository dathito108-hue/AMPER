package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAwareAssistantTurnTest {
    @Test
    fun deviceStatusActionExecutesOnceThenSynthesizesFinalAnswer() {
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
        val prompts = mutableListOf<String>()
        var calls = 0
        val inference = CognitiveInferencePort { request ->
            prompts += request.prompt
            calls += 1
            when (calls) {
                1 -> Result.success(
                    InferenceResponse(
                        text = """
                            <AMPER_ACTION_V1>
                            capability=device.status.read
                            reason=Read current device resources to answer the user
                            input=summary
                            </AMPER_ACTION_V1>
                        """.trimIndent(),
                        modelId = ModelId("test-model"),
                        backendId = "test-backend"
                    )
                )
                2 -> Result.success(
                    InferenceResponse(
                        text = "Battery is 61% and available memory is about 1536 MiB.",
                        modelId = ModelId("test-model"),
                        backendId = "test-backend"
                    )
                )
                else -> error("assistant turn must never perform a third inference pass")
            }
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
        assertEquals(2, final.inferencePasses)
        assertEquals(ActionStatus.EXECUTED, final.actionOutcome?.status)
        assertEquals(2, calls)
        assertEquals(1, audit.snapshot().size)
        assertTrue(prompts.first().contains("device.status.read"))
        assertTrue(prompts.last().contains("battery_percent=61"))
        assertTrue(prompts.last().contains("memory_available_mb=1536"))
        assertTrue(final.response.text.contains("61%"))
    }
}
