package io.amper.neuroos.core

import io.amper.neuroos.core.v2.AmperAgentAndroidContinuationHandoffPolicy
import io.amper.neuroos.core.v2.AmperAgentContinuationEnvelope
import io.amper.neuroos.core.v2.AmperAgentTaskState
import io.amper.neuroos.core.v2.OmegaBackgroundMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAgentContinuationJobIdentityTest {
    @Test
    fun sameTaskAndPlanKeepStableJobIdAcrossCheckpointVersions() {
        val first = handoff(planStateSha = "a".repeat(64), checkpointedAt = 100L)
        val second = handoff(planStateSha = "b".repeat(64), checkpointedAt = 200L)

        assertTrue(first.envelopeSha256 != second.envelopeSha256)
        assertEquals(first.dedupeKey, second.dedupeKey)
        assertEquals(
            AndroidAgentContinuationJobIdentity.jobIdFor(first),
            AndroidAgentContinuationJobIdentity.jobIdFor(second)
        )
    }

    @Test
    fun differentPlanGetsDifferentStableJobNamespaceInput() {
        val first = handoff(planId = "plan-1")
        val second = handoff(planId = "plan-2")

        assertTrue(first.dedupeKey != second.dedupeKey)
        assertTrue(
            AndroidAgentContinuationJobIdentity.jobIdFor(first) !=
                AndroidAgentContinuationJobIdentity.jobIdFor(second)
        )
    }

    private fun handoff(
        planId: String = "plan-1",
        planStateSha: String = "a".repeat(64),
        checkpointedAt: Long = 100L
    ) = AmperAgentAndroidContinuationHandoffPolicy.create(
        AmperAgentContinuationEnvelope(
            taskId = "user-task",
            planId = PlanId(planId),
            backgroundMode = OmegaBackgroundMode.PERSISTED_JOB,
            taskState = AmperAgentTaskState.CHECKPOINTED,
            completedSteps = 0,
            totalSteps = 1,
            planStateSha256 = planStateSha,
            checkpointedAtEpochMs = checkpointedAt
        )
    )
}
