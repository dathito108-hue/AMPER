package io.amper.neuroos.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexLinearCodecCompatibilityTest {
    @Test
    fun sparseV2RoundTripIsLosslessAndSmallerThanDenseReference() {
        val capabilities = listOf(DeviceStatusToolContract.capability)
        val biases = FloatArray(ReflexLinearModel.CLASS_SLOTS)
        val weights = FloatArray(ReflexLinearModel.PARAMETER_WEIGHTS)
        biases[0] = -0.25f
        biases[1] = 0.75f
        weights[7] = -0.5f
        weights[ReflexLinearModel.FEATURE_DIMENSION + 11] = 1.25f
        val model = ReflexLinearModel(capabilities, biases, weights)

        val encoded = ReflexLinearModelCodec.encode(model)
        val decoded = ReflexLinearModelCodec.decode(encoded)

        assertTrue(encoded.size < ReflexLinearModelCodec.denseReferenceByteCount(model))
        assertEquals(model.capabilities, decoded.capabilities)
        assertTrue(model.biases.contentEquals(decoded.biases))
        assertTrue(model.weights.contentEquals(decoded.weights))
    }

    @Test
    fun decoderStillAcceptsInstalledDenseV1Artifact() {
        val capabilities = listOf(DeviceStatusToolContract.capability)
        val biases = FloatArray(ReflexLinearModel.CLASS_SLOTS)
        val weights = FloatArray(ReflexLinearModel.PARAMETER_WEIGHTS)
        biases[1] = 1.5f
        weights[ReflexLinearModel.FEATURE_DIMENSION + 23] = 2.0f
        val model = ReflexLinearModel(capabilities, biases, weights)

        val legacy = encodeLegacyDenseV1(model)
        val decoded = ReflexLinearModelCodec.decode(legacy)

        assertEquals(model.capabilities, decoded.capabilities)
        assertTrue(model.biases.contentEquals(decoded.biases))
        assertTrue(model.weights.contentEquals(decoded.weights))
    }

    private fun encodeLegacyDenseV1(model: ReflexLinearModel): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(0x41524c31)
            out.writeInt(1)
            out.writeInt(ReflexLinearModel.FEATURE_DIMENSION)
            out.writeInt(ReflexLinearModel.CLASS_SLOTS)
            out.writeInt(model.capabilities.size)
            model.capabilities.forEach { capability ->
                val value = capability.value.toByteArray(StandardCharsets.UTF_8)
                out.writeInt(value.size)
                out.write(value)
            }
            model.biases.forEach(out::writeFloat)
            model.weights.forEach(out::writeFloat)
        }
        return bytes.toByteArray()
    }
}
