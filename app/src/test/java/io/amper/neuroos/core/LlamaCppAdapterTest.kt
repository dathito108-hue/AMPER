package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaCppAdapterTest {
    private val model = InstalledModel(
        ModelDescriptor(ModelId("brain"), "gguf", setOf(CapabilityId("reasoning")), true),
        "brain.gguf", "memory://brain", 1024L, "abc", 3, 1u, 1u
    )
    private val source = ByteArrayModelArtifactSource(ByteArray(8), "brain.gguf", "memory://brain")

    @Test
    fun llamaAdapterStreamsAndAggregatesTokens() {
        val adapter = LlamaCppAdapter(object : LlamaCppEngine {
            override fun health() = BackendHealth(BackendState.READY, hardwareAcceleration = true)
            override fun estimate(model: InstalledModel, request: InferenceRequest) = InferenceCost(256, 4, 4096)
            override fun supports(source: ModelArtifactSource) = true
            override fun generate(model: InstalledModel, source: ModelArtifactSource, request: InferenceRequest, onToken: (String) -> Unit): Result<Unit> {
                listOf("hello", " ", "world").forEach(onToken)
                return Result.success(Unit)
            }
        })
        val chunks = mutableListOf<InferenceChunk>()
        adapter.generateStream(model, source, InferenceRequest("hi"), chunks::add).getOrThrow()
        assertEquals("hello world", chunks.filterNot { it.finished }.joinToString("") { it.text })
        assertTrue(chunks.last().finished)
        assertEquals("hello world", adapter.generate(model, source, InferenceRequest("hi")).getOrThrow())
    }

    @Test
    fun optionalNativeProbeFailsClosedWithoutCrashing() {
        val probe = OptionalNativeLibraryProbe("missing") { throw UnsatisfiedLinkError("not bundled") }
        val health = probe.health()
        assertEquals(BackendState.UNAVAILABLE, health.state)
        assertTrue(health.detail.contains("not bundled"))
    }
}
