package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerceptualGroundingTest {
    private val capability = CapabilityId("phase261.test")

    @Test
    fun worldPerceptionBecomesTypedFreshnessAwareGroundingWithoutAuthority() {
        val world = CanonicalWorldModel()
        world.observe(
            CognitiveEvent(
                topic = "perception.sensor",
                payload = "light=42.0;proximity=1.0",
                salience = 0.8
            ),
            Provenance(
                source = "android-sensor-fusion",
                producer = "phase261-test",
                observedAtEpochMs = 9_900L,
                confidence = 0.97
            )
        )
        world.observe(
            CognitiveEvent(
                topic = "perception.camera",
                payload = "width=320;height=240;meanLuma=0.5",
                salience = 0.9
            ),
            Provenance(
                source = "android-camera-preview",
                producer = "phase261-test",
                observedAtEpochMs = 7_000L,
                confidence = 0.92
            )
        )
        world.observe(
            CognitiveEvent(
                topic = "intent",
                payload = "unrelated ordinary world fact",
                salience = 1.0
            ),
            Provenance(
                source = "user-intent",
                producer = "test",
                observedAtEpochMs = 9_999L,
                confidence = 1.0
            )
        )

        val source = WorldBackedPerceptualGroundingSource(
            world = world,
            clock = { 10_000L },
            freshAfterMs = 500L,
            staleAfterMs = 1_000L
        )
        val evidence = source.capture("What do the current sensors and camera show?")

        assertEquals(2, evidence.size)
        val sensor = evidence.single { it.modality == PerceptionModality.SENSOR }
        val camera = evidence.single { it.modality == PerceptionModality.CAMERA }

        assertEquals(PerceptualFreshness.FRESH, sensor.freshness)
        assertTrue(sensor.planningEligible)
        assertTrue(sensor.queryRelevance >= 0.85)
        assertFalse(sensor.authorityBearing)

        assertEquals(PerceptualFreshness.STALE, camera.freshness)
        assertFalse(camera.planningEligible)
        assertFalse(camera.authorityBearing)
    }

    @Test
    fun integratedCognitiveDigestBindsNewLivePerceptionForSameQuery() {
        val runtime = AmperRuntime.reference()
        val descriptor = descriptor()
        val query = "Plan the next bounded action"

        val before = runtime.integratedCognition.capture(
            query = query,
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor)
        )

        runtime.perception.ingest(
            Percept(
                id = "phase264-sensor",
                modality = PerceptionModality.SENSOR,
                payload = "accelerometer=0.1,9.7,0.2;light=42.0",
                salience = 0.8,
                provenance = Provenance(
                    source = "android-sensor-fusion",
                    producer = "phase264-test",
                    observedAtEpochMs = System.currentTimeMillis(),
                    confidence = 0.98
                )
            )
        )

        val after = runtime.integratedCognition.capture(
            query = query,
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor)
        )

        assertEquals(before.queryDigest, after.queryDigest)
        assertNotEquals(before.canonicalDigest, after.canonicalDigest)
        assertTrue(after.perceptualEvidence.any { it.modality == PerceptionModality.SENSOR })
        assertTrue(after.perceptualEvidence.any { it.planningEligible })
        assertFalse(after.authorityBearing)
    }

    @Test
    fun plannerAndCriticShareExactPerceptSnapshotAndNoToolRuns() {
        val runtime = AmperRuntime.reference()
        runtime.perception.ingest(
            Percept(
                id = "phase265-screen",
                modality = PerceptionModality.SCREEN,
                payload = "width=1080;height=2400;orientation=portrait;meanLuma=0.42",
                salience = 0.9,
                provenance = Provenance(
                    source = "android-app-screen",
                    producer = "phase265-test",
                    observedAtEpochMs = System.currentTimeMillis(),
                    confidence = 0.95
                )
            )
        )

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
                    modelId = ModelId(if (critic) "phase265-critic" else "phase264-planner"),
                    backendId = if (critic) "phase265-critic" else "phase264-planner",
                    text = if (critic) {
                        """
                        <AMPER_PLAN_CRITIC_V1>
                        verdict=ACCEPT
                        critique=Perceptual state is bound to the same cognitive decision
                        </AMPER_PLAN_CRITIC_V1>
                        """.trimIndent()
                    } else {
                        """
                        <AMPER_PLAN_V1>
                        step.1.capability=phase261.test
                        step.1.reason=Read the bounded value using current grounded state
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
            "Use the current screen state to plan the bounded read"
        ).getOrThrow()

        assertEquals(2, requests.size)
        val plannerPrompt = requests[0].prompt
        val criticPrompt = requests[1].prompt
        assertTrue(plannerPrompt.contains("perception modality=SCREEN"))
        assertTrue(plannerPrompt.contains("width=1080;height=2400"))
        assertTrue(criticPrompt.contains("PERCEPT modality=SCREEN"))
        assertTrue(criticPrompt.contains("width=1080;height=2400"))

        val plannerDigest = requireNotNull(COGNITIVE_DIGEST.find(plannerPrompt)?.groupValues?.get(1))
        val criticDigest = requireNotNull(CRITIC_DIGEST.find(criticPrompt)?.groupValues?.get(1))
        assertEquals(plannerDigest, criticDigest)
        assertEquals(ToolId("phase261-provider"), plan.steps.single().boundToolId)
        assertEquals(0, executions)
        assertTrue(audit.snapshot().isEmpty())
    }

    private fun descriptor(): ToolDescriptor = ToolDescriptor(
        id = ToolId("phase261-provider"),
        name = "Phase261 provider",
        capability = capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description = "bounded perceptual-grounding test contract",
            acceptedValues = setOf("read"),
            maxLength = 32
        )
    )

    companion object {
        private val COGNITIVE_DIGEST = Regex(
            "<INTEGRATED_COGNITIVE_STATE>[\\s\\S]*?digest=([0-9a-f]{64})"
        )
        private val CRITIC_DIGEST = Regex(
            "<COGNITIVE_STATE_BINDING>[\\s\\S]*?digest=([0-9a-f]{64})"
        )
    }
}
