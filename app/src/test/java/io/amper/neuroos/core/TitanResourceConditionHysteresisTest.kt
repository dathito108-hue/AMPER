package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class TitanResourceConditionHysteresisTest {
    private val reasoning = CapabilityId("reasoning")
    private val request = InferenceRequest("same interactive workload", maxOutputTokens = 128)

    @Test
    fun memoryDeteriorationIsImmediateAndRecoveryNeedsMargin() {
        val stabilizer = TitanResourceConditionStabilizer(memoryRecoveryMarginMb = 256)

        assertEquals(
            TitanResourceConditionClass.COOL_STANDARD,
            stabilizer.observe(ResourceBudget(memoryMb = 2_048, thermalClass = 1))
        )
        assertEquals(
            TitanResourceConditionClass.COOL_CONSTRAINED,
            stabilizer.observe(ResourceBudget(memoryMb = 1_024, thermalClass = 1))
        )
        assertEquals(
            TitanResourceConditionClass.COOL_CONSTRAINED,
            stabilizer.observe(ResourceBudget(memoryMb = 1_025, thermalClass = 1))
        )
        assertEquals(
            TitanResourceConditionClass.COOL_CONSTRAINED,
            stabilizer.observe(ResourceBudget(memoryMb = 1_280, thermalClass = 1))
        )
        assertEquals(
            TitanResourceConditionClass.COOL_STANDARD,
            stabilizer.observe(ResourceBudget(memoryMb = 1_281, thermalClass = 1))
        )
    }

    @Test
    fun expandedMemoryRecoveryUsesUpperMarginButLargeRecoveryIsImmediate() {
        val stabilizer = TitanResourceConditionStabilizer(memoryRecoveryMarginMb = 256)

        assertEquals(
            TitanResourceConditionClass.COOL_EXPANDED,
            stabilizer.observe(ResourceBudget(memoryMb = 8_192, thermalClass = 1))
        )
        assertEquals(
            TitanResourceConditionClass.COOL_STANDARD,
            stabilizer.observe(ResourceBudget(memoryMb = 4_096, thermalClass = 1))
        )
        assertEquals(
            TitanResourceConditionClass.COOL_STANDARD,
            stabilizer.observe(ResourceBudget(memoryMb = 4_097, thermalClass = 1))
        )
        assertEquals(
            TitanResourceConditionClass.COOL_STANDARD,
            stabilizer.observe(ResourceBudget(memoryMb = 4_352, thermalClass = 1))
        )
        assertEquals(
            TitanResourceConditionClass.COOL_EXPANDED,
            stabilizer.observe(ResourceBudget(memoryMb = 4_353, thermalClass = 1))
        )

        stabilizer.reset()
        assertEquals(
            TitanResourceConditionClass.COOL_CONSTRAINED,
            stabilizer.observe(ResourceBudget(memoryMb = 512, thermalClass = 1))
        )
        assertEquals(
            TitanResourceConditionClass.COOL_EXPANDED,
            stabilizer.observe(ResourceBudget(memoryMb = 8_192, thermalClass = 1))
        )
    }

    @Test
    fun thermalDeteriorationIsImmediateAndRecoveryNeedsRepeatedSamples() {
        val stabilizer = TitanResourceConditionStabilizer(thermalRecoverySamples = 2)
        val memoryMb = 2_048

        assertEquals(
            TitanResourceConditionClass.COOL_STANDARD,
            stabilizer.observe(ResourceBudget(memoryMb = memoryMb, thermalClass = 1))
        )
        assertEquals(
            TitanResourceConditionClass.WARM_STANDARD,
            stabilizer.observe(ResourceBudget(memoryMb = memoryMb, thermalClass = 2))
        )
        assertEquals(
            TitanResourceConditionClass.WARM_STANDARD,
            stabilizer.observe(ResourceBudget(memoryMb = memoryMb, thermalClass = 1))
        )
        assertEquals(
            TitanResourceConditionClass.COOL_STANDARD,
            stabilizer.observe(ResourceBudget(memoryMb = memoryMb, thermalClass = 1))
        )
        assertEquals(
            TitanResourceConditionClass.CRITICAL_STANDARD,
            stabilizer.observe(ResourceBudget(memoryMb = memoryMb, thermalClass = 4))
        )
        assertEquals(
            TitanResourceConditionClass.CRITICAL_STANDARD,
            stabilizer.observe(ResourceBudget(memoryMb = memoryMb, thermalClass = 2))
        )
        assertEquals(
            TitanResourceConditionClass.WARM_STANDARD,
            stabilizer.observe(ResourceBudget(memoryMb = memoryMb, thermalClass = 2))
        )
    }

    @Test
    fun oneCoolSampleDoesNotEscapeWarmFeedbackHistory() {
        val planner = planner()
        val warmBudget = ResourceBudget(memoryMb = 2_048, thermalClass = 2)
        val coolBudget = ResourceBudget(memoryMb = 2_048, thermalClass = 1)

        val warm = planner.plan(request, warmBudget).getOrThrow()
        assertEquals("a-primary", warm.descriptor.id.value)
        assertEquals(TitanResourceConditionClass.WARM_STANDARD, warm.resourceCondition)
        planner.recordFailure(warm)

        val firstCool = planner.plan(request, coolBudget).getOrThrow()
        assertEquals(TitanResourceConditionClass.WARM_STANDARD, firstCool.resourceCondition)
        assertEquals("b-fallback", firstCool.descriptor.id.value)

        val secondCool = planner.plan(request, coolBudget).getOrThrow()
        assertEquals(TitanResourceConditionClass.COOL_STANDARD, secondCool.resourceCondition)
        assertEquals("a-primary", secondCool.descriptor.id.value)
    }

    @Test
    fun ungovernedTransitionResetsHysteresisContext() {
        val stabilizer = TitanResourceConditionStabilizer(thermalRecoverySamples = 3)

        assertEquals(
            TitanResourceConditionClass.WARM_STANDARD,
            stabilizer.observe(ResourceBudget(memoryMb = 2_048, thermalClass = 2))
        )
        assertEquals(TitanResourceConditionClass.UNGOVERNED, stabilizer.observe(null))
        assertEquals(
            TitanResourceConditionClass.COOL_STANDARD,
            stabilizer.observe(ResourceBudget(memoryMb = 2_048, thermalClass = 1))
        )
    }

    private fun planner(): TitanInferenceRoutePlanner {
        val models = InMemoryModelRegistry()
        val catalog = InMemoryInstalledModelCatalog()
        listOf("a-primary", "b-fallback").forEach { id ->
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
                memoryRecoveryMarginMb = 256,
                thermalRecoverySamples = 2
            )
        )
    }

    private fun installed(id: String): InstalledModel {
        val descriptor = ModelDescriptor(
            id = ModelId(id),
            format = "gguf",
            capabilities = setOf(reasoning),
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
