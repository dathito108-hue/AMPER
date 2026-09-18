package io.amper.neuroos.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionalBackendPackLoaderTest {
    @Test
    fun missingOptionalPackDoesNotBreakCanonicalRuntime() {
        val registry = InferenceBackendRegistry()
        val manager = TitanBackendPackManager(registry)

        val status = OptionalBackendPackLoader.attach("missing.backend.Pack", manager)

        assertFalse(status.available)
        assertTrue(registry.list().isEmpty())
    }
}
