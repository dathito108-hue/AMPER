package io.amper.neuroos.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import io.amper.neuroos.core.v2.AmperAgentProactiveAttentionKind
import io.amper.neuroos.core.v2.AmperAgentProactiveAttentionPolicy
import io.amper.neuroos.core.v2.AmperAgentProactiveTaskLifecycleCoordinator
import io.amper.neuroos.core.v2.AmperAgentProactiveTaskLifecycleView

enum class AndroidAgentProactiveAttentionSurfaceMode {
    BACKGROUND_TRANSITION,
    FOREGROUND_RECONCILE,
    USER_INTERACTION
}

enum class AndroidAgentProactiveAttentionDelivery {
    POSTED,
    CANCELLED,
    SUPPRESSED,
    NOT_TRACKED
}

data class AndroidAgentProactiveAttentionPermissionStatus(
    val runtimePermissionRequired: Boolean,
    val runtimePermissionGranted: Boolean,
    val notificationsEnabled: Boolean,
    val channelEnabled: Boolean
) {
    val canPost: Boolean
        get() = runtimePermissionGranted && notificationsEnabled && channelEnabled
}

data class AndroidAgentProactiveAttentionReconcileReport(
    val tracked: Int,
    val posted: Int,
    val cancelled: Int,
    val suppressed: Int
) {
    init {
        require(tracked >= 0)
        require(posted >= 0)
        require(cancelled >= 0)
        require(suppressed >= 0)
        require(posted + cancelled + suppressed <= tracked)
    }
}

object AndroidAgentProactiveAttentionIdentity {
    const val NOTIFICATION_ID: Int = 663
    const val TAG_PREFIX: String = "amper.proactive.plan:"
    const val EXTRA_PLAN_ID: String = "io.amper.neuroos.extra.PROACTIVE_PLAN_ID"
    const val ACTION_OPEN: String = "io.amper.neuroos.agent.proactive.OPEN"

    fun tag(planId: PlanId): String = TAG_PREFIX + planId.value

    fun navigationData(planId: PlanId): Uri =
        Uri.Builder()
            .scheme("amper")
            .authority("proactive-plan")
            .appendPath(planId.value)
            .build()
}

object AndroidAgentProactiveAttentionPermission {
    fun status(context: Context): AndroidAgentProactiveAttentionPermissionStatus {
        val appContext = context.applicationContext
        val manager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val runtimeRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val runtimeGranted =
            !runtimeRequired ||
                appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        val channel = manager.getNotificationChannel(
            AndroidAgentProactiveAttentionController.NOTIFICATION_CHANNEL_ID
        )
        return AndroidAgentProactiveAttentionPermissionStatus(
            runtimePermissionRequired = runtimeRequired,
            runtimePermissionGranted = runtimeGranted,
            notificationsEnabled = manager.areNotificationsEnabled(),
            channelEnabled = channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
        )
    }
}

/**
 * Phase663 attention-only adapter.
 *
 * It owns no JobService, scheduler, planner, approval action, ToolFabric, AuthorityGate, or mutable
 * task persistence. It derives attention solely from Phase662 lifecycle views and uses Android's
 * notification surface as a bounded discoverability layer.
 */
class AndroidAgentProactiveAttentionController(
    context: Context,
    private val lifecycle: AmperAgentProactiveTaskLifecycleCoordinator
) {
    private val appContext = context.applicationContext
    private val manager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        ensureNotificationChannel()
    }

    fun permissionStatus(): AndroidAgentProactiveAttentionPermissionStatus =
        AndroidAgentProactiveAttentionPermission.status(appContext)

    fun syncPlan(
        planId: PlanId,
        mode: AndroidAgentProactiveAttentionSurfaceMode
    ): Result<AndroidAgentProactiveAttentionDelivery> = runCatching {
        val view = lifecycle.findByPlanId(planId)
        if (view == null) {
            cancel(planId)
            return@runCatching AndroidAgentProactiveAttentionDelivery.NOT_TRACKED
        }
        syncView(view, mode)
    }

    fun reconcileTracked(
        mode: AndroidAgentProactiveAttentionSurfaceMode =
            AndroidAgentProactiveAttentionSurfaceMode.FOREGROUND_RECONCILE,
        limit: Int = 32
    ): Result<AndroidAgentProactiveAttentionReconcileReport> = runCatching {
        require(limit in 1..64)
        val views = lifecycle.inspect(limit)
        var posted = 0
        var cancelled = 0
        var suppressed = 0
        views.forEach { view ->
            when (syncView(view, mode)) {
                AndroidAgentProactiveAttentionDelivery.POSTED -> posted += 1
                AndroidAgentProactiveAttentionDelivery.CANCELLED -> cancelled += 1
                AndroidAgentProactiveAttentionDelivery.SUPPRESSED -> suppressed += 1
                AndroidAgentProactiveAttentionDelivery.NOT_TRACKED -> Unit
            }
        }

        val trackedTags = views.mapTo(linkedSetOf()) {
            AndroidAgentProactiveAttentionIdentity.tag(it.binding.planId)
        }
        manager.activeNotifications
            .asSequence()
            .mapNotNull { it.tag }
            .filter { it.startsWith(AndroidAgentProactiveAttentionIdentity.TAG_PREFIX) }
            .filterNot { it in trackedTags }
            .forEach { staleTag ->
                manager.cancel(staleTag, AndroidAgentProactiveAttentionIdentity.NOTIFICATION_ID)
            }

        AndroidAgentProactiveAttentionReconcileReport(
            tracked = views.size,
            posted = posted,
            cancelled = cancelled,
            suppressed = suppressed
        )
    }

    fun cancel(planId: PlanId) {
        manager.cancel(
            AndroidAgentProactiveAttentionIdentity.tag(planId),
            AndroidAgentProactiveAttentionIdentity.NOTIFICATION_ID
        )
    }

    private fun syncView(
        view: AmperAgentProactiveTaskLifecycleView,
        mode: AndroidAgentProactiveAttentionSurfaceMode
    ): AndroidAgentProactiveAttentionDelivery {
        val decision = AmperAgentProactiveAttentionPolicy.decide(view)
        val shouldPost = when (decision.kind) {
            AmperAgentProactiveAttentionKind.NONE -> false
            AmperAgentProactiveAttentionKind.APPROVAL_REQUIRED -> true
            AmperAgentProactiveAttentionKind.COMPLETED,
            AmperAgentProactiveAttentionKind.FAILED ->
                mode == AndroidAgentProactiveAttentionSurfaceMode.BACKGROUND_TRANSITION
            AmperAgentProactiveAttentionKind.PLAN_UNAVAILABLE -> true
        }

        if (!shouldPost) {
            cancel(decision.planId)
            return AndroidAgentProactiveAttentionDelivery.CANCELLED
        }

        if (!permissionStatus().canPost) {
            return AndroidAgentProactiveAttentionDelivery.SUPPRESSED
        }

        manager.notify(
            AndroidAgentProactiveAttentionIdentity.tag(decision.planId),
            AndroidAgentProactiveAttentionIdentity.NOTIFICATION_ID,
            buildNotification(decision.kind, decision.planId, decision.waitingApprovalStepIndex)
        )
        return AndroidAgentProactiveAttentionDelivery.POSTED
    }

    private fun buildNotification(
        kind: AmperAgentProactiveAttentionKind,
        planId: PlanId,
        waitingApprovalStepIndex: Int?
    ): Notification {
        val navigation = Intent(AndroidAgentProactiveAttentionIdentity.ACTION_OPEN)
            .setClassName(appContext, "io.amper.neuroos.MainActivity")
            .setData(AndroidAgentProactiveAttentionIdentity.navigationData(planId))
            .putExtra(AndroidAgentProactiveAttentionIdentity.EXTRA_PLAN_ID, planId.value)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val openPending = PendingIntent.getActivity(
            appContext,
            AndroidAgentProactiveAttentionIdentity.NOTIFICATION_ID,
            navigation,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val (title, text, category, ongoing, autoCancel) = when (kind) {
            AmperAgentProactiveAttentionKind.APPROVAL_REQUIRED ->
                Quintuple(
                    "AMPER needs your approval",
                    "A proactive task is waiting at governed approval step " +
                        requireNotNull(waitingApprovalStepIndex) + ".",
                    Notification.CATEGORY_REMINDER,
                    true,
                    false
                )
            AmperAgentProactiveAttentionKind.COMPLETED ->
                Quintuple(
                    "AMPER proactive task completed",
                    "Open AMPER to review the durable result.",
                    Notification.CATEGORY_STATUS,
                    false,
                    true
                )
            AmperAgentProactiveAttentionKind.FAILED ->
                Quintuple(
                    "AMPER proactive task needs review",
                    "The durable task reached a non-success terminal state.",
                    Notification.CATEGORY_STATUS,
                    false,
                    true
                )
            AmperAgentProactiveAttentionKind.PLAN_UNAVAILABLE ->
                Quintuple(
                    "AMPER proactive task needs review",
                    "Canonical plan state is unavailable; execution remains fail-closed.",
                    Notification.CATEGORY_ERROR,
                    true,
                    false
                )
            AmperAgentProactiveAttentionKind.NONE ->
                error("non-attention state cannot build a notification")
        }

        return Notification.Builder(appContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(category)
            .setContentIntent(openPending)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(autoCancel)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }

    private fun ensureNotificationChannel() {
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "AMPER proactive tasks",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description =
                    "Approval and completion attention for user-configured proactive AMPER tasks"
                setShowBadge(true)
            }
        )
    }

    private data class Quintuple(
        val first: String,
        val second: String,
        val third: String,
        val fourth: Boolean,
        val fifth: Boolean
    )

    companion object {
        const val NOTIFICATION_CHANNEL_ID: String = "amper-proactive-attention"
    }
}
