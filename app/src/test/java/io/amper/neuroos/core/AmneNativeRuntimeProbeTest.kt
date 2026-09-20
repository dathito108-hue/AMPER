package io.amper.neuroos.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmneNativeRuntimeProbeTest {
    @Test
    fun canonicalJvmBuildDoesNotPretendNativeBackendIsPackaged() {
        assertFalse(AmneNativeRuntimeProbe.isPackaged())
    }

    @Test
    fun qualificationFailsClosedWhenNativeBackendIsAbsent() {
        val result = AmneNativeRuntimeProbe.qualify()

        assertTrue(result.isFailure)
    }
}
