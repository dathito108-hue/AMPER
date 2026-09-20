package io.amper.neuroos.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitanRouteObservatoryTest {
    private val reasoning = TitanCapabilities.REASONING
    private val planning = TitanCapabilities.PLANNING

    @Test
    fun preferredModelNotInstalledIsExplainedWhileFallbackRouteRemainsUnchanged() {
        val preferred = descriptor("a-preferred", setOf(reasoning))
        val fallback = descriptor("b-fallback", setOf(reasoning))
        val models = InMemoryModelRegistry().also {
            it.register(preferred)
            it.register(fallback)
        }
        val installedFallback = installed(fallback)
        val catalog = InMemoryInstalledModelCatalog().also { it.put(installedFallback) }
        val observatory = TitanRouteObservatory()
        val planner = TitanInferenceRoutePlanner(
            models = models,
            catalog = catalog,
            artifacts = resolver(),
            backends = InferenceBackendRegistry().also {
                it.register(DiagnosticBackend(mapOf("b-fallback" to 128)))
            },
            observatory = observatory
        )

        val route = planner.plan(
            request = InferenceRequest(
                prompt = "route",
                userPreferredModelId = preferred.id
            ),
            budget = ResourceBudget(memoryMb = 512, thermalClass = 1)
        ).getOrThrow()

        assertEquals(fallback.id, route.descriptor.id)
        val observation = observatory.latest()!!
        assertEquals(fallback.id, observation.selectedModelId)
        assertEquals(preferred.id, observation.preferredModelId)
        assertEquals(TitanRouteSelectionReason.NORMAL_RANKING, observation.selectionReason)
        assertEquals("a-preferred:not-installed", observation.preferredModelOutcome())
        assertTrue(observation.failure == null)
    }

    @Test
    fun preferredModelMemoryRejectionReportsConcreteBackendAdmissionReason() {
        val preferred = descriptor("a-preferred", setOf(reasoning))
        val fallback = descriptor("b-fallback", setOf(reasoning))
        val models = InMemoryModelRegistry().also {
            it.register(preferred)
            it.register(fallback)
        }
        val catalog = InMemoryInstalledModelCatalog().also {
            it.put(installed(preferred))
            it.put(installed(fallback))
        }
        val planner = TitanInferenceRoutePlanner(
            models = models,
            catalog = catalog,
            artifacts = resolver(),
            backends = InferenceBackendRegistry().also {
                it.register(
                    DiagnosticBackend(
                        mapOf(
                            "a-preferred" to 1_024,
                            "b-fallback" to 128
                        )
                    )
                )
            }
        )

        val route = planner.plan(
            request = InferenceRequest(
                prompt = "memory constrained route",
                userPreferredModelId = preferred.id
            ),
            budget = ResourceBudget(memoryMb = 512, thermalClass = 1)
        ).getOrThrow()

        assertEquals(fallback.id, route.descriptor.id)
        val observation = planner.latestObservation()!!
        assertTrue(
            observation.rejected.contains(
                "a-preferred/diagnostic-backend:memory-budget:estimated=1024MiB,budget=512MiB"
            )
        )
        assertEquals(
            "a-preferred/diagnostic-backend:memory-budget:estimated=1024MiB,budget=512MiB",
            observation.preferredModelOutcome()
        )
    }

    @Test
    fun noCapableModelProducesReadOnlyFailureObservation() {
        val model = descriptor("reasoner", setOf(reasoning))
        val models = InMemoryModelRegistry().also { it.register(model) }
        val catalog = InMemoryInstalledModelCatalog().also { it.put(installed(model)) }
        val planner = TitanInferenceRoutePlanner(
            models = models,
            catalog = catalog,
            artifacts = resolver(),
            backends = InferenceBackendRegistry().also {
                it.register(DiagnosticBackend(mapOf("reasoner" to 128)))
            }
        )

        val result = planner.plan(
            request = InferenceRequest(
                prompt = "planning required",
                requiredCapabilities = setOf(planning)
            ),
            budget = ResourceBudget(memoryMb = 512, thermalClass = 1)
        )

        assertTrue(result.isFailure)
        val observation = planner.latestObservation()!!
        assertNull(observation.selectedModelId)
        assertNull(observation.selectionReason)
        assertTrue(observation.failure?.contains("no model satisfies") == true)
        assertTrue(observation.rejected.any { it.endsWith(":no-model") })
        assertEquals(setOf(planning), observation.requiredCapabilities)
    }

    @Test
    fun backendPolicyClassifiesContextAndUnavailableRejectionsWithoutChangingEligibility() {
        val model = installed(descriptor("brain", setOf(reasoning)))
        val unavailable = object : ManagedInferenceBackend {
            override val id: String = "unavailable"
            override fun supports(model: InstalledModel): Boolean = true
            override fun health(): BackendHealth = BackendHealth(BackendState.UNAVAILABLE)
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
                InferenceCost(128, 1, 4_096)
            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> = error("must not execute")
        }

        val unavailableEvaluation = TitanBackendPolicy.evaluate(
            backend = unavailable,
            model = model,
            request = InferenceRequest("hello"),
            budget = ResourceBudget(memoryMb = 512, thermalClass = 1)
        )
        assertTrue(!unavailableEvaluation.eligible)
        assertEquals("backend-unavailable", unavailableEvaluation.rejectionReason)

        val tinyContext = DiagnosticBackend(
            memoryByModelMb = mapOf("brain" to 128),
            contextTokens = 64
        )
        val contextEvaluation = TitanBackendPolicy.evaluate(
            backend = tinyContext,
            model = model,
            request = InferenceRequest(
                prompt = "x".repeat(1_000),
                maxOutputTokens = 128
            ),
            budget = ResourceBudget(memoryMb = 512, thermalClass = 1)
        )
        assertTrue(!contextEvaluation.eligible)
        assertEquals("context-window", contextEvaluation.rejectionReason)
    }

    @Test
    fun observatoryIsBoundedAndNewestFirstIsNotInvented() {
        val observatory = TitanRouteObservatory(maxEntries = 2)
        fun observation(id: String) = TitanRouteObservation(
            purpose = TitanRoutePurpose.INFERENCE,
            requiredCapabilities = setOf(reasoning),
            preferredModelId = null,
            selectedModelId = ModelId(id),
            selectedBackendId = "backend",
            selectedCapabilities = setOf(reasoning),
            selectionReason = TitanRouteSelectionReason.NORMAL_RANKING,
            workloadClass = TitanInferenceWorkloadClass.DEFAULT,
            resourceCondition = TitanResourceConditionClass.UNGOVERNED,
            estimatedMemoryMb = null,
            backendPolicyScore = null,
            rejected = emptyList()
        )

        observatory.record(observation("one"))
        observatory.record(observation("two"))
        observatory.record(observation("three"))

        assertEquals(listOf(ModelId("two"), ModelId("three")), observatory.recent(8).map { it.selectedModelId })
        assertEquals(ModelId("three"), observatory.latest()?.selectedModelId)
    }

    private fun descriptor(
        id: String,
        capabilities: Set<CapabilityId>
    ): ModelDescriptor = ModelDescriptor(
        id = ModelId(id),
        format = "gguf",
        capabilities = capabilities,
        local = true
    )

    private fun installed(descriptor: ModelDescriptor): InstalledModel = InstalledModel(
        descriptor = descriptor,
        displayName = "${descriptor.id.value}.gguf",
        locator = "memory://${descriptor.id.value}.gguf",
        lengthBytes = 128L,
        sha256 = "a".repeat(64),
        ggufVersion = 3L,
        tensorCount = 1UL,
        metadataKeyValueCount = 1UL,
        installedAtEpochMs = 1L
    )

    private fun resolver(): ModelArtifactResolver = object : ModelArtifactResolver {
        override fun resolve(model: InstalledModel): ModelArtifactSource =
            object : ModelArtifactSource {
                override val locator: String = model.locator
                override val displayName: String = model.displayName
                override val lengthBytes: Long? = model.lengthBytes
                override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            }
    }

    private class DiagnosticBackend(
        private val memoryByModelMb: Map<String, Int>,
        private val contextTokens: Int = 4_096
    ) : ManagedInferenceBackend {
        override val id: String = "diagnostic-backend"

        override fun supports(model: InstalledModel): Boolean =
            model.descriptor.id.value in memoryByModelMb

        override fun health(): BackendHealth = BackendHealth(BackendState.READY)

        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(
                estimatedMemoryMb = memoryByModelMb.getValue(model.descriptor.id.value),
                preferredThreads = 1,
                contextTokens = contextTokens
            )

        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> = Result.success(
            InferenceResponse(
                modelId = model.descriptor.id,
                backendId = id,
                text = "ok"
            )
        )
    }
}
