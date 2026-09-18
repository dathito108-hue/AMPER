package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousLearningTest {
    private val capability = CapabilityId("learning.practice")

    @Test
    fun authorityDenialDoesNotBecomeExecutionReliabilityWeakness() {
        val memory = InMemoryMemoryOs()
        val competence = MemoryBackedCapabilityCompetenceModel(memory)
        val skills = MemoryBackedSkillGenesisModel(memory)
        val generalization = MemoryBackedSkillGeneralizationModel(memory, skills)
        val learning = MemoryBackedAutonomousLearningModel(
            memory, competence, skills, generalization
        )
        val proposal = ActionProposal(
            capability = capability,
            reason = "denied by authority boundary",
            input = "run"
        )
        competence.observe(
            ActionOutcome(
                status = ActionStatus.DENIED,
                proposal = proposal,
                toolId = ToolId("learning-tool"),
                sideEffect = ToolSideEffect.EXTERNAL,
                detail = "denied"
            )
        )

        val needs = learning.diagnose(
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor(ToolSideEffect.EXTERNAL)),
            limit = 8
        )

        assertTrue(needs.any { it.kind == LearningNeedKind.EVIDENCE_DEPTH })
        assertTrue(needs.any { it.kind == LearningNeedKind.CONTRACT_PRACTICE })
        assertFalse(needs.any { it.kind == LearningNeedKind.EXECUTION_RELIABILITY })
        assertEquals(0, competence.snapshot(capability)?.executionAttempts)
    }

    @Test
    fun validPracticePassesWithoutChangingRealExecutionCompetence() {
        val memory = InMemoryMemoryOs()
        val competence = MemoryBackedCapabilityCompetenceModel(memory)
        val skills = MemoryBackedSkillGenesisModel(memory)
        val generalization = MemoryBackedSkillGeneralizationModel(memory, skills)
        val learning = MemoryBackedAutonomousLearningModel(
            memory, competence, skills, generalization, clock = { 2_000L }
        )
        val descriptor = descriptor(ToolSideEffect.EXTERNAL)
        val task = learning.curriculum(
            setOf(capability), listOf(descriptor), limit = 1
        ).single()

        val evidence = learning.assess(task, validPlan(), listOf(descriptor))

        assertEquals(AutonomousPracticeVerdict.PASS, evidence.verdict)
        assertEquals(1, evidence.validatedStepCount)
        assertFalse(evidence.authorityBearing)
        assertEquals(1, learning.practiceSnapshot(capability)?.passed)
        assertNull(competence.snapshot(capability))
    }

    @Test
    fun malformedPracticeFailsAndRawOutputIsNotPersisted() {
        val memory = InMemoryMemoryOs()
        val competence = MemoryBackedCapabilityCompetenceModel(memory)
        val skills = MemoryBackedSkillGenesisModel(memory)
        val generalization = MemoryBackedSkillGeneralizationModel(memory, skills)
        val learning = MemoryBackedAutonomousLearningModel(
            memory, competence, skills, generalization, clock = { 3_000L }
        )
        val descriptor = descriptor(ToolSideEffect.READ_ONLY)
        val task = learning.curriculum(
            setOf(capability), listOf(descriptor), limit = 1
        ).single()
        val rawSecret = "RAW_PRACTICE_OUTPUT_SHOULD_NOT_PERSIST"

        val evidence = learning.assess(
            task,
            "$rawSecret\n<AMPER_PLAN_V1>\nstep.1.capability=learning.practice\n</AMPER_PLAN_V1>",
            listOf(descriptor)
        )

        assertEquals(AutonomousPracticeVerdict.FAIL, evidence.verdict)
        assertEquals(0, evidence.validatedStepCount)
        assertEquals(1, learning.practiceSnapshot(capability)?.failed)
        assertTrue(memory.recall(rawSecret, 16).isEmpty())
    }

    @Test
    fun activeSkillWithoutTransferEvidenceCreatesNovelTransferCurriculum() {
        val memory = InMemoryMemoryOs()
        var time = 4_000L
        val competence = MemoryBackedCapabilityCompetenceModel(memory, clock = { time++ })
        val skills = MemoryBackedSkillGenesisModel(memory, clock = { time++ })
        val generalization = MemoryBackedSkillGeneralizationModel(memory, skills, clock = { time++ })
        val learning = MemoryBackedAutonomousLearningModel(
            memory, competence, skills, generalization, clock = { time++ }
        )

        repeat(2) { index ->
            val plan = successfulPlan("skill-$index", "learn local skill $index")
            skills.begin(plan, emptyList())
            skills.observe(plan, emptyList())
        }

        val needs = learning.diagnose(
            setOf(capability),
            listOf(descriptor(ToolSideEffect.READ_ONLY)),
            limit = 8
        )
        assertTrue(needs.any { it.kind == LearningNeedKind.TRANSFER_COVERAGE })

        val task = learning.curriculum(
            setOf(capability),
            listOf(descriptor(ToolSideEffect.READ_ONLY)),
            limit = 1
        ).single()
        assertEquals(AutonomousPracticeKind.NOVEL_TRANSFER_PLAN, task.kind)
        assertTrue(LearningNeedKind.TRANSFER_COVERAGE in task.sourceNeeds)
        assertFalse(task.authorityBearing)
    }

    @Test
    fun plannerPracticeOneUsesOneInferenceAndNeverExecutesExternalTool() {
        val runtime = AmperRuntime.reference()
        var executions = 0
        val registry = InMemoryToolRegistry().apply {
            register(object : ToolProvider {
                override val descriptor = descriptor(ToolSideEffect.EXTERNAL)
                override fun execute(input: String): Result<String> {
                    executions += 1
                    return Result.success("should-not-run")
                }
            })
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
            Result.success(
                InferenceResponse(
                    modelId = ModelId("practice-model"),
                    backendId = "practice-backend",
                    text = validPlan(),
                    selectedCapabilities = setOf(TitanCapabilities.REASONING)
                )
            )
        }
        val planner = SovereignPlanCoordinator(
            runtime = runtime,
            inference = inference,
            actions = actions,
            advertisedCapabilities = setOf(capability),
            criticInference = null
        )

        val result = planner.practiceOne().getOrThrow()
        val assessed = result as AutonomousLearningCycleResult.Assessed

        assertEquals(1, requests.size)
        assertTrue(requests.single().prompt.contains("zero-tool simulation"))
        assertTrue(requests.single().prompt.contains("side_effect=EXTERNAL"))
        assertEquals(AutonomousPracticeVerdict.PASS, assessed.evidence.verdict)
        assertEquals(0, executions)
        assertTrue(audit.snapshot().isEmpty())
        assertNull(runtime.competence.snapshot(capability))
        assertEquals(1, runtime.autonomousLearning.practiceSnapshot(capability)?.passed)
    }

    @Test
    fun autonomousPracticeJournalIsExcludedFromGenericContext() {
        val runtime = AmperRuntime.reference()
        val descriptor = descriptor(ToolSideEffect.READ_ONLY)
        val task = runtime.autonomousLearning.curriculum(
            setOf(capability), listOf(descriptor), limit = 1
        ).single()
        runtime.autonomousLearning.assess(task, validPlan(), listOf(descriptor))

        val snapshot = runtime.context.capture(
            query = capability.value,
            memoryLimit = 12,
            worldLimit = 0,
            workspaceLimit = 0
        )

        assertFalse(snapshot.memories.any {
            it.kind == MemoryBackedAutonomousLearningModel.PRACTICE_EVIDENCE_KIND ||
                it.kind == MemoryBackedAutonomousLearningModel.PRACTICE_SNAPSHOT_KIND ||
                it.kind == MemoryBackedAutonomousLearningModel.PRACTICE_INDEX_KIND
        })
    }

    private fun validPlan(): String = """
        <AMPER_PLAN_V1>
        step.1.capability=learning.practice
        step.1.reason=Practice the live contract without execution
        step.1.input=run
        </AMPER_PLAN_V1>
    """.trimIndent()

    private fun successfulPlan(id: String, goal: String): SovereignPlan {
        val request = ActionRequestId("request-$id")
        val toolId = ToolId("tool-learning.practice")
        val proposal = ActionProposal(
            requestId = request,
            capability = capability,
            reason = "governed successful skill evidence",
            input = "run"
        )
        return SovereignPlan(
            id = PlanId(id),
            conversationId = ConversationId("autonomous-learning-test"),
            goal = goal,
            steps = listOf(
                SovereignPlanStep(
                    index = 1,
                    requestId = request,
                    capability = capability,
                    reason = proposal.reason,
                    input = proposal.input,
                    status = PlanStepStatus.EXECUTED,
                    outcome = ActionOutcome(
                        status = ActionStatus.EXECUTED,
                        proposal = proposal,
                        toolId = toolId,
                        sideEffect = ToolSideEffect.READ_ONLY,
                        output = "ok"
                    ),
                    boundToolId = toolId,
                    boundSideEffect = ToolSideEffect.READ_ONLY
                )
            ),
            planningBackendId = "test"
        )
    }

    private fun descriptor(sideEffect: ToolSideEffect): ToolDescriptor = ToolDescriptor(
        id = ToolId("tool-learning.practice"),
        name = "learning practice tool",
        capability = capability,
        sideEffect = sideEffect,
        inputContract = ToolInputContract(
            description = "practice run contract",
            acceptedValues = setOf("run"),
            maxLength = 32
        )
    )
}
