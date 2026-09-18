package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class BackendAdmissionIsolationTest {
    private val reasoning = CapabilityId("reasoning")
    private val model = InstalledModel(
        descriptor = ModelDescriptor(
            id = ModelId("isolation-model"),
            format = "gguf",
            capabilities = setOf(reasoning),
            local = true
        ),
        displayName = "isolation.gguf",
        locator = "memory://isolation.gguf",
        lengthBytes = 128L,
        sha256 = "a".repeat(64),
        ggufVersion = 3L,
        tensorCount = 1UL,
        metadataKeyValueCount = 1UL
    )
    private val request = InferenceRequest("isolate admission failures")

    @Test
    fun legacyCandidatesSkipBackendWhoseSupportsThrows() {
        val registry = InferenceBackendRegistry().apply {
            register(throwingBackend("broken-supports", FailurePoint.SUPPORTS))
            register(healthyBackend("healthy"))
        }

        assertEquals(listOf("healthy"), registry.candidates(model).map { it.id })
    }

    @Test
    fun requestAwareCandidatesSkipBackendWhoseHealthThrows() {
        val registry = InferenceBackendRegistry().apply {
            register(throwingBackend("broken-health", FailurePoint.HEALTH))
            register(healthyBackend("healthy"))
        }

        assertEquals(listOf("healthy"), registry.candidates(model, request).map { it.id })
    }

    @Test
    fun governedCandidatesSkipBackendWhoseEstimateThrows() {
        val registry = InferenceBackendRegistry().apply {
            register(throwingBackend("broken-estimate", FailurePoint.ESTIMATE))
            register(healthyBackend("healthy"))
        }

        assertEquals(
            listOf("healthy"),
            registry.candidates(
                model,
                request,
                ResourceBudget(memoryMb = 512, thermalClass = 1)
            ).map { it.id }
        )
    }

    @Test
    fun nativeLinkageFailureIsIsolatedToBrokenBackend() {
        val broken = object : ManagedInferenceBackend {
            override val id: String = "broken-jni"
            override fun supports(model: InstalledModel): Boolean = true
            override fun health(): BackendHealth = throw UnsatisfiedLinkError("synthetic missing JNI")
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
                InferenceCost(128, 2, 2048)
            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> = error("must not execute")
        }
        val registry = InferenceBackendRegistry().apply {
            register(broken)
            register(healthyBackend("healthy"))
        }

        assertEquals(listOf("healthy"), registry.candidates(model, request).map { it.id })
    }

    @Test
    fun ungovernedPlannerFallsThroughAfterBrokenAdmissionCallback() {
        val planner = planner(
            InferenceBackendRegistry().apply {
                register(throwingBackend("broken-health", FailurePoint.HEALTH))
                register(healthyBackend("healthy"))
            }
        )

        val route = planner.plan(request, budget = null).getOrThrow()

        assertEquals("healthy", route.backend.id)
    }

    @Test
    fun governedPlannerFallsThroughAfterBrokenAdmissionCallback() {
        val planner = planner(
            InferenceBackendRegistry().apply {
                register(throwingBackend("broken-estimate", FailurePoint.ESTIMATE))
                register(healthyBackend("healthy"))
            }
        )

        val route = planner.plan(
            request,
            ResourceBudget(memoryMb = 512, thermalClass = 1)
        ).getOrThrow()

        assertEquals("healthy", route.backend.id)
    }

    @Test
    fun virtualMachineErrorsAreNotSilentlyIsolated() {
        val fatal = object : InferenceBackend {
            override val id: String = "fatal"
            override fun supports(model: InstalledModel): Boolean = throw OutOfMemoryError("synthetic fatal")
            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> = error("must not execute")
        }
        val registry = InferenceBackendRegistry().apply {
            register(fatal)
            register(healthyBackend("healthy"))
        }

        try {
            registry.candidates(model)
            fail("VirtualMachineError must escape admission isolation")
        } catch (error: OutOfMemoryError) {
            assertEquals("synthetic fatal", error.message)
        }
    }

    @Test
    fun executionFailureAfterSelectionDoesNotFailOverToSecondBackend() {
        val calls = mutableListOf<String>()
        val failing = healthyBackend("selected-failure") { installed ->
            calls += "selected-failure"
            Result.failure(IllegalStateException("synthetic inference failure:${installed.descriptor.id.value}"))
        }
        val fallback = healthyBackend("fallback") { installed ->
            calls += "fallback"
            Result.success(InferenceResponse(installed.descriptor.id, "fallback", "unexpected"))
        }
        val models = InMemoryModelRegistry().apply { register(model.descriptor) }
        val catalog = InMemoryInstalledModelCatalog().apply { put(model) }
        val runtime = TitanCortexRuntime(
            models = models,
            catalog = catalog,
            artifacts = object : ModelArtifactResolver {
                override fun resolve(model: InstalledModel): ModelArtifactSource = StaticSource(model.locator)
            },
            backends = InferenceBackendRegistry().apply {
                register(failing)
                register(fallback)
            }
        )

        val result = runtime.infer(request)

        assertTrue(result.isFailure)
        assertEquals(listOf("selected-failure"), calls)
    }

    private fun planner(backends: InferenceBackendRegistry): TitanInferenceRoutePlanner {
        val models = InMemoryModelRegistry().apply { register(model.descriptor) }
        val catalog = InMemoryInstalledModelCatalog().apply { put(model) }
        return TitanInferenceRoutePlanner(
            models = models,
            catalog = catalog,
            artifacts = object : ModelArtifactResolver {
                override fun resolve(model: InstalledModel): ModelArtifactSource = StaticSource(model.locator)
            },
            backends = backends
        )
    }

    private enum class FailurePoint { SUPPORTS, HEALTH, ESTIMATE }

    private fun throwingBackend(id: String, point: FailurePoint): ManagedInferenceBackend =
        object : ManagedInferenceBackend {
            override val id: String = id
            override fun supports(model: InstalledModel): Boolean {
                if (point == FailurePoint.SUPPORTS) error("synthetic supports failure")
                return true
            }
            override fun health(): BackendHealth {
                if (point == FailurePoint.HEALTH) error("synthetic health failure")
                return BackendHealth(BackendState.READY)
            }
            override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost {
                if (point == FailurePoint.ESTIMATE) error("synthetic estimate failure")
                return InferenceCost(128, 2, 2048)
            }
            override fun infer(
                model: InstalledModel,
                source: ModelArtifactSource,
                request: InferenceRequest
            ): Result<InferenceResponse> = error("broken backend must not execute")
        }

    private fun healthyBackend(
        id: String,
        inferBlock: (InstalledModel) -> Result<InferenceResponse> = { installed ->
            Result.success(InferenceResponse(installed.descriptor.id, id, "ok"))
        }
    ): ManagedInferenceBackend = object : ManagedInferenceBackend {
        override val id: String = id
        override fun supports(model: InstalledModel): Boolean = true
        override fun health(): BackendHealth = BackendHealth(BackendState.READY)
        override fun estimate(model: InstalledModel, request: InferenceRequest): InferenceCost =
            InferenceCost(128, 2, 2048)
        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> = inferBlock(model)
    }

    private class StaticSource(override val locator: String) : ModelArtifactSource {
        override val displayName: String = "isolation.gguf"
        override val lengthBytes: Long? = 128L
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }
}
