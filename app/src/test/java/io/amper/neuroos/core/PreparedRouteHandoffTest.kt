package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class PreparedRouteHandoffTest {
    private val reasoning = CapabilityId("reasoning")
    private val coding = CapabilityId("coding")
    private val standardRequest = InferenceRequest("prepared handoff")

    @Test
    fun prepareThenInferUsesPreparedRouteEvenWhenUngovernedOrderPrefersLegacyBackend() {
        val model = installed("m")
        val legacy = RecordingLegacyBackend("legacy", memoryMb = 32)
        val prepared = RecordingPreparableBackend("prepared", memoryMb = 128)
        val fixture = fixture(listOf(model), listOf(legacy, prepared), governor = null)

        fixture.runtime.prepare(standardRequest).getOrThrow()
        val response = fixture.runtime.infer(standardRequest).getOrThrow()

        assertEquals("prepared", response.backendId)
        assertEquals(0, legacy.inferCalls)
        assertEquals(1, prepared.prepareCalls)
        assertEquals(1, prepared.inferCalls)
    }

    @Test
    fun preparedHandoffIsOneShot() {
        val model = installed("m")
        val legacy = RecordingLegacyBackend("legacy", memoryMb = 32)
        val prepared = RecordingPreparableBackend("prepared", memoryMb = 128)
        val fixture = fixture(listOf(model), listOf(legacy, prepared), governor = null)

        fixture.runtime.prepare(standardRequest).getOrThrow()
        val first = fixture.runtime.infer(standardRequest).getOrThrow()
        val second = fixture.runtime.infer(standardRequest).getOrThrow()

        assertEquals("prepared", first.backendId)
        assertEquals("legacy", second.backendId)
        assertEquals(1, prepared.inferCalls)
        assertEquals(1, legacy.inferCalls)
    }

    @Test
    fun incompatibleRequestDoesNotConsumePreparedHandoff() {
        val model = installed("m")
        val legacy = RecordingLegacyBackend("legacy", memoryMb = 32)
        val prepared = RecordingPreparableBackend("prepared", memoryMb = 128)
        val fixture = fixture(listOf(model), listOf(legacy, prepared), governor = null)

        fixture.runtime.prepare(standardRequest).getOrThrow()
        val incompatible = fixture.runtime.infer(
            standardRequest.copy(prompt = "different temperature", temperature = 0.8)
        ).getOrThrow()
        val compatible = fixture.runtime.infer(
            standardRequest.copy(prompt = "same prepared shape")
        ).getOrThrow()

        assertEquals("legacy", incompatible.backendId)
        assertEquals("prepared", compatible.backendId)
    }

    @Test
    fun resourceBudgetIsRevalidatedBeforePreparedHandoffCanRun() {
        val model = installed("m")
        val legacy = RecordingLegacyBackend("legacy", memoryMb = 32)
        val prepared = RecordingPreparableBackend("prepared", memoryMb = 128)
        val governor = MutableGovernor(ResourceBudget(memoryMb = 512, thermalClass = 1))
        val fixture = fixture(listOf(model), listOf(legacy, prepared), governor)

        fixture.runtime.prepare(standardRequest).getOrThrow()
        governor.budget = ResourceBudget(memoryMb = 64, thermalClass = 1)
        val response = fixture.runtime.infer(standardRequest).getOrThrow()

        assertEquals("legacy", response.backendId)
        assertEquals(0, prepared.inferCalls)
        assertEquals(1, legacy.inferCalls)
    }

    @Test
    fun changedInstalledIdentityInvalidatesPreparedHandoff() {
        val original = installed("m", sha = "a".repeat(64))
        val replacement = original.copy(sha256 = "b".repeat(64))
        val legacy = RecordingLegacyBackend("legacy", memoryMb = 32)
        val prepared = RecordingPreparableBackend("prepared", memoryMb = 128)
        val fixture = fixture(listOf(original), listOf(legacy, prepared), governor = null)

        fixture.runtime.prepare(standardRequest).getOrThrow()
        fixture.catalog.put(replacement)
        val response = fixture.runtime.infer(standardRequest).getOrThrow()

        assertEquals("legacy", response.backendId)
        assertEquals(0, prepared.inferCalls)
    }

    @Test
    fun unloadClearsPreparedHandoffForThatModel() {
        val model = installed("m")
        val legacy = RecordingLegacyBackend("legacy", memoryMb = 32)
        val prepared = RecordingPreparableBackend("prepared", memoryMb = 128)
        val fixture = fixture(listOf(model), listOf(legacy, prepared), governor = null)

        fixture.runtime.prepare(standardRequest).getOrThrow()
        fixture.runtime.unload(model.descriptor.id).getOrThrow()
        val response = fixture.runtime.infer(standardRequest).getOrThrow()

        assertEquals("legacy", response.backendId)
        assertEquals(0, prepared.inferCalls)
    }

    @Test
    fun handoffInferenceStillAdvancesExplorationCadence() {
        val model = installed("m")
        val first = RecordingPreparableBackend(
            id = "a-first",
            memoryMb = 128,
            tokensPerSecond = 10.0
        )
        val second = RecordingPreparableBackend(
            id = "b-second",
            memoryMb = 128,
            tokensPerSecond = 10.0
        )
        val models = InMemoryModelRegistry().also { it.register(model.descriptor) }
        val catalog = InMemoryInstalledModelCatalog().also { it.put(model) }
        val registry = InferenceBackendRegistry().apply {
            register(first)
            register(second)
        }
        val planner = TitanInferenceRoutePlanner(
            models = models,
            catalog = catalog,
            artifacts = StaticResolver(),
            backends = registry,
            explorationEveryPlanningPasses = 2
        )
        val runtime = TitanCortexRuntime(
            models = models,
            catalog = catalog,
            artifacts = StaticResolver(),
            backends = registry,
            governor = null,
            routePlanner = planner
        )

        runtime.prepare(standardRequest).getOrThrow()
        val handedOff = runtime.infer(standardRequest).getOrThrow()
        val explorationTurn = runtime.infer(standardRequest).getOrThrow()

        assertEquals("a-first", handedOff.backendId)
        assertEquals("b-second", explorationTurn.backendId)
    }

    @Test
    fun specialistCapabilityProfileStillPrecedesPreparedBaselineRoute() {
        val baseline = installed("a-base", capabilities = setOf(reasoning))
        val specialist = installed("b-specialist", capabilities = setOf(reasoning, coding))
        val basePrepared = RecordingPreparableBackend(
            id = "base-prepared",
            memoryMb = 128,
            supportedModels = setOf("a-base")
        )
        val specialistBackend = RecordingLegacyBackend(
            id = "specialist",
            memoryMb = 128,
            supportedModels = setOf("b-specialist")
        )
        val fixture = fixture(
            listOf(baseline, specialist),
            listOf(basePrepared, specialistBackend),
            governor = null
        )

        fixture.runtime.prepare(standardRequest).getOrThrow()
        val specialistProfile = setOf(reasoning, coding)
        val response = fixture.runtime.infer(
            InferenceRequest(
                prompt = "use specialist",
                requiredCapabilities = setOf(reasoning),
                preferredCapabilityProfiles = listOf(specialistProfile)
            )
        ).getOrThrow()

        assertEquals(ModelId("b-specialist"), response.modelId)
        assertEquals("specialist", response.backendId)
    }

    private fun fixture(
        installedModels: List<InstalledModel>,
        backendList: List<InferenceBackend>,
        governor: ResourceGovernor?
    ): RuntimeFixture {
        val models = InMemoryModelRegistry()
        val catalog = InMemoryInstalledModelCatalog()
        installedModels.forEach {
            models.register(it.descriptor)
            catalog.put(it)
        }
        val registry = InferenceBackendRegistry().apply {
            backendList.forEach(::register)
        }
        val resolver = StaticResolver()
        return RuntimeFixture(
            runtime = TitanCortexRuntime(
                models = models,
                catalog = catalog,
                artifacts = resolver,
                backends = registry,
                governor = governor
            ),
            catalog = catalog
        )
    }

    private fun installed(
        id: String,
        capabilities: Set<CapabilityId> = setOf(reasoning),
        sha: String = "a".repeat(64)
    ): InstalledModel {
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
            sha256 = sha,
            ggufVersion = 3L,
            tensorCount = 1UL,
            metadataKeyValueCount = 1UL
        )
    }

    private data class RuntimeFixture(
        val runtime: TitanCortexRuntime,
        val catalog: InMemoryInstalledModelCatalog
    )

    private class MutableGovernor(
        var budget: ResourceBudget
    ) : ResourceGovernor {
        override fun currentBudget(): ResourceBudget = budget
        override fun allows(agentCount: Int): Boolean = agentCount <= budget.maxConcurrentAgents
    }

    private class RecordingLegacyBackend(
        override val id: String,
        private val memoryMb: Int,
        private val supportedModels: Set<String>? = null
    ) : ManagedInferenceBackend {
        var inferCalls: Int = 0

        override fun supports(model: InstalledModel): Boolean =
            supportedModels == null || model.descriptor.id.value in supportedModels

        override fun health(): BackendHealth = BackendHealth(BackendState.READY)

        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(memoryMb, 2, 2048)

        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> {
            inferCalls += 1
            return Result.success(
                InferenceResponse(
                    modelId = model.descriptor.id,
                    backendId = id,
                    text = "legacy:$id"
                )
            )
        }
    }

    private class RecordingPreparableBackend(
        override val id: String,
        private val memoryMb: Int,
        private val supportedModels: Set<String>? = null,
        private val tokensPerSecond: Double? = null
    ) : PreparableInferenceBackend {
        var prepareCalls: Int = 0
        var inferCalls: Int = 0

        override fun supports(model: InstalledModel): Boolean =
            supportedModels == null || model.descriptor.id.value in supportedModels

        override fun health(): BackendHealth = BackendHealth(BackendState.READY)

        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(memoryMb, 2, 2048)

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
            inferCalls += 1
            return Result.success(
                InferenceResponse(
                    modelId = model.descriptor.id,
                    backendId = id,
                    text = "prepared:$id",
                    tokensPerSecond = tokensPerSecond
                )
            )
        }
    }

    private class StaticResolver : ModelArtifactResolver {
        override fun resolve(model: InstalledModel): ModelArtifactSource =
            StaticSource(model.locator, model.lengthBytes)
    }

    private class StaticSource(
        override val locator: String,
        override val lengthBytes: Long?
    ) : ModelArtifactSource {
        override val displayName: String = "handoff.gguf"
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }
}
