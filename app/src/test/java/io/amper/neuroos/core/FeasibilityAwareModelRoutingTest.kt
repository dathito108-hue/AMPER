package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class FeasibilityAwareModelRoutingTest {
    private val reasoning = CapabilityId("reasoning")

    @Test
    fun runtimeSkipsPreferredCandidateWithoutInstalledArtifact() {
        val models = registry("a-preferred", "b-installed")
        val catalog = InMemoryInstalledModelCatalog().apply {
            put(installed("b-installed"))
        }
        val backend = RecordingBackend()
        val titan = runtime(
            models = models,
            catalog = catalog,
            resolver = resolverFor("b-installed"),
            backend = backend
        )

        val response = titan.infer(InferenceRequest("route me")).getOrThrow()

        assertEquals("b-installed", response.modelId.value)
        assertEquals(listOf("b-installed"), backend.inferredModels)
    }

    @Test
    fun runtimeSkipsPreferredCandidateWithUnavailableArtifact() {
        val models = registry("a-missing-source", "b-ready")
        val catalog = catalog("a-missing-source", "b-ready")
        val backend = RecordingBackend()
        val titan = runtime(
            models = models,
            catalog = catalog,
            resolver = resolverFor("b-ready"),
            backend = backend
        )

        val response = titan.infer(InferenceRequest("route me")).getOrThrow()

        assertEquals("b-ready", response.modelId.value)
        assertEquals(listOf("b-ready"), backend.inferredModels)
    }

    @Test
    fun runtimeSkipsCandidateWithoutCompatibleBackend() {
        val models = registry("a-unsupported", "b-supported")
        val catalog = catalog("a-unsupported", "b-supported")
        val backend = RecordingBackend(supportedIds = setOf("b-supported"))
        val titan = runtime(
            models = models,
            catalog = catalog,
            resolver = resolverFor("a-unsupported", "b-supported"),
            backend = backend
        )

        val response = titan.infer(InferenceRequest("route me")).getOrThrow()

        assertEquals("b-supported", response.modelId.value)
        assertEquals(listOf("b-supported"), backend.inferredModels)
    }

    @Test
    fun governorNeverFallsBackToUnbudgetedBackend() {
        val models = registry("brain")
        val catalog = catalog("brain")
        val backend = CostAwareBackend(mapOf("brain" to 1_024))
        val titan = runtime(
            models = models,
            catalog = catalog,
            resolver = resolverFor("brain"),
            backend = backend,
            governor = MobileResourceGovernor(ResourceBudget(memoryMb = 128, thermalClass = 1))
        )

        val result = titan.infer(InferenceRequest("must stay gated"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("no feasible model route") == true)
        assertTrue(backend.inferredModels.isEmpty())
    }

    @Test
    fun governorFallsThroughToSecondModelThatFitsBudget() {
        val models = registry("a-heavy", "b-mobile")
        val catalog = catalog("a-heavy", "b-mobile")
        val backend = CostAwareBackend(
            memoryByModelMb = mapOf(
                "a-heavy" to 1_024,
                "b-mobile" to 256
            )
        )
        val titan = runtime(
            models = models,
            catalog = catalog,
            resolver = resolverFor("a-heavy", "b-mobile"),
            backend = backend,
            governor = MobileResourceGovernor(ResourceBudget(memoryMb = 512, thermalClass = 1))
        )

        val response = titan.infer(InferenceRequest("pick feasible intelligence")).getOrThrow()

        assertEquals("b-mobile", response.modelId.value)
        assertEquals(listOf("b-mobile"), backend.inferredModels)
    }

    @Test
    fun criticalThermalBudgetCannotBeBypassedByFallback() {
        val models = registry("brain")
        val catalog = catalog("brain")
        val backend = CostAwareBackend(mapOf("brain" to 64))
        val titan = runtime(
            models = models,
            catalog = catalog,
            resolver = resolverFor("brain"),
            backend = backend,
            governor = MobileResourceGovernor(ResourceBudget(memoryMb = 4_096, thermalClass = 4))
        )

        val result = titan.infer(InferenceRequest("stay safe"))

        assertTrue(result.isFailure)
        assertTrue(backend.inferredModels.isEmpty())
    }

    @Test
    fun backendExecutionFailureIsNotHiddenBySwitchingModels() {
        val models = registry("a-first", "b-second")
        val catalog = catalog("a-first", "b-second")
        val backend = RecordingBackend(failOn = setOf("a-first"))
        val titan = runtime(
            models = models,
            catalog = catalog,
            resolver = resolverFor("a-first", "b-second"),
            backend = backend
        )

        val result = titan.infer(InferenceRequest("do not mask execution failure"))

        assertTrue(result.isFailure)
        assertEquals(listOf("a-first"), backend.inferredModels)
    }

    @Test
    fun defaultRegistryCandidateOrderRemainsDeterministic() {
        val registry = InMemoryModelRegistry()
        registry.register(descriptor("z-local"))
        registry.register(descriptor("a-local"))
        registry.register(
            ModelDescriptor(
                id = ModelId("contract"),
                format = "contract-only",
                capabilities = setOf(reasoning),
                local = true
            )
        )

        assertEquals(
            listOf("a-local", "z-local", "contract"),
            registry.candidates(setOf(reasoning)).map { it.id.value }
        )
    }

    private fun registry(vararg ids: String): InMemoryModelRegistry =
        InMemoryModelRegistry().also { registry -> ids.forEach { registry.register(descriptor(it)) } }

    private fun catalog(vararg ids: String): InMemoryInstalledModelCatalog =
        InMemoryInstalledModelCatalog().also { catalog -> ids.forEach { catalog.put(installed(it)) } }

    private fun descriptor(id: String): ModelDescriptor = ModelDescriptor(
        id = ModelId(id),
        format = "gguf",
        capabilities = setOf(reasoning),
        local = true
    )

    private fun installed(id: String): InstalledModel = InstalledModel(
        descriptor = descriptor(id),
        displayName = "$id.gguf",
        locator = "memory://$id.gguf",
        lengthBytes = 128L,
        sha256 = "a".repeat(64),
        ggufVersion = 3L,
        tensorCount = 1UL,
        metadataKeyValueCount = 1UL,
        installedAtEpochMs = 1L
    )

    private fun resolverFor(vararg ids: String): ModelArtifactResolver {
        val allowed = ids.toSet()
        return object : ModelArtifactResolver {
            override fun resolve(model: InstalledModel): ModelArtifactSource? =
                if (model.descriptor.id.value in allowed) StaticSource(model.locator) else null
        }
    }

    private fun runtime(
        models: ModelRegistry,
        catalog: InstalledModelCatalog,
        resolver: ModelArtifactResolver,
        backend: InferenceBackend,
        governor: ResourceGovernor? = null
    ): TitanCortexRuntime = TitanCortexRuntime(
        models = models,
        catalog = catalog,
        artifacts = resolver,
        backends = InferenceBackendRegistry().apply { register(backend) },
        governor = governor
    )

    private class StaticSource(override val locator: String) : ModelArtifactSource {
        override val displayName: String = "test.gguf"
        override val lengthBytes: Long? = 128L
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }

    private class RecordingBackend(
        private val supportedIds: Set<String>? = null,
        private val failOn: Set<String> = emptySet()
    ) : InferenceBackend {
        override val id: String = "recording"
        val inferredModels = mutableListOf<String>()

        override fun supports(model: InstalledModel): Boolean =
            supportedIds == null || model.descriptor.id.value in supportedIds

        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> {
            val modelId = model.descriptor.id.value
            inferredModels += modelId
            return if (modelId in failOn) {
                Result.failure(IllegalStateException("backend execution failed for $modelId"))
            } else {
                Result.success(
                    InferenceResponse(
                        modelId = model.descriptor.id,
                        backendId = id,
                        text = "processed:$modelId"
                    )
                )
            }
        }
    }

    private class CostAwareBackend(
        private val memoryByModelMb: Map<String, Int>
    ) : ManagedInferenceBackend {
        override val id: String = "cost-aware"
        val inferredModels = mutableListOf<String>()

        override fun supports(model: InstalledModel): Boolean = true

        override fun health(): BackendHealth = BackendHealth(BackendState.READY)

        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(
                estimatedMemoryMb = memoryByModelMb.getValue(model.descriptor.id.value),
                preferredThreads = 1,
                contextTokens = 2_048
            )

        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> {
            inferredModels += model.descriptor.id.value
            return Result.success(
                InferenceResponse(
                    modelId = model.descriptor.id,
                    backendId = id,
                    text = "processed:${model.descriptor.id.value}"
                )
            )
        }
    }
}
