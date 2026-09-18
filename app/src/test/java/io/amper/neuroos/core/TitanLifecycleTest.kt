package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TitanLifecycleTest {
    @Test
    fun unloadAllReleasesEveryInstalledModelThroughManagedBackends() {
        val registry = InMemoryModelRegistry()
        val catalog = InMemoryInstalledModelCatalog()
        val backendRegistry = InferenceBackendRegistry()
        val unloaded = mutableListOf<ModelId>()

        val backend = object : ManagedInferenceBackend {
            override val id: String = "managed"
            override fun supports(model: InstalledModel): Boolean = true
            override fun health() = BackendHealth(BackendState.READY)
            override fun estimate(model: InstalledModel, request: InferenceRequest) = InferenceCost()
            override fun infer(model: InstalledModel, source: ModelArtifactSource, request: InferenceRequest) =
                Result.success(InferenceResponse(model.descriptor.id, id, "ok"))
            override fun unload(modelId: ModelId): Result<Unit> {
                unloaded += modelId
                return Result.success(Unit)
            }
        }
        backendRegistry.register(backend)

        listOf("alpha", "beta").forEach { name ->
            val descriptor = ModelDescriptor(
                id = ModelId(name),
                format = "gguf",
                capabilities = setOf(CapabilityId("reasoning")),
                local = true
            )
            registry.register(descriptor)
            catalog.put(
                InstalledModel(
                    descriptor = descriptor,
                    displayName = "$name.gguf",
                    locator = "/tmp/$name.gguf",
                    lengthBytes = 1,
                    sha256 = name,
                    ggufVersion = 3,
                    tensorCount = 1UL,
                    metadataKeyValueCount = 1UL
                )
            )
        }

        val titan = TitanCortexRuntime(
            models = registry,
            catalog = catalog,
            artifacts = LocatorArtifactResolver(emptyList()),
            backends = backendRegistry
        )

        assertTrue(titan.unloadAll().isSuccess)
        assertEquals(setOf(ModelId("alpha"), ModelId("beta")), unloaded.toSet())
    }

    @Test
    fun inferenceResponseDefaultsToColdSession() {
        val response = InferenceResponse(ModelId("m"), "b", "text")
        assertEquals(false, response.sessionReused)
        assertEquals(null, response.tokensPerSecond)
    }
}
