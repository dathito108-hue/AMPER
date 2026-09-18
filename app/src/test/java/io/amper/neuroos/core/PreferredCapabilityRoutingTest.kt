package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class PreferredCapabilityRoutingTest {
    private val reasoning = TitanCapabilities.REASONING
    private val code = TitanCapabilities.CODE_GENERATION

    @Test
    fun specializedProfileWinsBeforeGenericModelOrdering() {
        val general = descriptor("a-general", setOf(reasoning))
        val specialist = descriptor("z-code", setOf(reasoning, code))
        val fixture = fixture(general, specialist)
        val request = InferenceRequest(
            prompt = "write code",
            requiredCapabilities = setOf(reasoning),
            preferredCapabilityProfiles = listOf(setOf(reasoning, code))
        )

        val route = fixture.planner.plan(request).getOrThrow()

        assertEquals("z-code", route.descriptor.id.value)
        assertEquals(setOf(reasoning, code), route.selectedCapabilities)
    }

    @Test
    fun missingSpecialistFallsBackToMandatoryReasoningBaseline() {
        val general = descriptor("general", setOf(reasoning))
        val fixture = fixture(general)
        val request = InferenceRequest(
            prompt = "write code",
            requiredCapabilities = setOf(reasoning),
            preferredCapabilityProfiles = listOf(setOf(reasoning, code))
        )

        val route = fixture.planner.plan(request).getOrThrow()

        assertEquals("general", route.descriptor.id.value)
        assertEquals(setOf(reasoning), route.selectedCapabilities)
    }

    @Test
    fun infeasibleSpecialistFallsBackToFeasibleGenericModel() {
        val specialist = descriptor("a-code-heavy", setOf(reasoning, code))
        val general = descriptor("b-general", setOf(reasoning))
        val fixture = fixture(
            specialist,
            general,
            memoryByModelMb = mapOf(
                "a-code-heavy" to 1_024,
                "b-general" to 128
            )
        )
        val request = InferenceRequest(
            prompt = "debug Kotlin",
            requiredCapabilities = setOf(reasoning),
            preferredCapabilityProfiles = listOf(setOf(reasoning, code))
        )

        val route = fixture.planner.plan(
            request,
            ResourceBudget(memoryMb = 512, thermalClass = 1)
        ).getOrThrow()

        assertEquals("b-general", route.descriptor.id.value)
        assertEquals(setOf(reasoning), route.selectedCapabilities)
    }

    @Test
    fun preferredProfileCannotWeakenMandatoryBaseline() {
        val result = runCatching {
            InferenceRequest(
                prompt = "invalid ladder",
                requiredCapabilities = setOf(reasoning),
                preferredCapabilityProfiles = listOf(setOf(code))
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("must contain all required"))
    }

    @Test
    fun capabilityPolicyRecognizesStrongVietnameseCodeSignal() {
        val profiles = DeterministicInferenceCapabilityPolicy.preferredProfiles(
            "Hãy viết code Kotlin để parse file này",
            setOf(reasoning)
        )

        assertEquals(listOf(setOf(reasoning, code)), profiles)
    }

    @Test
    fun capabilityPolicyRecognizesNaturalImplementationRequest() {
        val profiles = DeterministicInferenceCapabilityPolicy.preferredProfiles(
            "Implement a retrying HTTP client in Kotlin",
            setOf(reasoning)
        )

        assertEquals(listOf(setOf(reasoning, code)), profiles)
    }

    @Test
    fun capabilityPolicyRecognizesRefactorRequestWithoutLanguageName() {
        val profiles = DeterministicInferenceCapabilityPolicy.preferredProfiles(
            "Refactor this parser to avoid duplicate allocations",
            setOf(reasoning)
        )

        assertEquals(listOf(setOf(reasoning, code)), profiles)
    }

    @Test
    fun capabilityPolicyRecognizesVietnameseApiImplementationRequest() {
        val profiles = DeterministicInferenceCapabilityPolicy.preferredProfiles(
            "Tạo API endpoint để lưu dữ liệu người dùng",
            setOf(reasoning)
        )

        assertEquals(listOf(setOf(reasoning, code)), profiles)
    }

    @Test
    fun capabilityPolicyRecognizesCompilerFailureAsStrongArtifact() {
        val profiles = DeterministicInferenceCapabilityPolicy.preferredProfiles(
            "Gradle build failed: unresolved reference FooBar",
            setOf(reasoning)
        )

        assertEquals(listOf(setOf(reasoning, code)), profiles)
    }

    @Test
    fun capabilityPolicyRecognizesCodeSyntaxWithoutExplicitActionVerb() {
        val intent = DeterministicInferenceCapabilityPolicy.classify(
            "fun parse(value: String): Result<String> { return runCatching { value } }"
        )

        assertTrue(intent.codeGeneration)
    }

    @Test
    fun capabilityPolicyDoesNotSpecializeBareProgrammingLanguageQuestion() {
        val profiles = DeterministicInferenceCapabilityPolicy.preferredProfiles(
            "What is Python?",
            setOf(reasoning)
        )

        assertTrue(profiles.isEmpty())
    }

    @Test
    fun capabilityPolicyDoesNotSpecializeProgrammingHistoryQuestion() {
        val profiles = DeterministicInferenceCapabilityPolicy.preferredProfiles(
            "Tell me about the history of Java",
            setOf(reasoning)
        )

        assertTrue(profiles.isEmpty())
    }

    @Test
    fun capabilityPolicyDoesNotSpecializeNonCodeWritingAboutProgramming() {
        val intent = DeterministicInferenceCapabilityPolicy.classify(
            "Write an essay about Python history"
        )

        assertFalse(intent.codeGeneration)
    }

    @Test
    fun capabilityPolicyDoesNotTreatAmbiguousClassWordAsCode() {
        val intent = DeterministicInferenceCapabilityPolicy.classify(
            "What does a class mean in sociology?"
        )

        assertFalse(intent.codeGeneration)
    }

    @Test
    fun capabilityPolicyPreservesEveryMandatoryBaselineCapability() {
        val extra = CapabilityId("vision")
        val profiles = DeterministicInferenceCapabilityPolicy.preferredProfiles(
            "Implement an Android coroutine worker",
            setOf(reasoning, extra)
        )

        assertEquals(listOf(setOf(reasoning, extra, code)), profiles)
    }

    @Test
    fun capabilityPolicyLeavesOrdinaryReasoningRequestUnspecialized() {
        val profiles = DeterministicInferenceCapabilityPolicy.preferredProfiles(
            "Tóm tắt ghi chú hôm nay",
            setOf(reasoning)
        )

        assertTrue(profiles.isEmpty())
    }

    @Test
    fun capabilityProfilesDeduplicateBaselineWithoutChangingPriority() {
        val specialized = setOf(reasoning, code)
        val request = InferenceRequest(
            prompt = "code",
            requiredCapabilities = setOf(reasoning),
            preferredCapabilityProfiles = listOf(specialized, specialized, setOf(reasoning))
        )

        assertEquals(listOf(specialized, setOf(reasoning)), request.capabilityProfiles())
    }

    private data class Fixture(val planner: TitanInferenceRoutePlanner)

    private fun fixture(
        vararg descriptors: ModelDescriptor,
        memoryByModelMb: Map<String, Int> = descriptors.associate { it.id.value to 64 }
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
        val backends = InferenceBackendRegistry().apply {
            register(CostAwareBackend(memoryByModelMb))
        }
        return Fixture(TitanInferenceRoutePlanner(models, catalog, resolver, backends))
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
        sha256 = "a".repeat(64),
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

    private class CostAwareBackend(
        private val memoryByModelMb: Map<String, Int>
    ) : ManagedInferenceBackend {
        override val id: String = "cost-aware"

        override fun supports(model: InstalledModel): Boolean =
            model.descriptor.id.value in memoryByModelMb

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
        ): Result<InferenceResponse> = Result.success(
            InferenceResponse(
                modelId = model.descriptor.id,
                backendId = id,
                text = "ok"
            )
        )
    }
}
