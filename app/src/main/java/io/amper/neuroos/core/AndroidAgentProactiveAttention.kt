package io.amper.neuroos.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.amper.neuroos.R
import io.amper.neuroos.SafeLauncherActivity
import io.amper.neuroos.core.v2.AmperAgentProactiveAttentionPolicy
import io.amper.neuroos.core.v2.AmperAgentProactiveAttentionSignal
import io.amper.neuroos.core.v2.AmperAgentProactiveTaskLifecycleCoordinator
import io.amper.neuroos.core.v2.AmperAgentProactiveTaskLifecycleView
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class AndroidAgentProactiveAttentionDeliveryReport(
    val inspected: Int,
    val delivered: Int,
    val duplicateSuppressed: Int,
    val permissionSuppressed: Int,
    val cancelled: Int,
    val stalePruned: Int
) {
    init {
        require(inspected >= 0)
        require(delivered >= 0)
        require(duplicateSuppressed >= 0)
        require(permissionSuppressed >= 0)
        require(cancelled >= 0)
        require(stalePruned >= 0)
    }
}

object AndroidAgentProactiveAttentionIdentity {
    private const val NOTIFICATION_NAMESPACE = 0x45000000
    private const val PAYLOAD_MASK = 0x00ffffff

    fun notificationIdFor(planId: PlanId): Int {
        val bytes = digest(planId.value)
        val raw =
            ((bytes[0].toInt() and 0xff) shl 16) or
                ((bytes[1].toInt() and 0xff) shl 8) or
                (bytes[2].toInt() and 0xff)
        return NOTIFICATION_NAMESPACE or (raw and PAYLOAD_MASK)
    }

    fun notificationTagFor(planId: PlanId): String =
        "amper-proactive:" + digest(planId.value)
            .joinToString("") { "%02x".format(it) }

    fun preferenceKeyFor(planId: PlanId): String =
        PREF_KEY_PREFIX + digest(planId.value)
            .joinToString("") { "%02x".format(it) }

    internal const val PREF_KEY_PREFIX = "signal:"

    private fun digest(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
}

enum class AndroidAgentProactiveAttentionDeliveryDecision {
    DELIVER,
    DUPLICATE_SUPPRESSED,
    PERMISSION_SUPPRESSED,
    CANCEL
}

object AndroidAgentProactiveAttentionDeliveryPolicy {
    fun decide(
        currentFingerprintSha256: String?,
        signalFingerprintSha256: String?,
        notificationsAllowed: Boolean
    ): AndroidAgentProactiveAttentionDeliveryDecision {
        currentFingerprintSha256?.let {
            require(it.matches(Regex("[0-9a-f]{64}")))
        }
        signalFingerprintSha256?.let {
            require(it.matches(Regex("[0-9a-f]{64}")))
        }

        if (signalFingerprintSha256 == null) {
            return AndroidAgentProactiveAttentionDeliveryDecision.CANCEL
        }
        if (currentFingerprintSha256 == signalFingerprintSha256) {
            return AndroidAgentProactiveAttentionDeliveryDecision.DUPLICATE_SUPPRESSED
        }
        if (!notificationsAllowed) {
            return AndroidAgentProactiveAttentionDeliveryDecision.PERMISSION_SUPPRESSED
        }
        return AndroidAgentProactiveAttentionDeliveryDecision.DELIVER
    }
}

/**
 * Phase663 notification transport.
 *
 * SharedPreferences stores only notification transport metadata:
 * hashed plan-key -> notification-id + already-delivered attention fingerprint.
 * It is not canonical task, plan, approval, trigger, scheduler, or authority state.
 */
class AndroidAgentProactiveAttentionDelivery(
    context: Context
) {
    private val appContext = context.applicationContext
    private val notifications =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val deliveryState =
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    init {
        ensureChannel()
    }

    fun notificationsAllowed(): Boolean =
        permissionGranted() && notifications.areNotificationsEnabled()

    fun reconcile(
        view: AmperAgentProactiveTaskLifecycleView
    ): Result<AndroidAgentProactiveAttentionDeliveryReport> = runCatching {
        val planId = view.binding.planId
        val signal = AmperAgentProactiveAttentionPolicy.signal(view)
        reconcileOne(planId, signal)
    }

    fun reconcileTracked(
        views: List<AmperAgentProactiveTaskLifecycleView>
    ): Result<AndroidAgentProactiveAttentionDeliveryReport> = runCatching {
        require(views.size <= MAX_TRACKED)
        var delivered = 0
        var duplicate = 0
        var permission = 0
        var cancelled = 0

        views.forEach { view ->
            val report = reconcileOne(
                view.binding.planId,
                AmperAgentProactiveAttentionPolicy.signal(view)
            )
            delivered += report.delivered
            duplicate += report.duplicateSuppressed
            permission += report.permissionSuppressed
            cancelled += report.cancelled
        }

        val currentKeys = views
            .mapTo(linkedSetOf()) {
                AndroidAgentProactiveAttentionIdentity.preferenceKeyFor(
                    it.binding.planId
                )
            }
        var stalePruned = 0
        val editor = deliveryState.edit()
        deliveryState.all.forEach { (key, rawValue) ->
            if (
                key.startsWith(AndroidAgentProactiveAttentionIdentity.PREF_KEY_PREFIX) &&
                key !in currentKeys
            ) {
                parseCheckpoint(rawValue as? String)?.let { checkpoint ->
                    notifications.cancel(
                        checkpoint.notificationTag,
                        checkpoint.notificationId
                    )
                }
                editor.remove(key)
                stalePruned += 1
            }
        }
        if (stalePruned > 0) {
            require(editor.commit()) {
                "proactive attention transport checkpoint cleanup failed"
            }
        }

        AndroidAgentProactiveAttentionDeliveryReport(
            inspected = views.size,
            delivered = delivered,
            duplicateSuppressed = duplicate,
            permissionSuppressed = permission,
            cancelled = cancelled,
            stalePruned = stalePruned
        )
    }

    private fun reconcileOne(
        planId: PlanId,
        signal: AmperAgentProactiveAttentionSignal?
    ): AndroidAgentProactiveAttentionDeliveryReport {
        val notificationId =
            AndroidAgentProactiveAttentionIdentity.notificationIdFor(planId)
        val notificationTag =
            AndroidAgentProactiveAttentionIdentity.notificationTagFor(planId)
        val key = AndroidAgentProactiveAttentionIdentity.preferenceKeyFor(planId)

        val checkpoint = parseCheckpoint(deliveryState.getString(key, null))
        val currentFingerprint = checkpoint
            ?.takeIf {
                it.notificationId == notificationId &&
                    it.notificationTag == notificationTag
            }
            ?.fingerprintSha256
        val decision = AndroidAgentProactiveAttentionDeliveryPolicy.decide(
            currentFingerprintSha256 = currentFingerprint,
            signalFingerprintSha256 = signal?.fingerprintSha256,
            notificationsAllowed = notificationsAllowed()
        )

        when (decision) {
            AndroidAgentProactiveAttentionDeliveryDecision.CANCEL -> {
                notifications.cancel(notificationTag, notificationId)
                val existed = deliveryState.contains(key)
                if (existed) {
                    require(deliveryState.edit().remove(key).commit()) {
                        "proactive attention transport checkpoint clear failed"
                    }
                }
                return AndroidAgentProactiveAttentionDeliveryReport(
                    inspected = 1,
                    delivered = 0,
                    duplicateSuppressed = 0,
                    permissionSuppressed = 0,
                    cancelled = if (existed) 1 else 0,
                    stalePruned = 0
                )
            }

            AndroidAgentProactiveAttentionDeliveryDecision.DUPLICATE_SUPPRESSED ->
                return AndroidAgentProactiveAttentionDeliveryReport(
                    inspected = 1,
                    delivered = 0,
                    duplicateSuppressed = 1,
                    permissionSuppressed = 0,
                    cancelled = 0,
                    stalePruned = 0
                )

            AndroidAgentProactiveAttentionDeliveryDecision.PERMISSION_SUPPRESSED -> {
                // Do not mark undelivered attention as delivered. A later user-granted permission
                // may surface the same still-current canonical lifecycle state.
                notifications.cancel(notificationId)
                return AndroidAgentProactiveAttentionDeliveryReport(
                    inspected = 1,
                    delivered = 0,
                    duplicateSuppressed = 0,
                    permissionSuppressed = 1,
                    cancelled = 0,
                    stalePruned = 0
                )
            }

            AndroidAgentProactiveAttentionDeliveryDecision.DELIVER -> Unit
        }

        val requiredSignal = requireNotNull(signal)
        notifications.notify(
            notificationTag,
            notificationId,
            notification(requiredSignal)
        )
        require(
            deliveryState.edit()
                .putString(
                    key,
                    AttentionCheckpoint(
                        notificationId = notificationId,
                        notificationTag = notificationTag,
                        fingerprintSha256 = requiredSignal.fingerprintSha256
                    ).encode()
                )
                .commit()
        ) {
            "proactive attention delivery checkpoint failed"
        }

        return AndroidAgentProactiveAttentionDeliveryReport(
            inspected = 1,
            delivered = 1,
            duplicateSuppressed = 0,
            permissionSuppressed = 0,
            cancelled = 0,
            stalePruned = 0
        )
    }

    private fun notification(
        signal: AmperAgentProactiveAttentionSignal
    ): Notification {
        val launchIntent = Intent(appContext, SafeLauncherActivity::class.java)
            .putExtra(SafeLauncherActivity.EXTRA_PROACTIVE_ATTENTION, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentIntent = PendingIntent.getActivity(
            appContext,
            AndroidAgentProactiveAttentionIdentity.notificationIdFor(signal.planId),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_amper_attention)
            .setContentTitle(signal.title)
            .setContentText(signal.message)
            .setStyle(Notification.BigTextStyle().bigText(signal.message))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun permissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "AMPER proactive attention",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description =
                    "Read-only alerts for proactive tasks that need governed review or reached a terminal state."
                setShowBadge(true)
            }
        )
    }

    private data class AttentionCheckpoint(
        val notificationId: Int,
        val notificationTag: String,
        val fingerprintSha256: String
    ) {
        init {
            require(notificationTag.matches(Regex("amper-proactive:[0-9a-f]{64}")))
            require(fingerprintSha256.matches(Regex("[0-9a-f]{64}")))
        }

        fun encode(): String =
            "$notificationId|$notificationTag|$fingerprintSha256"
    }

    private fun parseCheckpoint(raw: String?): AttentionCheckpoint? =
        raw?.split('|')
            ?.takeIf { it.size == 3 }
            ?.let { parts ->
                runCatching {
                    AttentionCheckpoint(
                        notificationId = parts[0].toInt(),
                        notificationTag = parts[1],
                        fingerprintSha256 = parts[2]
                    )
                }.getOrNull()
            }

    companion object {
        private const val PREFERENCES = "amper-proactive-attention-delivery-v1"
        private const val CHANNEL_ID = "amper-proactive-attention"
        private const val MAX_TRACKED = 64
    }
}

/**
 * Read-only Phase663 bridge from the canonical Phase662 lifecycle to Android attention delivery.
 */
class AndroidAgentProactiveAttentionController(
    context: Context,
    private val lifecycle: AmperAgentProactiveTaskLifecycleCoordinator
) {
    private val delivery = AndroidAgentProactiveAttentionDelivery(context)

    fun notificationsAllowed(): Boolean = delivery.notificationsAllowed()

    fun reconcilePlan(
        planId: PlanId
    ): Result<AndroidAgentProactiveAttentionDeliveryReport?> = runCatching {
        val view = lifecycle.findByPlanId(planId) ?: return@runCatching null
        delivery.reconcile(view).getOrThrow()
    }

    fun reconcileTracked(
        limit: Int = 32
    ): Result<AndroidAgentProactiveAttentionDeliveryReport> = runCatching {
        require(limit in 1..64)
        delivery.reconcileTracked(lifecycle.inspect(limit)).getOrThrow()
    }
}
