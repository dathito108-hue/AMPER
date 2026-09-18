package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class TitanWorkloadScopedFeedbackTest {
    private val reasoning = CapabilityId("reasoning")

    @Test
    fun workloadClassificationHasDeterministicBoundaries() {
        assertEquals(
            TitanInferenceWorkloadClass(TitanPromptWorkloadBand.COMPACT, TitanOutputWorkloadBand.SHORT),
            TitanInferenceWorkloadClass.from(request(promptChars = 1_024, outputTokens = 256))
        )
        assertEquals(
            TitanInferenceWorkloadClass(TitanPromptWorkloadBand.STANDARD, TitanOutputWorkloadBand.STANDARD),
            TitanInferenceWorkloadClass.from(request(promptChars = 1_025, outputTokens = 257))
        )
        assertEquals(
            TitanInferenceWorkloadClass(TitanPromptWorkloadBand.STANDARD, TitanOutputWorkloadBand.STANDARD),
            TitanInferenceWorkloadClass.from(request(promptChars = 8_192, outputTokens = 1_024))
        )
        assertEquals(
            TitanInferenceWorkloadClass(TitanPromptWorkloadBand.LONG, TitanOutputWorkloadBand.LONG),
            TitanInferenceWorkloadClass.from(request(promptChars = 8_193, outputTokens = 1_025))
        )
    }

    @Test
    fun rawPromptContentIsNotPartOfWorkloadIdentity() {
        val first = InferenceRequest(prompt = "a".repeat(400), maxOutputTokens = 128)
        val second = InferenceRequest(prompt = "z".repeat(900), maxOutputTokens = 128)

        assertEquals(
            TitanInferenceWorkloadClass.from(first),
            TitanInferenceWorkloadClass.from(second)
        )
    }

    @Test
    fun workloadClassParticipatesInFeedbackKeyIdentity() {
        val route = route("brain", TitanInferenceWorkloadClass.DEFAULT)
        val compact = TitanRouteFeedbackKey.from(
            route.copy(
                workloadClass = TitanInferenceWorkloadClass(
                    TitanPromptWorkloadBand.COMPACT,
                    TitanOutputWorkloadBand.SHORT
                )
            )
        )
        val long = TitanRouteFeedbackKey.from(
            route.copy(
                workloadClass = TitanInferenceWorkloadClass(
                    TitanPromptWorkloadBand.LONG,
                    TitanOutputWorkloadBand.LONG
                )
            )
        )

        assertNotEquals(compact, long)
    }

    @Test
    fun longWorkloadFailureDoesNotDownrankShortWorkload() {
        val feedback = TitanRuntimeFeedback()
        val planner = planner(feedback)
        val longRequest = InferenceRequest(
            prompt = "x".repeat(9_000),
            maxOutputTokens = 2_048
        )
        val shortRequest = InferenceRequest(
            prompt = "short interactive task",
            maxOutputTokens = 128
        )

        val firstLong = planner.plan(longRequest).getOrThrow()
        assertEquals("a-primary", firstLong.descriptor.id.value)
        planner.recordFailure(firstLong)

        val shortRoute = planner.plan(shortRequest).getOrThrow()
        assertEquals("a-primary", shortRoute.descriptor.id.value)

        val secondLong = planner.plan(longRequest).getOrThrow()
        assertEquals("b-fallback", secondLong.descriptor.id.value)
    }

    @Test
    fun slowHeavyFeedbackDoesNotLeakIntoCompactFeedbackBucket() {
        val feedback = TitanRuntimeFeedback(minSlowSamplesForPenalty = 2)
        val heavy = TitanRouteFeedbackKey.from(
            route(
                "brain",
                TitanInferenceWorkloadClass(TitanPromptWorkloadBand.LONG, TitanOutputWorkloadBand.LONG)
            )
        )
        val compact = TitanRouteFeedbackKey.from(
            route(
                "brain",
                TitanInferenceWorkloadClass(TitanPromptWorkloadBand.COMPACT, TitanOutputWorkloadBand.SHORT)
            )
        )
        val slow = InferenceResponse(
            modelId = ModelId("brain"),
            backendId = "test-backend",
            text = "ok",
            outputTokens = 20,
            tokensPerSecond = 1.0,
            generationTimeMs = 20_000L
        )

        feedback.recordSuccess(heavy, slow)
        feedback.recordSuccess(heavy, slow)

        assertEquals(1, feedback.snapshot(heavy).performancePenalty)
        assertEquals(0, feedback.snapshot(compact).performancePenalty)
        assertEquals(0, feedback.snapshot(compact).throughputSamples)
    }

    private fun planner(feedback: TitanRuntimeFeedback): TitanInferenceRoutePlanner {
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
            feedback = feedback,
            explorationEveryPlanningPasses = 1_000
        )
    }

    private fun route(id: String, workloadClass: TitanInferenceWorkloadClass): TitanInferenceRoute {
        val installed = installed(id)
        val source = StaticSource(installed.locator)
        return TitanInferenceRoute(
            descriptor = installed.descriptor,
            installed = installed,
            source = source,
            backend = object : InferenceBackend {
                override val id: String = "test-backend"
                override fun supports(model: InstalledModel): Boolean = true
                override fun infer(
                    model: InstalledModel,
                    source: ModelArtifactSource,
                    request: InferenceRequest
                ): Result<InferenceResponse> = error("not executed")
            },
            runtimeIdentity = ModelRuntimeIdentity.bind(installed, source),
            budget = null,
            selectedCapabilities = setOf(reasoning),
            workloadClass = workloadClass
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

    private fun request(promptChars: Int, outputTokens: Int): InferenceRequest = InferenceRequest(
        prompt = "x".repeat(promptChars),
        maxOutputTokens = outputTokens
    )

    private class StaticSource(override val locator: String) : ModelArtifactSource {
        override val displayName: String = "test.gguf"
        override val lengthBytes: Long? = 128L
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }
}
