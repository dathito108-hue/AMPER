package io.amper.neuroos.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PersistableBundle
import io.amper.neuroos.core.v2.AmperAgentAndroidContinuationExecutionPort
import io.amper.neuroos.core.v2.AmperAgentAndroidContinuationHandoff
import io.amper.neuroos.core.v2.AmperAgentAndroidContinuationHostDispatcher
import io.amper.neuroos.core.v2.AmperAgentAndroidExecutionHost
import io.amper.neuroos.core.v2.AmperAgentAndroidHostExecutionResult
import io.amper.neuroos.core.v2.AmperAgentAndroidHostExecutionState
import io.amper.neuroos.core.v2.AmperAgentContinuationWakeDisposition
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Process-local bridge only. It owns no planner, ToolFabric, AuthorityGate, or task persistence.
 * Phase652 may provide cold-process canonical Agent Core restoration when no live port is registered.
 */
object AndroidAgentContinuationProcessRegistry {
    @Volatile
    private var execution: AmperAgentAndroidContinuationExecutionPort? = null

    @Synchronized
    fun register(value: AmperAgentAndroidContinuationExecutionPort) {
        execution = value
    }

    @Synchronized
    fun unregister(expected: AmperAgentAndroidContinuationExecutionPort) {
        if (execution === expected) {
            execution = null
        }
    }

    fun current(): AmperAgentAndroidContinuationExecutionPort? = execution
}

/**
 * Android scheduling boundary for Phase650 verified continuation handoffs.
 *
 * FOREGROUND_CONTINUATION is launched only through an explicit foreground-service intent.
 * PERSISTED_JOB uses one stable JobScheduler id per task/plan dedupe key so newer checkpoints
 * replace older pending jobs instead of accumulating stale wake-ups.
 */
class AndroidAgentContinuationScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val jobScheduler =
        appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

    fun handoff(value: AmperAgentAndroidContinuationHandoff): Result<Boolean> = runCatching {
        when (value.executionHost) {
            AmperAgentAndroidExecutionHost.VISIBLE_FOREGROUND_SERVICE -> {
                val intent = Intent(
                    appContext,
                    AgentContinuationForegroundService::class.java
                ).apply {
                    action = AgentContinuationForegroundService.ACTION_START
                    AndroidAgentContinuationTransport.writeToIntent(this, value)
                }
                appContext.startForegroundService(intent)
                true
            }

            AmperAgentAndroidExecutionHost.PERSISTED_JOB_SCHEDULER -> {
                val extras = PersistableBundle().also {
                    AndroidAgentContinuationTransport.writeToBundle(it, value)
                }
                val info = JobInfo.Builder(
                    AndroidAgentContinuationJobIdentity.jobIdFor(value),
                    ComponentName(appContext, AgentContinuationJobService::class.java)
                )
                    .setPersisted(true)
                    .setExtras(extras)
                    .build()
                jobScheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
            }
        }
    }

    fun cancel(value: AmperAgentAndroidContinuationHandoff) {
        when (value.executionHost) {
            AmperAgentAndroidExecutionHost.VISIBLE_FOREGROUND_SERVICE -> {
                val intent = Intent(
                    appContext,
                    AgentContinuationForegroundService::class.java
                ).apply {
                    action = AgentContinuationForegroundService.ACTION_STOP
                }
                runCatching { appContext.startService(intent) }
            }

            AmperAgentAndroidExecutionHost.PERSISTED_JOB_SCHEDULER ->
                jobScheduler.cancel(AndroidAgentContinuationJobIdentity.jobIdFor(value))
        }
    }
}

object AndroidAgentContinuationJobIdentity {
    private const val JOB_NAMESPACE_MASK = 0x21000000
    private const val JOB_PAYLOAD_MASK = 0x0fffffff

    fun jobIdFor(value: AmperAgentAndroidContinuationHandoff): Int {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.dedupeKey.toByteArray(Charsets.UTF_8))
        val raw =
            ((bytes[0].toInt() and 0xff) shl 24) or
                ((bytes[1].toInt() and 0xff) shl 16) or
                ((bytes[2].toInt() and 0xff) shl 8) or
                (bytes[3].toInt() and 0xff)
        return JOB_NAMESPACE_MASK or (raw and JOB_PAYLOAD_MASK)
    }
}

internal object AndroidAgentContinuationTransport {
    private const val EXTRA_TASK_ID = "agent.task_id"
    private const val EXTRA_PLAN_ID = "agent.plan_id"
    private const val EXTRA_HOST = "agent.host"
    private const val EXTRA_WAKE = "agent.wake"
    private const val EXTRA_DEDUPE = "agent.dedupe"
    private const val EXTRA_ENVELOPE = "agent.envelope"
    private const val EXTRA_ENVELOPE_SHA = "agent.envelope_sha256"
    private const val EXTRA_VISIBLE = "agent.visible"
    private const val EXTRA_PERSISTED = "agent.persisted"

    fun writeToIntent(
        intent: Intent,
        value: AmperAgentAndroidContinuationHandoff
    ) {
        intent.putExtra(EXTRA_TASK_ID, value.taskId)
        intent.putExtra(EXTRA_PLAN_ID, value.planId)
        intent.putExtra(EXTRA_HOST, value.executionHost.name)
        intent.putExtra(EXTRA_WAKE, value.wakeDisposition.name)
        intent.putExtra(EXTRA_DEDUPE, value.dedupeKey)
        intent.putExtra(EXTRA_ENVELOPE, value.encodedEnvelope)
        intent.putExtra(EXTRA_ENVELOPE_SHA, value.envelopeSha256)
        intent.putExtra(EXTRA_VISIBLE, value.requiresVisibleNotification)
        intent.putExtra(EXTRA_PERSISTED, value.persistedAcrossReboot)
    }

    fun readFromIntent(intent: Intent): Result<AmperAgentAndroidContinuationHandoff> =
        build(
            taskId = intent.getStringExtra(EXTRA_TASK_ID),
            planId = intent.getStringExtra(EXTRA_PLAN_ID),
            host = intent.getStringExtra(EXTRA_HOST),
            wake = intent.getStringExtra(EXTRA_WAKE),
            dedupe = intent.getStringExtra(EXTRA_DEDUPE),
            envelope = intent.getStringExtra(EXTRA_ENVELOPE),
            envelopeSha = intent.getStringExtra(EXTRA_ENVELOPE_SHA),
            visible = intent.getBooleanExtra(EXTRA_VISIBLE, false),
            persisted = intent.getBooleanExtra(EXTRA_PERSISTED, false)
        )

    fun writeToBundle(
        bundle: PersistableBundle,
        value: AmperAgentAndroidContinuationHandoff
    ) {
        bundle.putString(EXTRA_TASK_ID, value.taskId)
        bundle.putString(EXTRA_PLAN_ID, value.planId)
        bundle.putString(EXTRA_HOST, value.executionHost.name)
        bundle.putString(EXTRA_WAKE, value.wakeDisposition.name)
        bundle.putString(EXTRA_DEDUPE, value.dedupeKey)
        bundle.putString(EXTRA_ENVELOPE, value.encodedEnvelope)
        bundle.putString(EXTRA_ENVELOPE_SHA, value.envelopeSha256)
        bundle.putBoolean(EXTRA_VISIBLE, value.requiresVisibleNotification)
        bundle.putBoolean(EXTRA_PERSISTED, value.persistedAcrossReboot)
    }

    fun readFromBundle(
        bundle: PersistableBundle
    ): Result<AmperAgentAndroidContinuationHandoff> =
        build(
            taskId = bundle.getString(EXTRA_TASK_ID),
            planId = bundle.getString(EXTRA_PLAN_ID),
            host = bundle.getString(EXTRA_HOST),
            wake = bundle.getString(EXTRA_WAKE),
            dedupe = bundle.getString(EXTRA_DEDUPE),
            envelope = bundle.getString(EXTRA_ENVELOPE),
            envelopeSha = bundle.getString(EXTRA_ENVELOPE_SHA),
            visible = bundle.getBoolean(EXTRA_VISIBLE),
            persisted = bundle.getBoolean(EXTRA_PERSISTED)
        )

    private fun build(
        taskId: String?,
        planId: String?,
        host: String?,
        wake: String?,
        dedupe: String?,
        envelope: String?,
        envelopeSha: String?,
        visible: Boolean,
        persisted: Boolean
    ): Result<AmperAgentAndroidContinuationHandoff> = runCatching {
        AmperAgentAndroidContinuationHandoff(
            taskId = requireNotNull(taskId),
            planId = requireNotNull(planId),
            executionHost = AmperAgentAndroidExecutionHost.valueOf(requireNotNull(host)),
            wakeDisposition = AmperAgentContinuationWakeDisposition.valueOf(requireNotNull(wake)),
            dedupeKey = requireNotNull(dedupe),
            encodedEnvelope = requireNotNull(envelope),
            envelopeSha256 = requireNotNull(envelopeSha),
            requiresVisibleNotification = visible,
            persistedAcrossReboot = persisted
        )
    }
}

class AgentContinuationForegroundService : Service() {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "amper-agent-foreground").apply { isDaemon = true }
    }

    @Volatile
    private var active: Future<*>? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopContinuation()
            ACTION_START -> startContinuation(requireNotNull(intent))
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        active?.cancel(true)
        active = null
        worker.shutdownNow()
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun startContinuation(intent: Intent) {
        if (active?.isDone == false) return

        val handoff = AndroidAgentContinuationTransport
            .readFromIntent(intent)
            .getOrElse {
                stopSelf()
                return
            }
        if (
            handoff.executionHost !=
            AmperAgentAndroidExecutionHost.VISIBLE_FOREGROUND_SERVICE
        ) {
            stopSelf()
            return
        }

        startContinuationForeground(handoff)

        active = worker.submit {
            val result = AmperAgentAndroidContinuationHostDispatcher.dispatch(
                handoff = handoff,
                execution = AndroidAgentContinuationProcessRegistry.current()
            ).getOrElse { error ->
                AmperAgentAndroidHostExecutionResult(
                    state = AmperAgentAndroidHostExecutionState.RETRY_LATER,
                    detail = error.message ?: error::class.java.simpleName
                )
            }

            Handler(Looper.getMainLooper()).post {
                active = null
                when (result.state) {
                    AmperAgentAndroidHostExecutionState.WAITING_APPROVAL ->
                        updateWaitingApprovalNotification()
                    AmperAgentAndroidHostExecutionState.RETRY_LATER ->
                        updateRetryNotification()
                    AmperAgentAndroidHostExecutionState.ADVANCED,
                    AmperAgentAndroidHostExecutionState.CHECKPOINTED,
                    AmperAgentAndroidHostExecutionState.TERMINAL_NOOP ->
                        stopContinuation()
                }
            }
        }
    }

    private fun startContinuationForeground(
        handoff: AmperAgentAndroidContinuationHandoff
    ) {
        val notification = buildNotification(
            text = "Continuing task " + handoff.taskId,
            ongoing = true
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateWaitingApprovalNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(
                text = "Task is waiting for your approval in AMPER",
                ongoing = false
            )
        )
        stopForegroundCompat(removeNotification = false)
        stopSelf()
    }

    private fun updateRetryNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(
                text = "Task paused; reopen AMPER to continue",
                ongoing = false
            )
        )
        stopForegroundCompat(removeNotification = false)
        stopSelf()
    }

    private fun buildNotification(
        text: String,
        ongoing: Boolean
    ): Notification {
        val stopIntent = Intent(this, AgentContinuationForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            NOTIFICATION_ID,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("AMPER task continuation")
            .setContentText(text)
            .setOngoing(ongoing)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    stopPending
                ).build()
            )
            .build()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "AMPER task continuation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Visible while a user-requested AMPER task continues after UI exit"
                setShowBadge(false)
            }
        )
    }

    private fun stopContinuation() {
        active?.cancel(true)
        active = null
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat(removeNotification: Boolean = true) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(
                if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH
            )
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
    }

    companion object {
        const val ACTION_START = "io.amper.neuroos.agent.continuation.START"
        const val ACTION_STOP = "io.amper.neuroos.agent.continuation.STOP"

        private const val NOTIFICATION_CHANNEL_ID = "amper-agent-continuation"
        private const val NOTIFICATION_ID = 165
    }
}

class AgentContinuationJobService : JobService() {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "amper-agent-job").apply { isDaemon = true }
    }

    @Volatile
    private var active: Future<*>? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val handoff = AndroidAgentContinuationTransport
            .readFromBundle(params.extras)
            .getOrElse {
                jobFinished(params, false)
                return false
            }

        if (
            handoff.executionHost !=
            AmperAgentAndroidExecutionHost.PERSISTED_JOB_SCHEDULER
        ) {
            jobFinished(params, false)
            return false
        }

        active = worker.submit {
            val result = AmperAgentAndroidContinuationHostDispatcher.dispatch(
                handoff = handoff,
                execution = AndroidAgentContinuationProcessRegistry.current()
            ).getOrElse { error ->
                AmperAgentAndroidHostExecutionResult(
                    state = AmperAgentAndroidHostExecutionState.RETRY_LATER,
                    detail = error.message ?: error::class.java.simpleName
                )
            }
            Handler(Looper.getMainLooper()).post {
                active = null
                jobFinished(params, result.shouldReschedule)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        active?.cancel(true)
        active = null
        return true
    }

    override fun onDestroy() {
        active?.cancel(true)
        worker.shutdownNow()
        super.onDestroy()
    }
}
