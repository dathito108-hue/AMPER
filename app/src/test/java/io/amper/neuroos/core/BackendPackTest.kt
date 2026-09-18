package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BackendPackTest {
    @Test
    fun failingBackendPackDoesNotBreakTitanRegistry() {
        val registry = InferenceBackendRegistry()
        val manager = TitanBackendPackManager(registry)
        val failing = object : TitanBackendPack {
            override val id: String = "broken-native-pack"
            override fun attach(registry: InferenceBackendRegistry): Result<Int> =
                Result.failure(UnsatisfiedLinkError("native runtime unavailable"))
        }

        val status = manager.attach(failing)

        assertFalse(status.available)
        assertEquals(0, status.attachedBackends)
        assertEquals(0, registry.list().size)
    }
}
