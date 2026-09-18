package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitanRuntimeTest {
    @Test
    fun ggufInspectorReadsHeaderAndDigest() {
        val source = ByteArrayModelArtifactSource(validGguf(version = 3, tensors = 42, metadata = 7))
        val inspected = GgufInspector().inspect(source).getOrThrow()
        assertEquals(3L, inspected.header.version)
        assertEquals(42UL, inspected.header.tensorCount)
        assertEquals(7UL, inspected.header.metadataKeyValueCount)
        assertEquals(64, inspected.sha256.length)
        assertTrue(inspected.header.supported)
    }

    @Test
    fun invalidMagicIsRejected() {
        val bytes = validGguf().also { it[0] = 0 }
        assertTrue(GgufInspector().inspect(ByteArrayModelArtifactSource(bytes)).isFailure)
    }

    @Test
    fun unsupportedVersionProbesButCannotInstall() {
        val source = ByteArrayModelArtifactSource(validGguf(version = 99))
        val probe = GgufInspector().probe(source).getOrThrow()
        assertFalse(probe.supported)
        assertTrue(GgufInspector().inspect(source).isFailure)
    }

    @Test
    fun installRegistersModelAndTitanRoutesThroughBackend() {
        val source = ByteArrayModelArtifactSource(validGguf(), displayName = "brain.gguf")
        val catalog = InMemoryInstalledModelCatalog()
        val models = InMemoryModelRegistry()
        val capability = CapabilityId("reasoning")
        val installed = LocalModelInstallService(GgufInspector(), catalog, models)
            .install(ModelId("user-brain"), source, setOf(capability))
            .getOrThrow()
        assertEquals("user-brain", models.route(setOf(capability))?.id?.value)
        assertEquals(installed.sha256, catalog.get(ModelId("user-brain"))?.sha256)

        val backendRegistry = InferenceBackendRegistry().apply {
            register(object : InferenceBackend {
                override val id = "fake-test-backend"
                override fun supports(model: InstalledModel): Boolean = model.descriptor.format == "gguf"
                override fun infer(
                    model: InstalledModel,
                    source: ModelArtifactSource,
                    request: InferenceRequest
                ): Result<InferenceResponse> = Result.success(
                    InferenceResponse(
                        modelId = model.descriptor.id,
                        backendId = id,
                        text = "processed:${request.prompt}"
                    )
                )
            })
        }
        val resolver = object : ModelArtifactResolver {
            override fun resolve(model: InstalledModel): ModelArtifactSource = source
        }
        val titan = TitanCortexRuntime(models, catalog, resolver, backendRegistry)
        val result = titan.infer(InferenceRequest("hello sovereign cortex")).getOrThrow()
        assertEquals("user-brain", result.modelId.value)
        assertEquals("fake-test-backend", result.backendId)
        assertEquals("processed:hello sovereign cortex", result.text)
    }

    private fun validGguf(version: Int = 3, tensors: Long = 1, metadata: Long = 1): ByteArray =
        GgufTestFixtures.validArtifact(
            version = version.toLong(),
            tensorCount = tensors.toULong(),
            metadataCount = metadata.toULong()
        )
}
