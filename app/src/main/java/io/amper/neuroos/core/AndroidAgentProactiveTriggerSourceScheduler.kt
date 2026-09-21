package io.amper.neuroos.core

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import io.amper.neuroos.core.v2.AmperAgentAcceptedProactiveTriggerObservation
import io.amper.neuroos.core.v2.AmperAgentPersistedProactiveTriggerSource
import io.amper.neuroos.core.v2.AmperAgentProactiveTriggerObservation
import io.amper.neuroos.core.v2.AmperAgentProactiveTriggerSource
import io.amper.neuroos.core.v2.AmperAgentProactiveTriggerSourceKind
import io.amper.neuroos.core.v2.AmperAgentProactiveTriggerSourceRegistry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Future

data class AndroidAgentScheduledTriggerSourcePlan(
    val sourceId: String,
    val configurationSha256: String,
    val intervalMs: Long,
    val persistedAcrossReboot: Boolean = true,
    val requiresBatteryNotLow: Boolean = true,
    val requiresStorageNotLow: Boolean = true
) {
    init {
        require(sourceId.isNotBlank())
        require(configurationSha256.matches(Regex("[0-9a-f]{64}")))
        require(
            intervalMs >=
                AmperAgentProactiveTriggerSource.SCHEDULED_WINDOW_MIN_INTERVAL_MS
        )
        require(persistedAcrossReboot)
        require(requiresBatteryNotLow)
        require(requiresStorageNotLow)
    }
}

/**
 * Phase660 source-side policy.
 *
 * SCHEDULED_WINDOW is the only source kind that may own an Android periodic job. APP_LOCAL_EVENT
 * remains event-driven and never receives a background polling schedule.
 */
object AndroidAgentScheduledTriggerSourcePolicy {
    const val MAX_REGISTERED_SOURCES: Int = 32
    const val MAX_SCHEDULED_SOURCES: Int = 16

    fun plan(
        state: AmperAgentPersistedProactiveTriggerSource
    ): AndroidAgentScheduledTriggerSourcePlan? {
        val source = state.source
        if (!source.enabled) return null
        if (source.kind != AmperAgentProactiveTriggerSourceKind.SCHEDULED_WINDOW) return null

        return AndroidAgentScheduledTriggerSourcePlan(
            sourceId = source.sourceId,
            configurationSha256 = source.configurationSha256,
            intervalMs = source.minimumIntervalMs
        )
    }

    fun observation(
        state: AmperAgentPersistedProactiveTriggerSource,
        observedAtEpochMs: Long
    ): Result<AmperAgentProactiveTriggerObservation> = runCatching {
        val source = state.source
        require(source.enabled)
        require(source.kind == AmperAgentProactiveTriggerSourceKind.SCHEDULED_WINDOW)
        require(observedAtEpochMs >= 0L)

        AmperAgentProactiveTriggerObservation(
            sourceId = source.sourceId,
            observedAtEpochMs = observedAtEpochMs,
            payloadDigest = sha256(
                listOf(
                    "scheduled-window",
                    source.sourceId,
                    source.configurationId,
                    source.configurationSha256,
                    observedAtEpochMs.toString()
                ).joinToString("|")
            )
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

object AndroidAgentTriggerSourceJobIdentity {
    private const val JOB_NAMESPACE = 0x43000000
    private const val JOB_NAMESPACE_MASK = -0x1000000
    private const val JOB_PAYLOAD_MASK = 0x00ffffff

    fun jobIdFor(sourceId: String): Int {
        require(sourceId.isNotBlank())
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(sourceId.toByteArray(StandardCharsets.UTF_8))
        val raw =
            ((bytes[0].toInt() and 0xff) shl 16) or
                ((bytes[1].toInt() and 0xff) shl 8) or
                (bytes[2].toInt() and 0xff)
        return JOB_NAMESPACE or (raw and JOB_PAYLOAD_MASK)
    }

    fun owns(jobId: Int): Boolean =
        (jobId and JOB_NAMESPACE_MASK) == JOB_NAMESPACE
}

internal data class AndroidAgentTriggerSourceJobToken(
    val sourceId: String,
    val configurationSha256: String
) {
    init {
        require(sourceId.matches(Regex("[a-z0-9][a-z0-9._:-]{0,127}")))
        require(configurationSha256.matches(Regex("[0-9a-f]{64}")))
    }
}

internal object AndroidAgentTriggerSourceTransport {
    private const val EXTRA_SOURCE_ID = "agent.trigger.source_id"
    private const val EXTRA_CONFIGURATION_SHA = "agent.trigger.configuration_sha256"

    fun write(
        bundle: PersistableBundle,
        token: AndroidAgentTriggerSourceJobToken
    ) {
        bundle.putString(EXTRA_SOURCE_ID, token.sourceId)
        bundle.putString(EXTRA_CONFIGURATION_SHA, token.configurationSha256)
    }

    fun read(bundle: PersistableBundle): Result<AndroidAgentTriggerSourceJobToken> =
        runCatching {
            AndroidAgentTriggerSourceJobToken(
                sourceId = requireNotNull(bundle.getString(EXTRA_SOURCE_ID)),
                configurationSha256 =
                    requireNotNull(bundle.getString(EXTRA_CONFIGURATION_SHA))
            )
        }
}

data class AndroidAgentTriggerSourceReconcileReport(
    val registeredSources: Int,
    val desiredScheduledSources: Int,
    val scheduledSources: Int,
    val cancelledStaleJobs: Int,
    val scheduleFailures: Int
) {
    init {
        require(registeredSources >= 0)
        require(desiredScheduledSources >= 0)
        require(scheduledSources >= 0)
        require(cancelledStaleJobs >= 0)
        require(scheduleFailures >= 0)
        require(scheduledSources + scheduleFailures == desiredScheduledSources)
    }

    val fullyApplied: Boolean
        get() = scheduleFailures == 0
}

interface AndroidAgentTriggerSourceScheduleReconciler {
    fun reconcile(
        states: List<AmperAgentPersistedProactiveTriggerSource>
    ): Result<AndroidAgentTriggerSourceReconcileReport>

    fun cancelSource(sourceId: String)
}

/**
 * Android periodic scheduling adapter for already-persisted trigger source registrations.
 *
 * It owns no observation qualification, Agent admission, planner, tool authority, model, or task
 * execution. Its only job is to keep OS scheduled-window jobs aligned with canonical source state.
 */
class AndroidAgentTriggerSourceJobScheduler(
    context: Context
) : AndroidAgentTriggerSourceScheduleReconciler {
    private val appContext = context.applicationContext
    private val jobs =
        appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

    override fun reconcile(
        states: List<AmperAgentPersistedProactiveTriggerSource>
    ): Result<AndroidAgentTriggerSourceReconcileReport> = runCatching {
        require(
            states.map { it.source.sourceId }.distinct().size == states.size
        ) {
            "duplicate proactive trigger source state during Android reconciliation"
        }
        require(states.size <= AndroidAgentScheduledTriggerSourcePolicy.MAX_REGISTERED_SOURCES) {
            "too many proactive trigger registrations for bounded Android reconciliation"
        }

        val desired = states.mapNotNull { state ->
            AndroidAgentScheduledTriggerSourcePolicy.plan(state)?.let { state to it }
        }
        require(
            desired.size <= AndroidAgentScheduledTriggerSourcePolicy.MAX_SCHEDULED_SOURCES
        ) {
            "too many scheduled proactive trigger sources for bounded Android execution"
        }

        val desiredJobIds = desired
            .mapTo(linkedSetOf()) { (_, plan) ->
                AndroidAgentTriggerSourceJobIdentity.jobIdFor(plan.sourceId)
            }

        var cancelled = 0
        jobs.allPendingJobs
            .asSequence()
            .filter { AndroidAgentTriggerSourceJobIdentity.owns(it.id) }
            .filter { it.id !in desiredJobIds }
            .forEach {
                jobs.cancel(it.id)
                cancelled += 1
            }

        var scheduled = 0
        var failures = 0
        desired.forEach { (_, plan) ->
            val token = AndroidAgentTriggerSourceJobToken(
                sourceId = plan.sourceId,
                configurationSha256 = plan.configurationSha256
            )
            val extras = PersistableBundle().also {
                AndroidAgentTriggerSourceTransport.write(it, token)
            }
            val info = JobInfo.Builder(
                AndroidAgentTriggerSourceJobIdentity.jobIdFor(plan.sourceId),
                ComponentName(appContext, AgentTriggerSourceJobService::class.java)
            )
                .setPersisted(plan.persistedAcrossReboot)
                .setRequiresBatteryNotLow(plan.requiresBatteryNotLow)
                .setRequiresStorageNotLow(plan.requiresStorageNotLow)
                .setExtras(extras)
                .setPeriodic(plan.intervalMs)
                .build()

            if (jobs.schedule(info) == JobScheduler.RESULT_SUCCESS) {
                scheduled += 1
            } else {
                failures += 1
            }
        }

        AndroidAgentTriggerSourceReconcileReport(
            registeredSources = states.size,
            desiredScheduledSources = desired.size,
            scheduledSources = scheduled,
            cancelledStaleJobs = cancelled,
            scheduleFailures = failures
        )
    }

    override fun cancelSource(sourceId: String) {
        jobs.cancel(AndroidAgentTriggerSourceJobIdentity.jobIdFor(sourceId))
    }
}

/**
 * Canonical Android mutation/reconciliation boundary for persisted trigger registrations.
 *
 * APP_LOCAL_EVENT acceptance is explicit and event-driven. SCHEDULED_WINDOW changes are reconciled
 * into JobScheduler. No source mutation can directly create a plan or invoke a tool.
 */
class AndroidAgentProactiveTriggerSourceController(
    context: Context,
    private val registry: AmperAgentProactiveTriggerSourceRegistry,
    private val scheduler: AndroidAgentTriggerSourceScheduleReconciler =
        AndroidAgentTriggerSourceJobScheduler(context)
) {
    fun upsert(
        source: AmperAgentProactiveTriggerSource,
        updatedAtEpochMs: Long = System.currentTimeMillis()
    ): Result<AmperAgentPersistedProactiveTriggerSource> = runCatching {
        if (registry.get(source.sourceId) == null) {
            require(
                registry.list(
                    AndroidAgentScheduledTriggerSourcePolicy.MAX_REGISTERED_SOURCES
                ).size <
                    AndroidAgentScheduledTriggerSourcePolicy.MAX_REGISTERED_SOURCES
            ) {
                "proactive trigger registration limit reached"
            }
        }

        val state = registry.upsert(source, updatedAtEpochMs)
        reconcileAll().getOrThrow()
        state
    }

    fun setEnabled(
        sourceId: String,
        enabled: Boolean,
        updatedAtEpochMs: Long = System.currentTimeMillis()
    ): Result<AmperAgentPersistedProactiveTriggerSource> = runCatching {
        val state = registry
            .setEnabled(sourceId, enabled, updatedAtEpochMs)
            .getOrThrow()
        reconcileAll().getOrThrow()
        state
    }

    fun remove(sourceId: String): Result<Boolean> = runCatching {
        val removed = registry.remove(sourceId)
        scheduler.cancelSource(sourceId)
        reconcileAll().getOrThrow()
        removed
    }

    fun reconcileAll(): Result<AndroidAgentTriggerSourceReconcileReport> = runCatching {
        val states = registry.list(
            AndroidAgentScheduledTriggerSourcePolicy.MAX_REGISTERED_SOURCES + 1
        )
        require(
            states.size <= AndroidAgentScheduledTriggerSourcePolicy.MAX_REGISTERED_SOURCES
        ) {
            "persisted proactive trigger registration count exceeds Android bound"
        }
        scheduler.reconcile(states).getOrThrow()
    }

    fun observeAppLocalEvent(
        sourceId: String,
        observedAtEpochMs: Long,
        payloadDigest: String
    ): Result<AmperAgentAcceptedProactiveTriggerObservation> = runCatching {
        val state = requireNotNull(registry.get(sourceId)) {
            "app-local proactive trigger source is not registered"
        }
        require(
            state.source.kind == AmperAgentProactiveTriggerSourceKind.APP_LOCAL_EVENT
        ) {
            "scheduled-window source cannot be injected through app-local event adapter"
        }
        registry.accept(
            AmperAgentProactiveTriggerObservation(
                sourceId = sourceId,
                observedAtEpochMs = observedAtEpochMs,
                payloadDigest = payloadDigest
            )
        ).getOrThrow()
    }
}

/**
 * Periodic source observation host.
 *
 * The job emits only a bounded scheduled-window observation into the Phase659 registry. It does not
 * create a sovereign plan or EVENT_WAKE execution job in Phase660; that durable dispatch binding is
 * the next M5 slice. APP_LOCAL_EVENT has no JobService path.
 */
class AgentTriggerSourceJobService : JobService() {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "amper-agent-trigger-source").apply { isDaemon = true }
    }

    @Volatile
    private var active: Future<*>? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val token = AndroidAgentTriggerSourceTransport
            .read(params.extras)
            .getOrElse { return false }

        active = worker.submit {
            val graph = AndroidCanonicalSovereignRuntimeBootstrap
                .acquire(applicationContext)
            val state = graph.agent.agentTriggerSources.get(token.sourceId)

            if (
                state == null ||
                !state.source.enabled ||
                state.source.kind !=
                    AmperAgentProactiveTriggerSourceKind.SCHEDULED_WINDOW
            ) {
                AndroidAgentTriggerSourceJobScheduler(applicationContext)
                    .cancelSource(token.sourceId)
                finish(params)
                return@submit
            }

            if (state.source.configurationSha256 != token.configurationSha256) {
                // A newer registration may already have replaced this stable job id. Never cancel
                // on revision mismatch because doing so could remove the newer schedule.
                finish(params)
                return@submit
            }

            val observation = AndroidAgentScheduledTriggerSourcePolicy
                .observation(state, System.currentTimeMillis())
            observation.onSuccess {
                graph.agent.agentTriggerSources.accept(it)
            }
            finish(params)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        active?.cancel(true)
        active = null
        // This is a periodic source clock, not an execution transaction. The next bounded period
        // is sufficient; do not request an immediate retry loop.
        return false
    }

    override fun onDestroy() {
        active?.cancel(true)
        active = null
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun finish(params: JobParameters) {
        Handler(Looper.getMainLooper()).post {
            active = null
            jobFinished(params, false)
        }
    }
}
