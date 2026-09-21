package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAgentProactiveAttentionPolicyTest {
    @Test
    fun deliveryDecisionNeverMarksPermissionSuppressedSignalAsDelivered() {
        val oldFingerprint = "a".repeat(64)
        val newFingerprint = "b".repeat(64)

        assertEquals(
            AndroidAgentProactiveAttentionDeliveryDecision.PERMISSION_SUPPRESSED,
            AndroidAgentProactiveAttentionDeliveryPolicy.decide(
                currentFingerprintSha256 = oldFingerprint,
                signalFingerprintSha256 = newFingerprint,
                notificationsAllowed = false
            )
        )
        assertEquals(
            AndroidAgentProactiveAttentionDeliveryDecision.DELIVER,
            AndroidAgentProactiveAttentionDeliveryPolicy.decide(
                currentFingerprintSha256 = oldFingerprint,
                signalFingerprintSha256 = newFingerprint,
                notificationsAllowed = true
            )
        )
    }

    @Test
    fun sameFingerprintIsSuppressedAndNoSignalCancels() {
        val fingerprint = "c".repeat(64)

        assertEquals(
            AndroidAgentProactiveAttentionDeliveryDecision.DUPLICATE_SUPPRESSED,
            AndroidAgentProactiveAttentionDeliveryPolicy.decide(
                currentFingerprintSha256 = fingerprint,
                signalFingerprintSha256 = fingerprint,
                notificationsAllowed = true
            )
        )
        assertEquals(
            AndroidAgentProactiveAttentionDeliveryDecision.CANCEL,
            AndroidAgentProactiveAttentionDeliveryPolicy.decide(
                currentFingerprintSha256 = fingerprint,
                signalFingerprintSha256 = null,
                notificationsAllowed = true
            )
        )
    }

    @Test
    fun notificationIdentityIsStableAndPlanSpecific() {
        val first = PlanId("agent-trigger-plan:" + "1".repeat(64))
        val same = PlanId(first.value)
        val other = PlanId("agent-trigger-plan:" + "2".repeat(64))

        assertEquals(
            AndroidAgentProactiveAttentionIdentity.notificationIdFor(first),
            AndroidAgentProactiveAttentionIdentity.notificationIdFor(same)
        )
        assertNotEquals(
            AndroidAgentProactiveAttentionIdentity.notificationIdFor(first),
            AndroidAgentProactiveAttentionIdentity.notificationIdFor(other)
        )
        assertEquals(
            AndroidAgentProactiveAttentionIdentity.preferenceKeyFor(first),
            AndroidAgentProactiveAttentionIdentity.preferenceKeyFor(same)
        )
        assertTrue(
            AndroidAgentProactiveAttentionIdentity.preferenceKeyFor(first)
                .startsWith(AndroidAgentProactiveAttentionIdentity.PREF_KEY_PREFIX)
        )
    }
}
