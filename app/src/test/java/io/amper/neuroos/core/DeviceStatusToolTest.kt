package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStatusToolTest {
    private val snapshot = DeviceStatusSnapshot(
        batteryPercent = 73,
        charging = true,
        availableMemoryMb = 2048,
        lowMemory = false,
        appStorageFreeMb = 8192,
        appStorageTotalMb = 32768,
        thermalStatus = 1,
        processors = 8,
        sdkInt = 35,
        supportedAbis = listOf("arm64-v8a", "armeabi-v7a")
    )

    @Test
    fun providerReturnsBoundedNonIdentifyingStatus() {
        val provider = DeviceStatusToolProvider(DeviceStatusSource { snapshot })
        val output = provider.execute("summary").getOrThrow()

        assertTrue(output.contains("battery_percent=73"))
        assertTrue(output.contains("memory_available_mb=2048"))
        assertTrue(output.contains("thermal_status=1"))
        assertTrue(output.contains("abis=arm64-v8a,armeabi-v7a"))
        assertFalse(output.contains("serial", ignoreCase = true))
        assertFalse(output.contains("android_id", ignoreCase = true))
        assertEquals(ToolSideEffect.READ_ONLY, provider.descriptor.sideEffect)
    }

    @Test
    fun providerRejectsUnsupportedRequests() {
        val provider = DeviceStatusToolProvider(DeviceStatusSource { snapshot })
        assertTrue(provider.execute("imei").isFailure)
    }

    @Test
    fun runtimeActionLoopPersistsToolOutcomeIntoSameSovereignMemory() {
        val runtime = AmperRuntime.reference()
        val registry = InMemoryToolRegistry().also {
            it.register(DeviceStatusToolProvider(DeviceStatusSource { snapshot }))
        }
        val audit = InMemoryToolAuditLog()
        val fabric = AuditedToolFabric(
            gate = DenyByDefaultAuthorityGate(setOf(DeviceStatusToolContract.capability)),
            registry = registry,
            audit = audit
        )
        val loop = runtime.actionLoop(registry, fabric)
        val modelOutput = """
            <AMPER_ACTION_V1>
            capability=device.status.read
            reason=Answer the user's request using current device resource status
            input=summary
            </AMPER_ACTION_V1>
        """.trimIndent()

        val outcome = loop.evaluateModelOutput(modelOutput)
        assertEquals(ActionStatus.EXECUTED, outcome.status)
        assertEquals(1, audit.snapshot().size)
        assertTrue(
            runtime.context.capture("device.status.read").memories
                .any { it.kind == "tool-action" && it.content.contains("device.status.read") }
        )
    }
}
