package io.amper.neuroos.core

import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableHierarchicalGoalOrchestrationTest {
    private val read = CapabilityId("test.read")

    private fun cipher(): MemoryLineCipher {
        val key = ByteArray(32) { index -> (31 + index).toByte() }
        return AesGcmMemoryLineCipher(
            SecretKeySpec(key, "AES"),
            "phase183-goal-graph-test"
        )
    }

    private fun descriptor(): ToolDescriptor = ToolDescriptor(
        id = ToolId("phase183-read"),
        name = "Phase183 read",
        capability = read,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description = "read one governed value",
            acceptedValues = setOf("one"),
            maxLength = 16
        )
    )

    private data class Harness(
        val runtime: AmperRuntime,
        val coordinator: SovereignGoalGraphCoordinator,
        val audit: InMemoryToolAuditLog,
        val inferenceCalls: () -> Int
    )

    private fun harness(): Harness {
        val runtime = AmperRuntime.reference()
        val registry = InMemoryToolRegistry()
        var executions = 0
        registry.register(object : ToolProvider {
            override val descriptor = descriptor()
            override fun execute(input: String): Result<String> = runCatching {
                executions += 1
                "ok:$input"
            }
        })
        val audit = InMemoryToolAuditLog()
        val actions = runtime.actionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(setOf(read)),
                registry = registry,
                audit = audit
            )
        )
        var calls = 0
        val inference = CognitiveInferencePort { request ->
            calls += 1
            if (request.prompt.contains("<AMPER_GOAL_GRAPH_V1>")) {
                Result.success(
                    InferenceResponse(
                        modelId = ModelId("phase183-decomposer"),
                        backendId = "phase183-test-backend",
                        text = """
                            <AMPER_GOAL_GRAPH_V1>
                            node.1.objective=Inspect governed source
                            node.1.depends=none
                            node.2.objective=Verify dependent result
                            node.2.depends=1
                            </AMPER_GOAL_GRAPH_V1>
                        """.trimIndent(),
                        selectedCapabilities = setOf(TitanCapabilities.REASONING)
                    )
                )
            } else {
                Result.success(
                    InferenceResponse(
                        modelId = ModelId("phase183-planner"),
                        backendId = "phase183-test-backend",
                        text = """
                            <AMPER_PLAN_V1>
                            step.1.capability=test.read
                            step.1.reason=Read one governed value
                            step.1.input=one
                            </AMPER_PLAN_V1>
                        """.trimIndent(),
                        selectedCapabilities = setOf(
                            TitanCapabilities.REASONING,
                            TitanCapabilities.PLANNING
                        )
                    )
                )
            }
        }
        val planner = SovereignPlanCoordinator(
            runtime = runtime,
            inference = inference,
            actions = actions,
            advertisedCapabilities = setOf(read)
        )
        return Harness(
            runtime = runtime,
            coordinator = SovereignGoalGraphCoordinator(
                runtime = runtime,
                inference = inference,
                planner = planner
            ),
            audit = audit,
            inferenceCalls = { calls }
        ).also {
            assertEquals(0, executions)
        }
    }

    @Test
    fun protocolBuildsBoundedAcyclicFrontierAndRejectsForwardDependency() {
        val valid = SovereignGoalGraphProtocol.parse(
            """
                <AMPER_GOAL_GRAPH_V1>
                node.1.objective=Collect evidence
                node.1.depends=none
                node.2.objective=Check evidence
                node.2.depends=1
                node.3.objective=Prepare result
                node.3.depends=1,2
                </AMPER_GOAL_GRAPH_V1>
            """.trimIndent()
        ).getOrThrow()

        assertEquals(3, valid.size)
        assertEquals(GoalNodeStatus.READY, valid[0].status)
        assertEquals(GoalNodeStatus.BLOCKED, valid[1].status)
        assertEquals(setOf(1, 2), valid[2].dependencies)

        val invalid = SovereignGoalGraphProtocol.parse(
            """
                <AMPER_GOAL_GRAPH_V1>
                node.1.objective=Invalid forward edge
                node.1.depends=2
                node.2.objective=Later node
                node.2.depends=none
                </AMPER_GOAL_GRAPH_V1>
            """.trimIndent()
        )
        assertTrue(invalid.isFailure)
    }

    @Test
    fun createAndPlanNextAreBoundedAndExecuteZeroTools() {
        val h = harness()
        val conversation = h.runtime.conversations.primary()

        val graph = h.coordinator.create(
            conversationId = conversation,
            rootGoal = "Complete a durable two-stage task"
        ).getOrThrow()

        assertEquals(1, h.inferenceCalls())
        assertEquals(2, graph.nodes.size)
        assertEquals(GoalGraphReviewStatus.PENDING_REVIEW, graph.reviewStatus)
        assertEquals(listOf(1), graph.readyNodes().map { it.index })
        assertEquals(0, h.audit.snapshot().size)
        assertNotNull(h.runtime.goalGraphs.load(graph.id))

        val blockedBeforeReview = h.coordinator.planNext(graph.id)
        assertTrue(blockedBeforeReview.isFailure)
        assertEquals(1, h.inferenceCalls())
        val approved = h.coordinator.approveGraph(graph.id).getOrThrow()
        assertEquals(GoalGraphReviewStatus.APPROVED, approved.reviewStatus)

        val (plannedGraph, plan) = h.coordinator.planNext(graph.id).getOrThrow()

        assertEquals(2, h.inferenceCalls())
        assertEquals(GoalNodeStatus.PLANNED, plannedGraph.nodes[0].status)
        assertEquals(plan.id, plannedGraph.nodes[0].planId)
        assertEquals(GoalNodeStatus.BLOCKED, plannedGraph.nodes[1].status)
        assertEquals(PlanStepStatus.PLANNED, plan.steps.single().status)
        assertNotNull(h.runtime.plans.load(plan.id))
        assertEquals(0, h.audit.snapshot().size)

        val secondMaterialization = h.coordinator.planNext(graph.id)
        assertTrue(secondMaterialization.isFailure)
        assertEquals(2, h.inferenceCalls())
        assertEquals(0, h.audit.snapshot().size)
    }

    @Test
    fun successfulExecutionAwaitsVerificationAndDoesNotUnlockDependencyYet() {
        val h = harness()
        val graph = h.coordinator.create(
            h.runtime.conversations.primary(),
            "Complete a durable dependency graph"
        ).getOrThrow()
        h.coordinator.approveGraph(graph.id).getOrThrow()
        val (_, plan) = h.coordinator.planNext(graph.id).getOrThrow()

        h.runtime.plans.save(
            plan.copy(
                steps = plan.steps.map { it.copy(status = PlanStepStatus.EXECUTED) }
            )
        )

        val reconciled = h.coordinator.reconcile(graph.id, nodeIndex = 1).getOrThrow()

        assertEquals(GoalNodeStatus.AWAITING_VERIFICATION, reconciled.nodes[0].status)
        assertEquals(GoalNodeStatus.BLOCKED, reconciled.nodes[1].status)
        assertTrue(reconciled.readyNodes().isEmpty())
        assertFalse(reconciled.complete)
        assertFalse(reconciled.stalled)
        assertEquals(0, h.audit.snapshot().size)
    }

    @Test
    fun failedSubgoalStallsDependentBranchWithoutAutomaticRetry() {
        val h = harness()
        val graph = h.coordinator.create(
            h.runtime.conversations.primary(),
            "Complete a task that may fail"
        ).getOrThrow()
        h.coordinator.approveGraph(graph.id).getOrThrow()
        val (_, plan) = h.coordinator.planNext(graph.id).getOrThrow()
        val callsBeforeFailure = h.inferenceCalls()

        h.runtime.plans.save(
            plan.copy(
                steps = plan.steps.map { it.copy(status = PlanStepStatus.FAILED) }
            )
        )
        val reconciled = h.coordinator.reconcile(graph.id, nodeIndex = 1).getOrThrow()

        assertEquals(GoalNodeStatus.FAILED, reconciled.nodes[0].status)
        assertEquals(GoalNodeStatus.BLOCKED, reconciled.nodes[1].status)
        assertTrue(reconciled.readyNodes().isEmpty())
        assertTrue(reconciled.stalled)
        assertEquals(callsBeforeFailure, h.inferenceCalls())
        assertEquals(0, h.audit.snapshot().size)
    }

    @Test
    fun rejectedGraphCannotMaterializeAnySubgoal() {
        val h = harness()
        val graph = h.coordinator.create(
            h.runtime.conversations.primary(),
            "Review this decomposition before doing anything"
        ).getOrThrow()
        val callsAfterDecomposition = h.inferenceCalls()

        val rejected = h.coordinator.rejectGraph(graph.id).getOrThrow()

        assertEquals(GoalGraphReviewStatus.REJECTED, rejected.reviewStatus)
        assertTrue(h.coordinator.frontier(graph.id).isEmpty())
        assertTrue(h.coordinator.planNext(graph.id).isFailure)
        assertEquals(callsAfterDecomposition, h.inferenceCalls())
        assertEquals(0, h.audit.snapshot().size)
    }

    @Test
    fun planOsV5PreservesRecoveryAndCounterfactualStateAcrossRestart() {
        val root = Files.createTempDirectory("amper-phase183-plan-v5").toFile()
        val cipher = cipher()
        val parent = PlanId("phase183-parent-plan")
        val plan = SovereignPlan(
            id = PlanId("phase183-recovery-plan"),
            conversationId = ConversationId("phase183-persistent-conversation"),
            goal = "durable recovery objective",
            steps = listOf(
                SovereignPlanStep(
                    index = 1,
                    requestId = ActionRequestId("phase183-request"),
                    capability = read,
                    reason = "read durable status",
                    input = "one",
                    status = PlanStepStatus.PLANNED,
                    boundToolId = ToolId("phase183-read"),
                    boundSideEffect = ToolSideEffect.READ_ONLY
                )
            ),
            planningBackendId = "phase183-backend",
            createdAtEpochMs = 1_234_567L,
            planningModelId = ModelId("phase183-model"),
            planningSelectedCapabilities = setOf(
                TitanCapabilities.REASONING,
                TitanCapabilities.PLANNING
            ),
            parentPlanId = parent,
            recoveryDepth = 1,
            deliberationCandidateCount = 3,
            deliberationScore = 0.8125,
            counterfactualViability = 0.734,
            counterfactualConfidence = 0.625
        )

        AmperRuntime.persistentEncrypted(root, cipher = cipher).plans.save(plan)
        val restored = AmperRuntime.persistentEncrypted(root, cipher = cipher).plans.load(plan.id)

        assertEquals(plan, restored)
        assertEquals(parent, restored?.parentPlanId)
        assertEquals(1, restored?.recoveryDepth)
        assertEquals(3, restored?.deliberationCandidateCount)
        assertEquals(0.8125, restored?.deliberationScore ?: 0.0, 0.000001)
        assertEquals(0.734, restored?.counterfactualViability ?: 0.0, 0.000001)
        assertEquals(0.625, restored?.counterfactualConfidence ?: 0.0, 0.000001)
    }

    @Test
    fun goalGraphStateSurvivesEncryptedRuntimeRecreation() {
        val root = Files.createTempDirectory("amper-phase183-goal-restart").toFile()
        val cipher = cipher()
        val graph = SovereignGoalGraph(
            id = GoalGraphId("phase183-durable-graph"),
            conversationId = ConversationId("phase183-conversation"),
            rootGoal = "durable long horizon objective",
            nodes = listOf(
                SovereignGoalNode(
                    index = 1,
                    objective = "first durable subgoal",
                    dependencies = emptySet(),
                    status = GoalNodeStatus.COMPLETED,
                    planId = PlanId("phase183-plan-1"),
                    verification = GoalVerificationRecord(
                        verdict = GoalVerificationVerdict.SATISFIED,
                        confidence = 0.95,
                        backendId = "phase184-verifier",
                        modelId = ModelId("phase184-verifier-model"),
                        verifiedAtEpochMs = 7_654_320L
                    )
                ),
                SovereignGoalNode(
                    index = 2,
                    objective = "second durable subgoal",
                    dependencies = setOf(1),
                    status = GoalNodeStatus.READY
                )
            ),
            decompositionBackendId = "phase183-decomposer",
            reviewStatus = GoalGraphReviewStatus.APPROVED,
            createdAtEpochMs = 7_654_321L,
            decompositionModelId = ModelId("phase183-model"),
            decompositionSelectedCapabilities = setOf(TitanCapabilities.REASONING)
        )

        AmperRuntime.persistentEncrypted(root, cipher = cipher).goalGraphs.save(graph)
        val restored = AmperRuntime.persistentEncrypted(root, cipher = cipher).goalGraphs.load(graph.id)

        assertEquals(graph, restored)
        assertEquals(listOf(2), restored?.readyNodes()?.map { it.index })
        assertNull(restored?.nodes?.get(1)?.planId)
    }

    @Test
    fun recoveryAttachmentRequiresExactFailedPlanLineageAndDoesNotExecute() {
        val h = harness()
        val graph = h.coordinator.create(
            h.runtime.conversations.primary(),
            "Recover a failed durable branch"
        ).getOrThrow()
        h.coordinator.approveGraph(graph.id).getOrThrow()
        val (_, original) = h.coordinator.planNext(graph.id).getOrThrow()
        h.runtime.plans.save(
            original.copy(steps = original.steps.map { it.copy(status = PlanStepStatus.FAILED) })
        )
        h.coordinator.reconcile(graph.id, 1).getOrThrow()

        val wrongRecovery = original.copy(
            id = PlanId("wrong-recovery"),
            parentPlanId = PlanId("different-parent"),
            recoveryDepth = 1
        )
        assertTrue(h.coordinator.attachRecovery(graph.id, 1, wrongRecovery).isFailure)

        val validRecovery = original.copy(
            id = PlanId("valid-recovery"),
            parentPlanId = original.id,
            recoveryDepth = 1,
            steps = original.steps.map {
                it.copy(
                    requestId = ActionRequestId("recovery-request"),
                    status = PlanStepStatus.PLANNED
                )
            }
        )
        val attached = h.coordinator.attachRecovery(graph.id, 1, validRecovery).getOrThrow()

        assertEquals(GoalNodeStatus.PLANNED, attached.nodes[0].status)
        assertEquals(validRecovery.id, attached.nodes[0].planId)
        assertNotNull(h.runtime.plans.load(validRecovery.id))
        assertEquals(0, h.audit.snapshot().size)
    }
}
