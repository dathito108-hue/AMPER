package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentGoalExecutiveTest {
    private val capability = CapabilityId("phase281.test")

    private fun descriptor(): ToolDescriptor = ToolDescriptor(
        id = ToolId("phase281-provider"),
        name = "Phase281 provider",
        capability = capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description = "bounded persistent goal executive test contract",
            acceptedValues = setOf("read"),
            maxLength = 32
        )
    )

    @Test
    fun checkpointCodecRoundTripsAcrossFreshStoreInstance() {
        val memory = InMemoryMemoryOs()
        val firstStore = MemoryBackedPersistentGoalExecutiveStore(memory)
        val planned = PersistentGoalExecutiveCheckpoint(
            sourceGoalId = "phase282-goal",
            objective = "Preserve mục tiêu\nacross restart",
            conversationId = ConversationId("phase282-conversation"),
            priority = 0.88,
            stage = PersistentGoalExecutiveStage.PLANNED,
            attemptCount = 2,
            lastAction = CognitiveExecutiveAction.PLAN,
            lastCognitiveStateDigest = "a".repeat(64),
            lastExecutionContextDigest = "b".repeat(64),
            plannedPlanId = PlanId("phase282-plan"),
            updatedAtEpochMs = 42L
        )

        firstStore.save(planned)
        val afterRestart = MemoryBackedPersistentGoalExecutiveStore(memory).load()

        assertEquals(planned, afterRestart)
        assertFalse(requireNotNull(afterRestart).authorityBearing)
    }

    @Test
    fun plannedGoalIsPersistedAndCannotDuplicateUntilCompletionAcknowledged() {
        val runtime = AmperRuntime.reference()
        val goalText = "Complete the persistent executive test goal"
        runtime.tick(goalText)
        val base = runtime.integratedCognition.capture(
            query = goalText,
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor())
        )
        val ready = readyState(base)

        var planCalls = 0
        val planId = PlanId("phase283-plan")
        val executive = AutonomousCognitiveExecutive(
            stateSource = fixedSource { ready },
            allowedCapabilities = setOf(capability),
            descriptors = { listOf(descriptor()) },
            createPlan = { conversationId, goal ->
                planCalls += 1
                Result.success(
                    SovereignPlan(
                        id = planId,
                        conversationId = conversationId,
                        goal = goal,
                        steps = listOf(
                            SovereignPlanStep(
                                index = 1,
                                requestId = ActionRequestId("phase283-request"),
                                capability = capability,
                                reason = "Create a durable plan handoff only",
                                input = "read",
                                boundToolId = descriptor().id,
                                boundSideEffect = ToolSideEffect.READ_ONLY
                            )
                        ),
                        planningBackendId = "phase283-planner"
                    )
                )
            },
            practiceOne = {
                Result.failure(IllegalStateException("practice must not run for ready state"))
            }
        )
        val coordinator = PersistentGoalExecutiveCoordinator(
            context = runtime.context,
            executive = executive,
            store = runtime.persistentGoalExecutiveStore,
            plans = runtime.plans,
            clock = { 100L }
        )
        val conversationId = runtime.conversations.primary()

        val first = coordinator.runNext(conversationId).getOrThrow()
        assertTrue(first is PersistentGoalExecutiveResult.Ran)
        val firstCheckpoint = (first as PersistentGoalExecutiveResult.Ran).checkpoint
        assertEquals(PersistentGoalExecutiveStage.PLANNED, firstCheckpoint.stage)
        assertEquals(planId, firstCheckpoint.plannedPlanId)
        assertEquals(CognitiveExecutiveAction.PLAN, firstCheckpoint.lastAction)
        assertEquals(1, planCalls)
        assertNotNull(runtime.plans.load(planId))

        val duplicateAttempt = coordinator.runNext(conversationId).getOrThrow()
        assertTrue(duplicateAttempt is PersistentGoalExecutiveResult.Deferred)
        assertEquals(1, planCalls)

        val completed = coordinator.completePlanned(planId).getOrThrow()
        assertEquals(PersistentGoalExecutiveStage.COMPLETED, completed.stage)

        val afterCompletion = coordinator.runNext(conversationId).getOrThrow()
        assertTrue(afterCompletion is PersistentGoalExecutiveResult.NoGoal)
        assertEquals(1, planCalls)
    }

    @Test
    fun waitingObservationCheckpointResumesWhenGroundedStateChanges() {
        val runtime = AmperRuntime.reference()
        val goalText = "Use the current camera state for the bounded goal"
        runtime.tick(goalText)
        val base = runtime.integratedCognition.capture(
            query = goalText,
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor())
        )
        val observe = base.copy(
            perceptualEvidence = listOf(
                PerceptualGroundingEvidence(
                    modality = PerceptionModality.CAMERA,
                    summary = "camera state is stale",
                    source = "phase284-camera",
                    producer = "phase284-test",
                    confidence = 0.95,
                    observedAtEpochMs = 1L,
                    ageMs = 500_000L,
                    freshness = PerceptualFreshness.STALE,
                    queryRelevance = 0.95
                )
            ),
            readiness = base.readiness.copy(
                worldConfidence = 0.25,
                uncertainty = 0.85,
                learningPressure = 0.20,
                overallReadiness = 0.25
            )
        )
        val ready = readyState(base)
        var current = observe
        var planCalls = 0
        val planId = PlanId("phase284-plan")

        val executive = AutonomousCognitiveExecutive(
            stateSource = fixedSource { current },
            allowedCapabilities = setOf(capability),
            descriptors = { listOf(descriptor()) },
            createPlan = { conversationId, goal ->
                planCalls += 1
                Result.success(
                    SovereignPlan(
                        id = planId,
                        conversationId = conversationId,
                        goal = goal,
                        steps = listOf(
                            SovereignPlanStep(
                                index = 1,
                                requestId = ActionRequestId("phase284-request"),
                                capability = capability,
                                reason = "Plan after fresh grounded evidence arrives",
                                input = "read",
                                boundToolId = descriptor().id,
                                boundSideEffect = ToolSideEffect.READ_ONLY
                            )
                        ),
                        planningBackendId = "phase284-planner"
                    )
                )
            },
            practiceOne = {
                Result.failure(IllegalStateException("practice must not run"))
            }
        )
        val coordinator = PersistentGoalExecutiveCoordinator(
            context = runtime.context,
            executive = executive,
            store = runtime.persistentGoalExecutiveStore,
            plans = runtime.plans
        )
        val conversationId = runtime.conversations.primary()

        val first = coordinator.runNext(conversationId).getOrThrow()
        assertTrue(first is PersistentGoalExecutiveResult.Ran)
        assertEquals(
            PersistentGoalExecutiveStage.WAITING_OBSERVATION,
            (first as PersistentGoalExecutiveResult.Ran).checkpoint.stage
        )
        assertEquals(0, planCalls)

        current = ready
        val resumed = coordinator.runNext(conversationId).getOrThrow()
        assertTrue(resumed is PersistentGoalExecutiveResult.Ran)
        val resumedCheckpoint = (resumed as PersistentGoalExecutiveResult.Ran).checkpoint
        assertEquals(PersistentGoalExecutiveStage.PLANNED, resumedCheckpoint.stage)
        assertEquals(planId, resumedCheckpoint.plannedPlanId)
        assertEquals(1, planCalls)
        assertNotNull(runtime.plans.load(planId))
    }

    private fun readyState(
        base: IntegratedCognitiveStatePacket
    ): IntegratedCognitiveStatePacket = base.copy(
        perceptualEvidence = emptyList(),
        learningNeeds = emptyList(),
        readiness = base.readiness.copy(
            epistemicConfidence = 0.90,
            worldConfidence = 0.90,
            skillConfidence = 0.85,
            competenceConfidence = 0.80,
            uncertainty = 0.10,
            learningPressure = 0.10,
            overallReadiness = 0.88
        )
    )

    private fun fixedSource(
        supplier: () -> IntegratedCognitiveStatePacket
    ): IntegratedCognitiveStateSource = object : IntegratedCognitiveStateSource {
        override fun capture(
            query: String,
            allowedCapabilities: Set<CapabilityId>,
            descriptors: Collection<ToolDescriptor>
        ): IntegratedCognitiveStatePacket = supplier()
    }
}
