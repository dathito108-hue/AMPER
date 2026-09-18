package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class TitanScopedResourceHysteresisTest {
    private val reasoning = CapabilityId("reasoning")
    private val coding = CapabilityId("coding")
    private val warm = ResourceBudget(memoryMb = 2_048, thermalClass = 2)
    private val cool = ResourceBudget(memoryMb = 2_048, thermalClass = 1)

    @Test
    fun capabilityProfilesKeepIndependentThermalRecoveryState() {
        val planner = planner(maxResourceStabilityScopes = 64)
        val reasoningRequest = InferenceRequest(
            prompt = "same workload",
            requiredCapabilities = setOf(reasoning),
            maxOutputTokens = 128
        )
        val codingRequest = InferenceRequest(
            prompt = "same workload",
            requiredCapabilities = setOf(coding),
            maxOutputTokens = 128
        )

        val reasoningWarm = planner.plan(reasoningRequest, warm).getOrThrow()
        assertEquals(TitanResourceConditionClass.WARM_STANDARD, reasoningWarm.resourceCondition)

        val codingCool = planner.plan(codingRequest, cool).getOrThrow()
        assertEquals(TitanResourceConditionClass.COOL_STANDARD, codingCool.resourceCondition)

        val reasoningFirstCool = planner.plan(reasoningRequest, cool).getOrThrow()
        assertEquals(TitanResourceConditionClass.WARM_STANDARD, reasoningFirstCool.resourceCondition)
        val reasoningSecondCool = planner.plan(reasoningRequest, cool).getOrThrow()
        assertEquals(TitanResourceConditionClass.COOL_STANDARD, reasoningSecondCool.resourceCondition)
    }

    @Test
    fun workloadClassesKeepIndependentThermalRecoveryState() {
        val planner = planner(maxResourceStabilityScopes = 64)
        val compact = InferenceRequest(
            prompt = "short",
            requiredCapabilities = setOf(reasoning),
            maxOutputTokens = 128
        )
        val heavy = InferenceRequest(
            prompt = "x".repeat(9_000),
            requiredCapabilities = setOf(reasoning),
            maxOutputTokens = 2_048
        )

        val compactWarm = planner.plan(compact, warm).getOrThrow()
        assertEquals(TitanResourceConditionClass.WARM_STANDARD, compactWarm.resourceCondition)

        val heavyCool = planner.plan(heavy, cool).getOrThrow()
        assertEquals(TitanResourceConditionClass.COOL_STANDARD, heavyCool.resourceCondition)

        val compactFirstCool = planner.plan(compact, cool).getOrThrow()
        assertEquals(TitanResourceConditionClass.WARM_STANDARD, compactFirstCool.resourceCondition)
    }

    @Test
    fun boundedStabilityStateEvictsEldestScope() {
        val planner = planner(maxResourceStabilityScopes = 1)
        val compact = InferenceRequest(
            prompt = "short",
            requiredCapabilities = setOf(reasoning),
            maxOutputTokens = 128
        )
        val heavy = InferenceRequest(
            prompt = "x".repeat(9_000),
            requiredCapabilities = setOf(reasoning),
            maxOutputTokens = 2_048
        )

        val compactWarm = planner.plan(compact, warm).getOrThrow()
        assertEquals(TitanResourceConditionClass.WARM_STANDARD, compactWarm.resourceCondition)

        val heavyCool = planner.plan(heavy, cool).getOrThrow()
        assertEquals(TitanResourceConditionClass.COOL_STANDARD, heavyCool.resourceCondition)

        val compactAfterEviction = planner.plan(compact, cool).getOrThrow()
        assertEquals(TitanResourceConditionClass.COOL_STANDARD, compactAfterEviction.resourceCondition)
    }

    @Test
    fun configuredRecoverySampleCountIsClonedIntoEveryScope() {
        val planner = planner(
            maxResourceStabilityScopes = 64,
            thermalRecoverySamples = 3
        )
        val reasoningRequest = InferenceRequest(
            prompt = "reasoning",
            requiredCapabilities = setOf(reasoning),
            maxOutputTokens = 128
        )
        val codingRequest = InferenceRequest(
            prompt = "coding",
            requiredCapabilities = setOf(coding),
            maxOutputTokens = 128
        )

        assertEquals(
            TitanResourceConditionClass.WARM_STANDARD,
            planner.plan(reasoningRequest, warm).getOrThrow().resourceCondition
        )
        assertEquals(
            TitanResourceConditionClass.COOL_STANDARD,
            planner.plan(codingRequest, cool).getOrThrow().resourceCondition
        )
        assertEquals(
            TitanResourceConditionClass.WARM_STANDARD,
            planner.plan(reasoningRequest, cool).getOrThrow().resourceCondition
        )
        assertEquals(
            TitanResourceConditionClass.WARM_STANDARD,
            planner.plan(reasoningRequest, cool).getOrThrow().resourceCondition
        )
        assertEquals(
            TitanResourceConditionClass.COOL_STANDARD,
            planner.plan(reasoningRequest, cool).getOrThrow().resourceCondition
        )
    }

    private fun planner(
        maxResourceStabilityScopes: Int,
        thermalRecoverySamples: Int = 2
    ): TitanInferenceRoutePlanner {
        val models = InMemoryModelRegistry()
        val catalog = InMemoryInstalledModelCatalog()
        listOf("a-primary", "b-alternative").forEach { id ->
            val installed = installed(id)
            models.register(installed.descriptor)
            catalog.put(installed)
        }
        val resolver = object : ModelArtifactResolver {
            override fun resolve(model: InstalledModel): ModelArtifactSource = StaticSource(model.locator)
        }
        val backends = InferenceBackendRegistry().apply {
            register(object : InferenceBackend {
                override val id: String = "test-backend"
                override fun supports(model: InstalledModel): Boolean = true
                override fun infer(
                    model: InstalledModel,
                    source: ModelArtifactSource,
                    request: InferenceRequest
                ): Result<InferenceResponse> = error("planner test does not execute inference")
            })
        }
        return TitanInferenceRoutePlanner(
            models = models,
            catalog = catalog,
            artifacts = resolver,
            backends = backends,
            explorationEveryPlanningPasses = 1_000,
            minThroughputSamplesForRanking = 1,
            resourceConditionStabilizer = TitanResourceConditionStabilizer(
                thermalRecoverySamples = thermalRecoverySamples
            ),
            maxResourceStabilityScopes = maxResourceStabilityScopes
        )
    }

    private fun installed(id: String): InstalledModel {
        val descriptor = ModelDescriptor(
            id = ModelId(id),
            format = "gguf",
            capabilities = setOf(reasoning, coding),
            local = true
        )
        return InstalledModel(
            descriptor = descriptor,
            displayName = "$id.gguf",
            locator = "memory://$id.gguf",
            lengthBytes = 128L,
            sha256 = id.padEnd(64, '0').take(64),
            ggufVersion = 3L,
            tensorCount = 1UL,
            metadataKeyValueCount = 1UL,
            installedAtEpochMs = 1L
        )
    }

    private class StaticSource(override val locator: String) : ModelArtifactSource {
        override val displayName: String = "test.gguf"
        override val lengthBytes: Long? = 128L
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }
}
