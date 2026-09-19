package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousCognitiveExecutiveTest {
    private val capability = CapabilityId("phase276.test")

    private fun descriptor(): ToolDescriptor = ToolDescriptor(
        id = ToolId("phase276-provider"),
        name = "Phase276 provider",
        capability = capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description = "bounded autonomous executive test contract",
            acceptedValues = setOf("read"),
            maxLength = 32
        )
    )

    private fun baseState(): IntegratedCognitiveStatePacket {
        val runtime = AmperRuntime.reference()
        return runtime.integratedCognition.capture(
            query = "Handle the current bounded goal",
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor())
        )
    }

    private fun need(
        kind: LearningNeedKind,
        severity: Double,
        confidence: Double
    ): LearningNeed = LearningNeed(
        capability = capability,
        kind = kind,
        severity = severity,
        evidenceConfidence = confidence,
        rationale = "phase276 deterministic executive test need"
    )

    @Test
    fun policyDeterministicallySelectsObserveEvolvePracticeAndPlan() {
        val base = baseState()

        val stale = PerceptualGroundingEvidence(
            modality = PerceptionModality.SCREEN,
            summary = "screen state may no longer match the live device",
            source = "phase276-screen",
            producer = "phase276-test",
            confidence = 0.95,
            observedAtEpochMs = 1L,
            ageMs = 300_000L,
            freshness = PerceptualFreshness.STALE,
            queryRelevance = 0.90
        )
        val observe = base.copy(
            perceptualEvidence = listOf(stale),
            learningNeeds = listOf(need(LearningNeedKind.EXECUTION_RELIABILITY, 0.95, 0.90)),
            readiness = base.readiness.copy(
                worldConfidence = 0.30,
                uncertainty = 0.80,
                learningPressure = 0.95,
                overallReadiness = 0.25
            )
        )
        val evolve = base.copy(
            perceptualEvidence = emptyList(),
            learningNeeds = listOf(need(LearningNeedKind.EXECUTION_RELIABILITY, 0.90, 0.80)),
            readiness = base.readiness.copy(
                worldConfidence = 0.80,
                uncertainty = 0.20,
                learningPressure = 0.90,
                overallReadiness = 0.45
            )
        )
        val practice = base.copy(
            perceptualEvidence = emptyList(),
            learningNeeds = listOf(need(LearningNeedKind.CONTRACT_PRACTICE, 0.72, 0.30)),
            readiness = base.readiness.copy(
                worldConfidence = 0.80,
                uncertainty = 0.20,
                learningPressure = 0.72,
                overallReadiness = 0.55
            )
        )
        val ready = base.copy(
            perceptualEvidence = emptyList(),
            learningNeeds = emptyList(),
            readiness = base.readiness.copy(
                epistemicConfidence = 0.90,
                worldConfidence = 0.90,
                skillConfidence = 0.82,
                competenceConfidence = 0.80,
                uncertainty = 0.10,
                learningPressure = 0.10,
                overallReadiness = 0.86
            )
        )

        val observeDirective = AutonomousCognitiveExecutivePolicy.decide(observe, true)
        val evolveDirective = AutonomousCognitiveExecutivePolicy.decide(evolve, true)
        val practiceDirective = AutonomousCognitiveExecutivePolicy.decide(practice, true)
        val fallbackPractice = AutonomousCognitiveExecutivePolicy.decide(evolve, false)
        val planDirective = AutonomousCognitiveExecutivePolicy.decide(ready, true)

        assertEquals(CognitiveExecutiveAction.OBSERVE, observeDirective.action)
        assertEquals(CognitiveExecutiveAction.EVOLVE, evolveDirective.action)
        assertEquals(capability, evolveDirective.triggeringCapability)
        assertEquals(CognitiveExecutiveAction.PRACTICE, practiceDirective.action)
        assertEquals(CognitiveExecutiveAction.PRACTICE, fallbackPractice.action)
        assertEquals(CognitiveExecutiveAction.PLAN, planDirective.action)
        listOf(
            observeDirective,
            evolveDirective,
            practiceDirective,
            fallbackPractice,
            planDirective
        ).forEach {
            assertFalse(it.authorityBearing)
            assertEquals(64, it.cognitiveStateDigest.length)
            assertEquals(64, it.executionContextDigest.length)
        }
    }

    @Test
    fun boundedExecutiveCanPracticeThenRecaptureAndPlanWithoutToolExecution() {
        val base = baseState()
        val practiceState = base.copy(
            learningNeeds = listOf(need(LearningNeedKind.CONTRACT_PRACTICE, 0.75, 0.25)),
            readiness = base.readiness.copy(
                uncertainty = 0.20,
                learningPressure = 0.75,
                overallReadiness = 0.52
            )
        )
        val readyState = base.copy(
            learningNeeds = emptyList(),
            readiness = base.readiness.copy(
                epistemicConfidence = 0.90,
                worldConfidence = 0.90,
                skillConfidence = 0.85,
                competenceConfidence = 0.82,
                uncertainty = 0.10,
                learningPressure = 0.10,
                overallReadiness = 0.88
            )
        )

        var current = practiceState
        var practiceCalls = 0
        var planCalls = 0
        val task = AutonomousPracticeTask(
            id = PracticeTaskId("phase276-practice"),
            capability = capability,
            kind = AutonomousPracticeKind.CONTRACT_PLAN,
            objective = "Practice the bounded live contract",
            priority = 0.75,
            sourceNeeds = setOf(LearningNeedKind.CONTRACT_PRACTICE)
        )
        val evidence = AutonomousPracticeEvidence(
            id = MemoryId("phase276-practice-evidence"),
            taskId = task.id,
            capability = capability,
            kind = task.kind,
            verdict = AutonomousPracticeVerdict.PASS,
            validatedStepCount = 1,
            observedAtEpochMs = 1L
        )

        val executive = AutonomousCognitiveExecutive(
            stateSource = object : IntegratedCognitiveStateSource {
                override fun capture(
                    query: String,
                    allowedCapabilities: Set<CapabilityId>,
                    descriptors: Collection<ToolDescriptor>
                ): IntegratedCognitiveStatePacket = current
            },
            allowedCapabilities = setOf(capability),
            descriptors = { listOf(descriptor()) },
            createPlan = { conversationId, goal ->
                planCalls += 1
                Result.success(
                    SovereignPlan(
                        conversationId = conversationId,
                        goal = goal,
                        steps = listOf(
                            SovereignPlanStep(
                                index = 1,
                                requestId = ActionRequestId("phase276-plan-request"),
                                capability = capability,
                                reason = "Plan only; no tool execution occurs in the executive",
                                input = "read",
                                boundToolId = ToolId("phase276-provider"),
                                boundSideEffect = ToolSideEffect.READ_ONLY
                            )
                        ),
                        planningBackendId = "phase276-planner"
                    )
                )
            },
            practiceOne = {
                practiceCalls += 1
                current = readyState
                Result.success(
                    AutonomousLearningCycleResult.Assessed(
                        task = task,
                        evidence = evidence,
                        backendId = "phase276-practice",
                        modelId = ModelId("phase276-practice-model")
                    )
                )
            }
        )

        val result = executive.runBounded(
            conversationId = ConversationId("phase276-conversation"),
            userGoal = "Handle the bounded goal",
            maxCycles = 3
        ).getOrThrow()

        assertEquals(2, result.cycles.size)
        assertTrue(result.cycles[0] is CognitiveExecutiveCycleResult.Practiced)
        assertTrue(result.cycles[1] is CognitiveExecutiveCycleResult.Planned)
        assertEquals(CognitiveExecutiveAction.PLAN, result.terminalAction)
        assertEquals(1, practiceCalls)
        assertEquals(1, planCalls)
        assertFalse(result.authorityBearing)
    }

    @Test
    fun observationPathConsumesNoPlanningPracticeOrEvolutionWork() {
        val base = baseState()
        val observeState = base.copy(
            perceptualEvidence = listOf(
                PerceptualGroundingEvidence(
                    modality = PerceptionModality.CAMERA,
                    summary = "camera summary is stale",
                    source = "phase277-camera",
                    producer = "phase277-test",
                    confidence = 0.90,
                    observedAtEpochMs = 1L,
                    ageMs = 400_000L,
                    freshness = PerceptualFreshness.STALE,
                    queryRelevance = 0.90
                )
            ),
            readiness = base.readiness.copy(
                worldConfidence = 0.25,
                uncertainty = 0.85,
                learningPressure = 0.80,
                overallReadiness = 0.20
            )
        )
        var plannerCalls = 0
        var practiceCalls = 0
        var evolutionCalls = 0

        val executive = AutonomousCognitiveExecutive(
            stateSource = fixedSource(observeState),
            allowedCapabilities = setOf(capability),
            descriptors = { listOf(descriptor()) },
            createPlan = { _, _ ->
                plannerCalls += 1
                Result.failure(IllegalStateException("planner must not run"))
            },
            practiceOne = {
                practiceCalls += 1
                Result.failure(IllegalStateException("practice must not run"))
            },
            evolution = CognitiveExecutiveEvolutionPort {
                evolutionCalls += 1
                Result.failure(IllegalStateException("evolution must not run"))
            }
        )

        val result = executive.step(
            ConversationId("phase277-conversation"),
            "Use the current camera state"
        ).getOrThrow()

        assertTrue(result is CognitiveExecutiveCycleResult.ObservationRequired)
        val observation = result as CognitiveExecutiveCycleResult.ObservationRequired
        assertTrue(PerceptionModality.CAMERA in observation.staleRelevantModalities)
        assertEquals(0, plannerCalls)
        assertEquals(0, practiceCalls)
        assertEquals(0, evolutionCalls)
    }

    @Test
    fun evidencedReliabilityWeaknessCanInvokeExactlyOneInjectedEvolutionCycle() {
        val base = baseState()
        val evolveState = base.copy(
            learningNeeds = listOf(need(LearningNeedKind.EXECUTION_RELIABILITY, 0.92, 0.85)),
            perceptualEvidence = emptyList(),
            readiness = base.readiness.copy(
                worldConfidence = 0.80,
                uncertainty = 0.20,
                learningPressure = 0.92,
                overallReadiness = 0.42
            )
        )
        var evolutionCalls = 0
        val evolutionPort = CognitiveExecutiveEvolutionPort {
            evolutionCalls += 1
            Result.success(
                EvolutionAutonomyRunResult(
                    id = EvolutionAutonomyRunId("phase279-test-run"),
                    cycles = listOf(
                        EvolutionAutonomyCycleResult(
                            index = 1,
                            campaignId = null,
                            baselineRevision = "phase279-baseline",
                            baselineArtifactDigest = "c".repeat(64),
                            stage = EvolutionAutonomyCycleStage.NO_GAP
                        )
                    ),
                    startedAtEpochMs = 1L,
                    completedAtEpochMs = 1L
                )
            )
        }
        val executive = AutonomousCognitiveExecutive(
            stateSource = fixedSource(evolveState),
            allowedCapabilities = setOf(capability),
            descriptors = { listOf(descriptor()) },
            createPlan = { _, _ ->
                Result.failure(IllegalStateException("planner must not run"))
            },
            practiceOne = {
                Result.failure(IllegalStateException("practice must not run"))
            },
            evolution = evolutionPort
        )

        val result = executive.step(
            ConversationId("phase279-conversation"),
            "Improve the evidenced weak capability"
        ).getOrThrow()

        assertTrue(result is CognitiveExecutiveCycleResult.Evolved)
        assertEquals(1, evolutionCalls)
        val evolved = result as CognitiveExecutiveCycleResult.Evolved
        assertEquals(EvolutionAutonomyCycleStage.NO_GAP, evolved.run.terminalStage)
        assertEquals(CognitiveExecutiveAction.EVOLVE, evolved.directive.action)
    }

    private fun fixedSource(
        packet: IntegratedCognitiveStatePacket
    ): IntegratedCognitiveStateSource = object : IntegratedCognitiveStateSource {
        override fun capture(
            query: String,
            allowedCapabilities: Set<CapabilityId>,
            descriptors: Collection<ToolDescriptor>
        ): IntegratedCognitiveStatePacket = packet
    }
}
