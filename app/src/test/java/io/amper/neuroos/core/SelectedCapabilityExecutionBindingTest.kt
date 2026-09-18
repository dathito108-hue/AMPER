package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class SelectedCapabilityExecutionBindingTest {
    private val reasoning = TitanCapabilities.REASONING
    private val code = TitanCapabilities.CODE_GENERATION

    @Test
    fun specializedSelectionIsFrozenIntoBackendExecutionRequestAndResponse() {
        val general = descriptor("a-general", setOf(reasoning))
        val specialist = descriptor("z-code", setOf(reasoning, code))
        val backend = RecordingBackend(setOf("a-general", "z-code"))
        val fixture = fixture(listOf(general, specialist), backend)
        val request = codeRequest()

        val response = fixture.titan.infer(request).getOrThrow()

        assertEquals("z-code", response.modelId.value)
        assertEquals(setOf(reasoning, code), response.selectedCapabilities)
        val executed = backend.requests.single()
        assertEquals(setOf(reasoning, code), executed.requiredCapabilities)
        assertTrue(executed.preferredCapabilityProfiles.isEmpty())
        assertEquals(request.prompt, executed.prompt)
        assertEquals(request.maxOutputTokens, executed.maxOutputTokens)
        assertEquals(request.temperature, executed.temperature, 0.0)
    }

    @Test
    fun baselineFallbackIsFrozenWhenSpecialistDoesNotExist() {
        val general = descriptor("general", setOf(reasoning))
        val backend = RecordingBackend(setOf("general"))
        val fixture = fixture(listOf(general), backend)

        val response = fixture.titan.infer(codeRequest()).getOrThrow()

        assertEquals("general", response.modelId.value)
        assertEquals(setOf(reasoning), response.selectedCapabilities)
        assertEquals(setOf(reasoning), backend.requests.single().requiredCapabilities)
        assertTrue(backend.requests.single().preferredCapabilityProfiles.isEmpty())
    }

    @Test
    fun backendCannotReturnDifferentModelIdentityThanSelectedRoute() {
        val general = descriptor("general", setOf(reasoning))
        val backend = RecordingBackend(
            supported = setOf("general"),
            responseModelOverride = ModelId("other-model")
        )
        val fixture = fixture(listOf(general), backend)

        val result = fixture.titan.infer(InferenceRequest("identity check"))

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty()
                .contains("response model identity does not match selected route")
        )
    }

    @Test
    fun backendCannotReturnDifferentBackendIdentityThanSelectedRoute() {
        val general = descriptor("general", setOf(reasoning))
        val backend = RecordingBackend(
            supported = setOf("general"),
            responseBackendOverride = "unexpected-backend"
        )
        val fixture = fixture(listOf(general), backend)

        val result = fixture.titan.infer(InferenceRequest("backend identity check"))

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty()
                .contains("response id does not match selected route")
        )
    }

    @Test
    fun executionAwareAgentLeaseRecordsSelectedSpecialistProfile() {
        val general = descriptor("a-general", setOf(reasoning))
        val specialist = descriptor("z-code", setOf(reasoning, code))
        val backend = RecordingBackend(setOf("a-general", "z-code"))
        val fixture = fixture(listOf(general, specialist), backend)
        val agents = EphemeralAgentFabric(
            governor = MobileResourceGovernor(
                ResourceBudget(maxConcurrentAgents = 2, memoryMb = 1024, thermalClass = 1)
            ),
            models = fixture.models,
            routePlanner = fixture.planner
        )

        val lease = agents.spawn(
            AgentSpec(
                role = "coder",
                requiredCapabilities = setOf(reasoning),
                purpose = "write Kotlin code",
                preferredCapabilityProfiles = listOf(setOf(reasoning, code))
            )
        ).getOrThrow()

        assertEquals("z-code", lease.model.value)
        assertEquals("recording", lease.backendId)
        assertEquals(setOf(reasoning, code), lease.selectedCapabilities)
        assertTrue(backend.requests.isEmpty())
    }

    @Test
    fun requestCannotBindCapabilityProfileOutsideDeclaredLadder() {
        val request = InferenceRequest(
            prompt = "reason",
            requiredCapabilities = setOf(reasoning)
        )

        val result = runCatching {
            request.bindSelectedCapabilities(setOf(reasoning, code))
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("was not declared"))
    }

    private fun codeRequest(): InferenceRequest = InferenceRequest(
        prompt = "write Kotlin code",
        requiredCapabilities = setOf(reasoning),
        maxOutputTokens = 321,
        temperature = 0.42,
        preferredCapabilityProfiles = listOf(setOf(reasoning, code))
    )

    private data class Fixture(
        val models: InMemoryModelRegistry,
        val planner: TitanInferenceRoutePlanner,
        val titan: TitanCortexRuntime
    )

    private fun fixture(
        descriptors: List<ModelDescriptor>,
        backend: InferenceBackend
    ): Fixture {
        val models = InMemoryModelRegistry().also { registry ->
            descriptors.forEach(registry::register)
        }
        val catalog = InMemoryInstalledModelCatalog().also { catalog ->
            descriptors.forEach { catalog.put(installed(it)) }
        }
        val resolver = object : ModelArtifactResolver {
            override fun resolve(model: InstalledModel): ModelArtifactSource =
                StaticSource(model.locator)
        }
        val backends = InferenceBackendRegistry().apply { register(backend) }
        val planner = TitanInferenceRoutePlanner(models, catalog, resolver, backends)
        return Fixture(
            models = models,
            planner = planner,
            titan = TitanCortexRuntime(
                models = models,
                catalog = catalog,
                artifacts = resolver,
                backends = backends,
                routePlanner = planner
            )
        )
    }

    private fun descriptor(id: String, capabilities: Set<CapabilityId>): ModelDescriptor =
        ModelDescriptor(
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
        sha256 = "b".repeat(64),
        ggufVersion = 3L,
        tensorCount = 1UL,
        metadataKeyValueCount = 1UL,
        installedAtEpochMs = 1L
    )

    private class StaticSource(override val locator: String) : ModelArtifactSource {
        override val displayName: String = "test.gguf"
        override val lengthBytes: Long? = 128L
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }

    private class RecordingBackend(
        private val supported: Set<String>,
        private val responseModelOverride: ModelId? = null,
        private val responseBackendOverride: String? = null
    ) : InferenceBackend {
        override val id: String = "recording"
        val requests = mutableListOf<InferenceRequest>()

        override fun supports(model: InstalledModel): Boolean =
            model.descriptor.id.value in supported

        override fun infer(
            model: InstalledModel,
            source: ModelArtifactSource,
            request: InferenceRequest
        ): Result<InferenceResponse> {
            requests += request
            return Result.success(
                InferenceResponse(
                    modelId = responseModelOverride ?: model.descriptor.id,
                    backendId = responseBackendOverride ?: id,
                    text = "ok"
                )
            )
        }
    }
}
