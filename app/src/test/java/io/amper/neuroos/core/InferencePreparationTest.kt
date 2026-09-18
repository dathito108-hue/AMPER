package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class InferencePreparationTest {
    private val reasoning = CapabilityId("reasoning")
    private val coding = CapabilityId("coding")

    @Test
    fun preparationSkipsNonPreparableBackendAndNeverInfers() {
        val model = installed("m", setOf(reasoning))
        var legacyInferCalls = 0
        var prepareCalls = 0
        var preparableInferCalls = 0
        val legacy = object : ManagedInferenceBackend {
            override val id: String = "legacy"
            override fun supports(model: InstalledModel): Boolean = true
            override fun health(): BackendHealth = BackendHealth(BackendState.READY)
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
                InferenceCost(128, 2, 2048)
            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> {
                legacyInferCalls += 1
                return Result.success(InferenceResponse(model.descriptor.id, id, "legacy"))
            }
        }
        val preparable = object : PreparableInferenceBackend {
            override val id: String = "preparable"
            override fun supports(model: InstalledModel): Boolean = true
            override fun health(): BackendHealth = BackendHealth(BackendState.READY)
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
                InferenceCost(128, 2, 2048)
            override fun prepare(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<BackendPreparationResult> {
                prepareCalls += 1
                return Result.success(BackendPreparationResult(sessionReused = false))
            }
            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> {
                preparableInferCalls += 1
                return Result.success(InferenceResponse(model.descriptor.id, id, "prepared"))
            }
        }
        val runtime = runtime(listOf(model), listOf(legacy, preparable), ResourceBudget(memoryMb = 512))

        val prepared = runtime.prepare(InferenceRequest("warm model")).getOrThrow()

        assertEquals("preparable", prepared.backendId)
        assertEquals(ModelId("m"), prepared.modelId)
        assertFalse(prepared.sessionReused)
        assertEquals(1, prepareCalls)
        assertEquals(0, legacyInferCalls)
        assertEquals(0, preparableInferCalls)
    }

    @Test
    fun preparationFallsThroughToSecondModelThatHasPreparableBackend() {
        val first = installed("a-first", setOf(reasoning))
        val second = installed("b-second", setOf(reasoning))
        val legacy = specificLegacyBackend("legacy", "a-first")
        val preparable = specificPreparableBackend("warmable", "b-second", memoryMb = 128)
        val runtime = runtime(
            listOf(first, second),
            listOf(legacy, preparable),
            ResourceBudget(memoryMb = 512)
        )

        val prepared = runtime.prepare(InferenceRequest("warm an executable route")).getOrThrow()

        assertEquals(ModelId("b-second"), prepared.modelId)
        assertEquals("warmable", prepared.backendId)
    }

    @Test
    fun preparationRespectsResourceBudgetBeforeBackendPrepare() {
        val model = installed("m", setOf(reasoning))
        var prepareCalls = 0
        val backend = object : PreparableInferenceBackend {
            override val id: String = "too-large"
            override fun supports(model: InstalledModel): Boolean = true
            override fun health(): BackendHealth = BackendHealth(BackendState.READY)
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
                InferenceCost(1_024, 2, 2048)
            override fun prepare(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<BackendPreparationResult> {
                prepareCalls += 1
                return Result.success(BackendPreparationResult(false))
            }
            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> = Result.success(
                InferenceResponse(model.descriptor.id, id, "unused")
            )
        }
        val runtime = runtime(listOf(model), listOf(backend), ResourceBudget(memoryMb = 256))

        val result = runtime.prepare(InferenceRequest("too large"))

        assertTrue(result.isFailure)
        assertEquals(0, prepareCalls)
    }

    @Test
    fun preparationBindsPlannerSelectedCapabilityProfile() {
        val model = installed("specialist", setOf(reasoning, coding))
        var seenCapabilities: Set<CapabilityId> = emptySet()
        val backend = object : PreparableInferenceBackend {
            override val id: String = "specialist-backend"
            override fun supports(model: InstalledModel): Boolean = true
            override fun health(): BackendHealth = BackendHealth(BackendState.READY)
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
                InferenceCost(128, 2, 2048)
            override fun prepare(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<BackendPreparationResult> {
                seenCapabilities = request.requiredCapabilities
                return Result.success(BackendPreparationResult(sessionReused = true))
            }
            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> = Result.success(
                InferenceResponse(model.descriptor.id, id, "unused")
            )
        }
        val runtime = runtime(listOf(model), listOf(backend), ResourceBudget(memoryMb = 512))
        val specialist = setOf(reasoning, coding)
        val request = InferenceRequest(
            prompt = "prepare coding specialist",
            requiredCapabilities = setOf(reasoning),
            preferredCapabilityProfiles = listOf(specialist)
        )

        val prepared = runtime.prepare(request).getOrThrow()

        assertEquals(specialist, prepared.selectedCapabilities)
        assertEquals(specialist, seenCapabilities)
        assertTrue(prepared.sessionReused)
    }

    @Test
    fun preparationFailureSurfacesWithoutFallingIntoInference() {
        val model = installed("m", setOf(reasoning))
        var inferCalls = 0
        val backend = object : PreparableInferenceBackend {
            override val id: String = "failing-prepare"
            override fun supports(model: InstalledModel): Boolean = true
            override fun health(): BackendHealth = BackendHealth(BackendState.READY)
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
                InferenceCost(128, 2, 2048)
            override fun prepare(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<BackendPreparationResult> = Result.failure(
                IllegalStateException("native load failed")
            )
            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> {
                inferCalls += 1
                return Result.success(InferenceResponse(model.descriptor.id, id, "must not run"))
            }
        }
        val runtime = runtime(listOf(model), listOf(backend), ResourceBudget(memoryMb = 512))

        val result = runtime.prepare(InferenceRequest("warm model"))

        assertTrue(result.isFailure)
        assertEquals(0, inferCalls)
    }

    @Test
    fun cancelledPreparationReleasesLeaseAndNeverPublishesPreparedHandoff() {
        val model = installed("m", setOf(reasoning))
        var legacyInferCalls = 0
        var prepareCalls = 0
        var preparedInferCalls = 0
        var cancelNext = true

        val legacy = object : ManagedInferenceBackend {
            override val id: String = "legacy-first"
            override fun supports(model: InstalledModel): Boolean = true
            override fun health(): BackendHealth = BackendHealth(BackendState.READY)
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
                InferenceCost(64, 1, 2_048)

            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> {
                legacyInferCalls += 1
                return Result.success(
                    InferenceResponse(model.descriptor.id, id, "legacy")
                )
            }
        }

        val preparable = object : CancellablePreparableInferenceBackend {
            override val id: String = "cancellable-prepare"
            override fun supports(model: InstalledModel): Boolean = true
            override fun health(): BackendHealth = BackendHealth(BackendState.READY)
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
                InferenceCost(128, 1, 2_048)

            override fun prepare(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest,
                cancellation: InferenceCancellationSignal
            ): Result<BackendPreparationResult> = runCatching {
                prepareCalls += 1
                if (cancelNext) {
                    cancelNext = false
                    cancellation.cancel()
                }
                cancellation.throwIfCancelled()
                BackendPreparationResult(sessionReused = false)
            }

            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> {
                preparedInferCalls += 1
                return Result.success(
                    InferenceResponse(model.descriptor.id, id, "prepared")
                )
            }
        }

        val runtime = runtime(
            listOf(model),
            listOf(legacy, preparable),
            ResourceBudget(maxConcurrentAgents = 1, memoryMb = 512)
        )
        val cancelledSignal = InferenceCancellationSignal()

        val cancelled = runtime.prepare(
            InferenceRequest("cancel preparation"),
            cancelledSignal
        )

        assertTrue(cancelled.isFailure)
        assertTrue(cancelled.exceptionOrNull() is InferenceCancelledException)
        assertEquals(1, prepareCalls)

        val afterCancellation = runtime.infer(
            InferenceRequest("no cancelled handoff")
        ).getOrThrow()
        assertEquals("legacy-first", afterCancellation.backendId)
        assertEquals(1, legacyInferCalls)
        assertEquals(0, preparedInferCalls)

        val fresh = runtime.prepare(
            InferenceRequest("fresh preparation"),
            InferenceCancellationSignal()
        ).getOrThrow()
        assertEquals("cancellable-prepare", fresh.backendId)
        assertEquals(2, prepareCalls)
    }

    @Test
    fun ordinaryInferenceStillAllowsNonPreparableBackend() {
        val model = installed("m", setOf(reasoning))
        var legacyInferCalls = 0
        var prepareCalls = 0
        val legacy = object : ManagedInferenceBackend {
            override val id: String = "legacy-first"
            override fun supports(model: InstalledModel): Boolean = true
            override fun health(): BackendHealth = BackendHealth(BackendState.READY)
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
                InferenceCost(128, 2, 2048)
            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> {
                legacyInferCalls += 1
                return Result.success(InferenceResponse(model.descriptor.id, id, "legacy"))
            }
        }
        val preparable = object : PreparableInferenceBackend {
            override val id: String = "preparable-second"
            override fun supports(model: InstalledModel): Boolean = true
            override fun health(): BackendHealth = BackendHealth(BackendState.READY)
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
                InferenceCost(128, 2, 2048)
            override fun prepare(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<BackendPreparationResult> {
                prepareCalls += 1
                return Result.success(BackendPreparationResult(false))
            }
            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> = Result.success(
                InferenceResponse(model.descriptor.id, id, "preparable")
            )
        }
        val runtime = runtime(listOf(model), listOf(legacy, preparable), ResourceBudget(memoryMb = 512))

        val response = runtime.infer(InferenceRequest("normal inference")).getOrThrow()

        assertEquals("legacy-first", response.backendId)
        assertEquals(1, legacyInferCalls)
        assertEquals(0, prepareCalls)
    }

    private fun runtime(
        installedModels: List<InstalledModel>,
        backendList: List<InferenceBackend>,
        budget: ResourceBudget
    ): TitanCortexRuntime {
        val models = InMemoryModelRegistry()
        val catalog = InMemoryInstalledModelCatalog()
        installedModels.forEach {
            models.register(it.descriptor)
            catalog.put(it)
        }
        val allowed = installedModels.associateBy { it.descriptor.id }
        val resolver = object : ModelArtifactResolver {
            override fun resolve(model: InstalledModel): ModelArtifactSource? =
                allowed[model.descriptor.id]?.let { StaticSource(it.locator, it.lengthBytes) }
        }
        val registry = InferenceBackendRegistry().apply {
            backendList.forEach(::register)
        }
        return TitanCortexRuntime(
            models = models,
            catalog = catalog,
            artifacts = resolver,
            backends = registry,
            governor = MobileResourceGovernor(budget)
        )
    }

    private fun installed(id: String, capabilities: Set<CapabilityId>): InstalledModel {
        val descriptor = ModelDescriptor(
            id = ModelId(id),
            format = "gguf",
            capabilities = capabilities,
            local = true
        )
        return InstalledModel(
            descriptor = descriptor,
            displayName = "$id.gguf",
            locator = "memory://$id.gguf",
            lengthBytes = 128L * 1024L * 1024L,
            sha256 = "a".repeat(64),
            ggufVersion = 3L,
            tensorCount = 1UL,
            metadataKeyValueCount = 1UL
        )
    }

    private fun specificLegacyBackend(id: String, supportedModel: String): ManagedInferenceBackend =
        object : ManagedInferenceBackend {
            override val id: String = id
            override fun supports(model: InstalledModel): Boolean = model.descriptor.id.value == supportedModel
            override fun health(): BackendHealth = BackendHealth(BackendState.READY)
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
                InferenceCost(128, 2, 2048)
            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> = Result.success(
                InferenceResponse(model.descriptor.id, id, "legacy")
            )
        }

    private fun specificPreparableBackend(
        id: String,
        supportedModel: String,
        memoryMb: Int
    ): PreparableInferenceBackend = object : PreparableInferenceBackend {
        override val id: String = id
        override fun supports(model: InstalledModel): Boolean = model.descriptor.id.value == supportedModel
        override fun health(): BackendHealth = BackendHealth(BackendState.READY)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(memoryMb, 2, 2048)
        override fun prepare(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<BackendPreparationResult> = Result.success(
            BackendPreparationResult(sessionReused = false)
        )
        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> = Result.success(
            InferenceResponse(model.descriptor.id, id, "prepared")
        )
    }

    private class StaticSource(
        override val locator: String,
        override val lengthBytes: Long?
    ) : ModelArtifactSource {
        override val displayName: String = "prepare.gguf"
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }
}
