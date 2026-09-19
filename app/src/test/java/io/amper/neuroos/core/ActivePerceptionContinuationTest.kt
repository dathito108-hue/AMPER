package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivePerceptionContinuationTest {
    private val capability = CapabilityId("phase291.test")

    private fun descriptor(): ToolDescriptor = ToolDescriptor(
        id = ToolId("phase291-provider"),
        name = "Phase291 provider",
        capability = capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description = "bounded active-perception continuation contract",
            acceptedValues = setOf("read"),
            maxLength = 32
        )
    )

    private fun baseState(): IntegratedCognitiveStatePacket {
        val runtime = AmperRuntime.reference()
        return runtime.integratedCognition.capture(
            query = "Use the current sensor state",
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor())
        )
    }

    private fun observeState(base: IntegratedCognitiveStatePacket): IntegratedCognitiveStatePacket =
        base.copy(
            perceptualEvidence = listOf(
                PerceptualGroundingEvidence(
                    modality = PerceptionModality.SENSOR,
                    summary = "sensor state is stale",
                    source = "phase291-sensor",
                    producer = "phase291-test",
                    confidence = 0.95,
                    observedAtEpochMs = 1L,
                    ageMs = 500_000L,
                    freshness = PerceptualFreshness.STALE,
                    queryRelevance = 0.95
                )
            ),
            learningNeeds = emptyList(),
            readiness = base.readiness.copy(
                worldConfidence = 0.25,
                uncertainty = 0.85,
                learningPressure = 0.10,
                overallReadiness = 0.25
            )
        )

    private fun readyState(base: IntegratedCognitiveStatePacket): IntegratedCognitiveStatePacket =
        base.copy(
            perceptualEvidence = listOf(
                PerceptualGroundingEvidence(
                    modality = PerceptionModality.SENSOR,
                    summary = "sensor state is fresh",
                    source = "phase291-sensor",
                    producer = "phase291-test",
                    confidence = 0.98,
                    observedAtEpochMs = 10_000L,
                    ageMs = 0L,
                    freshness = PerceptualFreshness.FRESH,
                    queryRelevance = 0.95
                )
            ),
            learningNeeds = emptyList(),
            readiness = base.readiness.copy(
                epistemicConfidence = 0.90,
                worldConfidence = 0.90,
                skillConfidence = 0.82,
                competenceConfidence = 0.80,
                uncertainty = 0.10,
                learningPressure = 0.10,
                overallReadiness = 0.88
            )
        )

    @Test
    fun stalePerceptionCanAcquireIngestRecaptureAndPlanInOneBoundedRun() {
        val base = baseState()
        val observe = observeState(base)
        val ready = readyState(base)
        var current = observe
        var acquisitionCalls = 0
        var ingestCalls = 0
        var planCalls = 0
        var capturedRequest: CognitiveExecutiveObservationRequest? = null

        val observation = object : CognitiveExecutiveObservationPort {
            override fun acquire(
                request: CognitiveExecutiveObservationRequest
            ): Result<Percept> {
                acquisitionCalls += 1
                capturedRequest = request
                return Result.success(
                    Percept(
                        id = "phase291-fresh-sensor",
                        modality = PerceptionModality.SENSOR,
                        payload = "light=55.0;proximity=0.0",
                        salience = 0.90,
                        provenance = Provenance(
                            source = "phase291-active-sensor",
                            producer = "phase291-test",
                            observedAtEpochMs = 10_000L,
                            confidence = 0.98
                        )
                    )
                )
            }
        }
        val bus = object : PerceptionBus {
            override fun ingest(percept: Percept): CognitiveEvent {
                ingestCalls += 1
                current = ready
                return CognitiveEvent(
                    id = percept.id,
                    topic = "perception.sensor",
                    payload = percept.payload,
                    salience = percept.salience
                )
            }
        }
        val executive = AutonomousCognitiveExecutive(
            stateSource = dynamicSource { current },
            allowedCapabilities = setOf(capability),
            descriptors = { listOf(descriptor()) },
            createPlan = { conversationId, goal ->
                planCalls += 1
                Result.success(testPlan(conversationId, goal, "phase291-plan"))
            },
            practiceOne = {
                Result.failure(IllegalStateException("practice must not run"))
            },
            observation = observation,
            perceptionBus = bus
        )

        val result = executive.runBounded(
            conversationId = ConversationId("phase291-conversation"),
            userGoal = "Use the current sensor state",
            maxCycles = 3
        ).getOrThrow()

        assertEquals(2, result.cycles.size)
        val first = result.cycles[0] as CognitiveExecutiveCycleResult.ObservationRequired
        assertTrue(first.refreshed)
        assertEquals("phase291-fresh-sensor", first.acquiredPerceptId)
        assertEquals(PerceptionModality.SENSOR, first.acquiredModality)
        assertEquals(null, first.acquisitionFailureCode)
        assertTrue(result.cycles[1] is CognitiveExecutiveCycleResult.Planned)
        assertEquals(CognitiveExecutiveAction.PLAN, result.terminalAction)
        assertEquals(1, acquisitionCalls)
        assertEquals(1, ingestCalls)
        assertEquals(1, planCalls)

        val request = requireNotNull(capturedRequest)
        assertEquals(listOf(PerceptionModality.SENSOR), request.preferredModalities)
        assertEquals(first.directive.cognitiveStateDigest, request.cognitiveStateDigest)
        assertEquals(first.directive.executionContextDigest, request.executionContextDigest)
        assertFalse(request.authorityBearing)
    }

    @Test
    fun unavailableActivePerceptionStopsAtObservationWithoutPlanningOrLooping() {
        val base = baseState()
        val observe = observeState(base)
        var acquisitionCalls = 0
        var ingestCalls = 0
        var planCalls = 0

        val executive = AutonomousCognitiveExecutive(
            stateSource = dynamicSource { observe },
            allowedCapabilities = setOf(capability),
            descriptors = { listOf(descriptor()) },
            createPlan = { _, _ ->
                planCalls += 1
                Result.failure(IllegalStateException("planner must not run"))
            },
            practiceOne = {
                Result.failure(IllegalStateException("practice must not run"))
            },
            observation = object : CognitiveExecutiveObservationPort {
                override fun acquire(
                    request: CognitiveExecutiveObservationRequest
                ): Result<Percept> {
                    acquisitionCalls += 1
                    return Result.failure(IllegalStateException("sensor unavailable"))
                }
            },
            perceptionBus = object : PerceptionBus {
                override fun ingest(percept: Percept): CognitiveEvent {
                    ingestCalls += 1
                    error("failed acquisition must not be ingested")
                }
            }
        )

        val result = executive.runBounded(
            conversationId = ConversationId("phase292-conversation"),
            userGoal = "Use the current sensor state",
            maxCycles = 4
        ).getOrThrow()

        assertEquals(1, result.cycles.size)
        val observation = result.cycles.single() as CognitiveExecutiveCycleResult.ObservationRequired
        assertFalse(observation.refreshed)
        assertNotNull(observation.acquisitionFailureCode)
        assertEquals(CognitiveExecutiveAction.OBSERVE, result.terminalAction)
        assertEquals(1, acquisitionCalls)
        assertEquals(0, ingestCalls)
        assertEquals(0, planCalls)
    }

    @Test
    fun selfPublishingObservationPortIsNotDoubleIngested() {
        val base = baseState()
        val observe = observeState(base)
        val ready = readyState(base)
        var current = observe
        var sinkCalls = 0
        var planCalls = 0

        val executive = AutonomousCognitiveExecutive(
            stateSource = dynamicSource { current },
            allowedCapabilities = setOf(capability),
            descriptors = { listOf(descriptor()) },
            createPlan = { conversationId, goal ->
                planCalls += 1
                Result.success(testPlan(conversationId, goal, "phase293-plan"))
            },
            practiceOne = {
                Result.failure(IllegalStateException("practice must not run"))
            },
            observation = object : CognitiveExecutiveObservationPort {
                override val publishesToPerceptionBus: Boolean
                    get() = true

                override fun acquire(
                    request: CognitiveExecutiveObservationRequest
                ): Result<Percept> {
                    current = ready
                    return Result.success(
                        Percept(
                            id = "phase293-published-sensor",
                            modality = PerceptionModality.SENSOR,
                            payload = "light=60.0",
                            provenance = Provenance(
                                source = "phase293-active-sensor",
                                producer = "phase293-test",
                                confidence = 0.98
                            )
                        )
                    )
                }
            },
            perceptionBus = object : PerceptionBus {
                override fun ingest(percept: Percept): CognitiveEvent {
                    sinkCalls += 1
                    error("self-publishing observation must not be ingested twice")
                }
            }
        )

        val result = executive.runBounded(
            conversationId = ConversationId("phase293-conversation"),
            userGoal = "Use the current sensor state",
            maxCycles = 3
        ).getOrThrow()

        assertEquals(2, result.cycles.size)
        assertTrue(
            (result.cycles.first() as CognitiveExecutiveCycleResult.ObservationRequired).refreshed
        )
        assertTrue(result.cycles.last() is CognitiveExecutiveCycleResult.Planned)
        assertEquals(0, sinkCalls)
        assertEquals(1, planCalls)
    }

    private fun testPlan(
        conversationId: ConversationId,
        goal: String,
        id: String
    ): SovereignPlan = SovereignPlan(
        id = PlanId(id),
        conversationId = conversationId,
        goal = goal,
        steps = listOf(
            SovereignPlanStep(
                index = 1,
                requestId = ActionRequestId("$id-request"),
                capability = capability,
                reason = "Plan after fresh grounded perception",
                input = "read",
                boundToolId = descriptor().id,
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        ),
        planningBackendId = "phase291-planner"
    )

    private fun dynamicSource(
        supplier: () -> IntegratedCognitiveStatePacket
    ): IntegratedCognitiveStateSource = object : IntegratedCognitiveStateSource {
        override fun capture(
            query: String,
            allowedCapabilities: Set<CapabilityId>,
            descriptors: Collection<ToolDescriptor>
        ): IntegratedCognitiveStatePacket = supplier()
    }
}
