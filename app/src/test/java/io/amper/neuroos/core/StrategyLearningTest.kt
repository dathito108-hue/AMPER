package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyLearningTest {
    private fun plan(
        id: String,
        statuses: List<PlanStepStatus>,
        capabilities: List<CapabilityId> = listOf(
            CapabilityId("device.read"),
            CapabilityId("network.lookup")
        ),
        goal: String = "private goal text"
    ): SovereignPlan {
        require(statuses.size == capabilities.size)
        return SovereignPlan(
            id = PlanId(id),
            conversationId = ConversationId("strategy-test"),
            goal = goal,
            steps = statuses.mapIndexed { index, status ->
                SovereignPlanStep(
                    index = index + 1,
                    requestId = ActionRequestId("$id-step-${index + 1}"),
                    capability = capabilities[index],
                    reason = "private reason ${index + 1}",
                    input = "private-input-${index + 1}",
                    status = status,
                    boundToolId = ToolId("tool-${index + 1}"),
                    boundSideEffect = ToolSideEffect.READ_ONLY
                )
            },
            planningBackendId = "planner-test"
        )
    }

    @Test
    fun completedPlansBuildIdempotentBoundedProceduralEvidence() {
        val memory = InMemoryMemoryOs()
        var now = 1_000L
        val strategies = MemoryBackedStrategyLearningModel(memory) { now++ }
        val signature = StrategySignature(
            listOf(CapabilityId("device.read"), CapabilityId("network.lookup"))
        )

        val success = plan(
            id = "plan-success",
            statuses = listOf(PlanStepStatus.EXECUTED, PlanStepStatus.EXECUTED)
        )
        val failed = plan(
            id = "plan-failed",
            statuses = listOf(PlanStepStatus.EXECUTED, PlanStepStatus.FAILED)
        )
        val aborted = plan(
            id = "plan-aborted",
            statuses = listOf(PlanStepStatus.EXECUTED, PlanStepStatus.REJECTED)
        )

        strategies.observe(success)
        strategies.observe(success)
        strategies.observe(failed)
        strategies.observe(aborted)

        val snapshot = requireNotNull(strategies.snapshot(signature))
        assertEquals(1, snapshot.successes)
        assertEquals(1, snapshot.failures)
        assertEquals(1, snapshot.aborted)
        assertEquals(2, snapshot.completedAttempts)
        assertEquals(0.5, snapshot.completedSuccessRate!!, 0.0001)
        assertEquals(2.0 / 6.0, snapshot.evidenceConfidence, 0.0001)

        val reloaded = MemoryBackedStrategyLearningModel(memory)
        val durable = requireNotNull(reloaded.snapshot(signature))
        assertEquals(snapshot.successes, durable.successes)
        assertEquals(snapshot.failures, durable.failures)
        assertEquals(snapshot.aborted, durable.aborted)
        assertEquals(signature, reloaded.recent(4).single().signature)

        val strategyRecords = memory.recall("", memory.size())
            .filter {
                it.kind == "strategy-evidence" ||
                    it.kind == "strategy-observation" ||
                    it.kind == "strategy-evidence-index"
            }
        assertTrue(strategyRecords.isNotEmpty())
        assertTrue(strategyRecords.none { it.content.contains("private goal text") })
        assertTrue(strategyRecords.none { it.content.contains("private-input") })
        assertTrue(strategyRecords.none { it.content.contains("private reason") })
    }

    @Test
    fun incompletePlanProducesNoStrategyEvidence() {
        val memory = InMemoryMemoryOs()
        val strategies = MemoryBackedStrategyLearningModel(memory)
        val incomplete = plan(
            id = "plan-incomplete",
            statuses = listOf(PlanStepStatus.EXECUTED, PlanStepStatus.PLANNED)
        )

        assertNull(strategies.observe(incomplete))
        assertTrue(strategies.recent(4).isEmpty())
    }

    @Test
    fun samePlanIdentityCannotBeRewrittenIntoDifferentOutcome() {
        val memory = InMemoryMemoryOs()
        val strategies = MemoryBackedStrategyLearningModel(memory)
        val first = plan(
            id = "stable-plan",
            statuses = listOf(PlanStepStatus.EXECUTED, PlanStepStatus.EXECUTED)
        )
        val rewritten = plan(
            id = "stable-plan",
            statuses = listOf(PlanStepStatus.EXECUTED, PlanStepStatus.FAILED)
        )

        strategies.observe(first)
        val changed = runCatching { strategies.observe(rewritten) }

        assertTrue(changed.isFailure)
        assertTrue(
            changed.exceptionOrNull()?.message.orEmpty()
                .contains("changed terminal strategy outcome")
        )
    }
}
