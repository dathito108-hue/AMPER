package io.amper.neuroos.core

import io.amper.neuroos.core.v2.AmperAgentProactiveAttentionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAgentProactiveAttentionPolicyTest {
    @Test
    fun approvalAndMissingPlanAlwaysSurfaceAttention() {
        AndroidAgentProactiveAttentionSurfaceMode.entries.forEach { mode ->
            assertTrue(
                AndroidAgentProactiveAttentionSurfacePolicy.shouldPost(
                    AmperAgentProactiveAttentionKind.APPROVAL_REQUIRED,
                    mode
                )
            )
            assertTrue(
                AndroidAgentProactiveAttentionSurfacePolicy.shouldPost(
                    AmperAgentProactiveAttentionKind.PLAN_UNAVAILABLE,
                    mode
                )
            )
        }
    }

    @Test
    fun terminalAttentionOnlySurfacesOnBackgroundTransition() {
        assertTrue(
            AndroidAgentProactiveAttentionSurfacePolicy.shouldPost(
                AmperAgentProactiveAttentionKind.COMPLETED,
                AndroidAgentProactiveAttentionSurfaceMode.BACKGROUND_TRANSITION
            )
        )
        assertTrue(
            AndroidAgentProactiveAttentionSurfacePolicy.shouldPost(
                AmperAgentProactiveAttentionKind.FAILED,
                AndroidAgentProactiveAttentionSurfaceMode.BACKGROUND_TRANSITION
            )
        )
        assertFalse(
            AndroidAgentProactiveAttentionSurfacePolicy.shouldPost(
                AmperAgentProactiveAttentionKind.COMPLETED,
                AndroidAgentProactiveAttentionSurfaceMode.FOREGROUND_RECONCILE
            )
        )
        assertFalse(
            AndroidAgentProactiveAttentionSurfacePolicy.shouldPost(
                AmperAgentProactiveAttentionKind.FAILED,
                AndroidAgentProactiveAttentionSurfaceMode.USER_INTERACTION
            )
        )
    }

    @Test
    fun checkpointedNoneNeverPosts() {
        AndroidAgentProactiveAttentionSurfaceMode.entries.forEach { mode ->
            assertFalse(
                AndroidAgentProactiveAttentionSurfacePolicy.shouldPost(
                    AmperAgentProactiveAttentionKind.NONE,
                    mode
                )
            )
        }
    }

    @Test
    fun notificationNavigationAcceptsOnlyDeterministicProactivePlanIds() {
        val valid = "agent-trigger-plan:" + "a".repeat(64)

        assertEquals(
            PlanId(valid),
            AndroidAgentProactiveAttentionIdentity.parseRequestedPlanId(valid)
        )
        assertEquals(
            null,
            AndroidAgentProactiveAttentionIdentity.parseRequestedPlanId(
                "agent-trigger-plan:" + "A".repeat(64)
            )
        )
        assertEquals(
            null,
            AndroidAgentProactiveAttentionIdentity.parseRequestedPlanId(
                "normal-plan-id"
            )
        )
        assertEquals(
            null,
            AndroidAgentProactiveAttentionIdentity.parseRequestedPlanId(
                "agent-trigger-plan:" + "a".repeat(63)
            )
        )
        assertEquals(
            null,
            AndroidAgentProactiveAttentionIdentity.parseRequestedPlanId(
                valid + "-suffix"
            )
        )
        assertEquals(
            null,
            AndroidAgentProactiveAttentionIdentity.parseRequestedPlanId(null)
        )
        assertEquals(
            "b".repeat(64),
            AndroidAgentProactiveAttentionIdentity.parseRequestedRevision(
                "b".repeat(64)
            )
        )
        assertEquals(
            null,
            AndroidAgentProactiveAttentionIdentity.parseRequestedRevision(
                "B".repeat(64)
            )
        )
        assertEquals(
            null,
            AndroidAgentProactiveAttentionIdentity.parseRequestedRevision(
                "b".repeat(63)
            )
        )
    }

    @Test
    fun notificationCopyIsGenericAndCannotMirrorSensitiveLifecycleContent() {
        val approval = AndroidAgentProactiveAttentionNotificationCopyPolicy.copy(
            AmperAgentProactiveAttentionKind.APPROVAL_REQUIRED,
            7
        )
        val completed = AndroidAgentProactiveAttentionNotificationCopyPolicy.copy(
            AmperAgentProactiveAttentionKind.COMPLETED,
            null
        )
        val failed = AndroidAgentProactiveAttentionNotificationCopyPolicy.copy(
            AmperAgentProactiveAttentionKind.FAILED,
            null
        )
        val unavailable = AndroidAgentProactiveAttentionNotificationCopyPolicy.copy(
            AmperAgentProactiveAttentionKind.PLAN_UNAVAILABLE,
            null
        )

        assertEquals("AMPER needs your approval", approval.title)
        assertEquals(
            "A proactive task is waiting at governed approval step 7.",
            approval.text
        )
        val rendered = listOf(approval, completed, failed, unavailable)
            .joinToString("\n") { it.title + "\n" + it.text }
            .lowercase()
        assertFalse(rendered.contains("goal:"))
        assertFalse(rendered.contains("source:"))
        assertFalse(rendered.contains("payload"))
        assertFalse(rendered.contains("tool input"))
        assertFalse(rendered.contains("agent-trigger-plan:"))
    }

    @Test
    fun notificationIdentityUsesFullCanonicalPlanIdWithoutHashTruncation() {
        val first = PlanId("agent-trigger-plan:" + "a".repeat(64))
        val second = PlanId("agent-trigger-plan:" + "b".repeat(64))

        assertEquals(
            AndroidAgentProactiveAttentionIdentity.TAG_PREFIX + first.value,
            AndroidAgentProactiveAttentionIdentity.tag(first)
        )
        assertTrue(
            AndroidAgentProactiveAttentionIdentity.tag(first) !=
                AndroidAgentProactiveAttentionIdentity.tag(second)
        )
    }
}
