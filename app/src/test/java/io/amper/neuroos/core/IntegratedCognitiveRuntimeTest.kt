package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegratedCognitiveRuntimeTest {
    private val capability = CapabilityId("phase251.test")

    private fun descriptor(): ToolDescriptor = ToolDescriptor(
        id = ToolId("phase251-provider"),
        name = "Phase251 provider",
        capability = capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description = "bounded integrated cognition test contract",
            acceptedValues = setOf("read"),
            maxLength = 32
        )
    )

    @Test
    fun sameStateProducesStableDigestAndBoundedNonAuthoritativeRenderer() {
        val runtime = AmperRuntime.reference()
        val descriptor = descriptor()

        val first = runtime.integratedCognition.capture(
            query = "Inspect the integrated state",
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor)
        )
        val second = runtime.integratedCognition.capture(
            query = "Inspect   the integrated state",
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor)
        )

        assertEquals(first.queryDigest, second.queryDigest)
        assertEquals(first.canonicalDigest, second.canonicalDigest)
        assertFalse(first.authorityBearing)
        assertFalse(first.readiness.authorityBearing)
        assertTrue(first.learningNeeds.isNotEmpty())

        val rendered = IntegratedCognitiveStateRenderer.render(first, 900)
        assertTrue(rendered.length <= 900)
        assertTrue(rendered.contains("<INTEGRATED_COGNITIVE_STATE>"))
        assertTrue(rendered.contains("digest=" + first.canonicalDigest))
        assertTrue(rendered.contains("authority=false"))
        assertTrue(rendered.contains("readiness overall="))
    }

    @Test
    fun cognitiveDigestChangesWhenPlanningEvidenceChanges() {
        val runtime = AmperRuntime.reference()
        val descriptor = descriptor()
        val before = runtime.integratedCognition.capture(
            query = "Read governed state",
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor)
        )

        runtime.epistemic.observe(
            EpistemicClaim(
                subject = "integrated",
                predicate = "state",
                value = "ready",
                confidence = 0.98,
                provenance = Provenance(
                    source = "phase251-test",
                    producer = "independent-evidence",
                    observedAtEpochMs = 10_000L,
                    confidence = 0.98
                )
            )
        )

        val after = runtime.integratedCognition.capture(
            query = "Read governed state",
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor)
        )

        assertEquals(before.queryDigest, after.queryDigest)
        assertNotEquals(before.canonicalDigest, after.canonicalDigest)
        assertTrue(after.context.epistemicBeliefs.isNotEmpty())
        assertTrue(after.context.semanticKnowledge.isNotEmpty())
    }

    @Test
    fun plannerAndIndependentCriticUseSameCognitiveStateDigestWithZeroTools() {
        val runtime = AmperRuntime.reference()
        var executions = 0
        val registry = InMemoryToolRegistry().also { registry ->
            registry.register(
                object : ToolProvider {
                    override val descriptor: ToolDescriptor = descriptor()
                    override fun execute(input: String): Result<String> = runCatching {
                        executions += 1
                        "executed:" + input
                    }
                }
            )
        }
        val audit = InMemoryToolAuditLog()
        val actions = runtime.actionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(setOf(capability)),
                registry = registry,
                audit = audit
            )
        )
        val requests = mutableListOf<InferenceRequest>()
        val inference = CognitiveInferencePort { request ->
            requests += request
            val critic = request.prompt.contains("<AMPER_PLAN_CRITIC_V1>")
            Result.success(
                InferenceResponse(
                    modelId = ModelId(if (critic) "phase255-critic" else "phase254-planner"),
                    backendId = if (critic) "phase255-critic" else "phase254-planner",
                    text = if (critic) {
                        """
                        <AMPER_PLAN_CRITIC_V1>
                        verdict=ACCEPT
                        critique=Integrated cognitive state is consistent with the bounded plan
                        </AMPER_PLAN_CRITIC_V1>
                        """.trimIndent()
                    } else {
                        """
                        <AMPER_PLAN_V1>
                        step.1.capability=phase251.test
                        step.1.reason=Read the bounded integrated state
                        step.1.input=read
                        </AMPER_PLAN_V1>
                        """.trimIndent()
                    },
                    selectedCapabilities = setOf(TitanCapabilities.REASONING)
                )
            )
        }
        val planner = SovereignPlanCoordinator(
            runtime = runtime,
            inference = inference,
            actions = actions,
            advertisedCapabilities = setOf(capability),
            criticInference = inference
        )

        val plan = planner.create(
            runtime.conversations.primary(),
            "Read the integrated cognitive state"
        ).getOrThrow()

        assertEquals(2, requests.size)
        val plannerPrompt = requests[0].prompt
        val criticPrompt = requests[1].prompt
        assertTrue(plannerPrompt.contains("<INTEGRATED_COGNITIVE_STATE>"))
        assertTrue(criticPrompt.contains("<COGNITIVE_STATE_BINDING>"))

        val plannerDigest = requireNotNull(DIGEST.find(plannerPrompt)?.groupValues?.get(1))
        val criticDigest = requireNotNull(DIGEST.find(criticPrompt)?.groupValues?.get(1))
        assertEquals(plannerDigest, criticDigest)
        assertEquals(ToolId("phase251-provider"), plan.steps.single().boundToolId)
        assertEquals(0, executions)
        assertTrue(audit.snapshot().isEmpty())
    }

    companion object {
        private val DIGEST = Regex("digest=([0-9a-f]{64})")
    }
}
