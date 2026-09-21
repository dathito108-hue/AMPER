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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

interface AndroidAgentPendingTriggerDispatchRequester {
    fun request(sourceId: String, attempt: Int = 0): Result<Boolean>

    fun cancel(sourceId: String)
}

object NoopAndroidAgentPendingTriggerDispatchRequester :
    AndroidAgentPendingTriggerDispatchRequester {
    override fun request(sourceId: String, attempt: Int): Result<Boolean> =
        Result.success(false)

    override fun cancel(sourceId: String) = Unit
}

object AndroidAgentPendingTriggerDispatchPolicy {
    const val MAX_ATTEMPTS: Int = 4
    private const val MAX_RETRY_DELAY_MS: Long = 60L * 60L * 1000L

    fun requireAttempt(attempt: Int) {
        require(attempt in 0 until MAX_ATTEMPTS) {
            "pending trigger dispatch attempt exceeds bounded retry budget"
        }
    }

    fun schedulePlan(
        budget: ResourceBudget,
        attempt: Int
    ): AndroidAgentEventWakeSchedulePlan {
        requireAttempt(attempt)
        val base = AndroidAgentEventWakeResourcePolicy.plan(budget)
        val multiplier = attempt.toLong() + 1L
        return base.copy(
            minimumLatencyMs =
                (base.minimumLatencyMs * multiplier).coerceAtMost(MAX_RETRY_DELAY_MS)
        )
    }

    fun nextAttempt(attempt: Int): Int? {
        requireAttempt(attempt)
        val next = attempt + 1
        return next.takeIf { it < MAX_ATTEMPTS }
    }
}

object AndroidAgentPendingTriggerDispatchJobIdentity {
    private const val JOB_NAMESPACE = 0x44000000
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

    fun requireCollisionFree(sourceIds: Collection<String>) {
        val ids = sourceIds.map(::jobIdFor)
        require(ids.distinct().size == ids.size) {
            "pending trigger dispatch job-id collision detected"
        }
    }
}

internal data class AndroidAgentPendingTriggerDispatchJobToken(
    val sourceId: String,
    val attempt: Int
) {
    init {
        require(sourceId.matches(Regex("[a-z0-9][a-z0-9._:-]{0,127}")))
        AndroidAgentPendingTriggerDispatchPolicy.requireAttempt(attempt)
    }
}

internal object AndroidAgentPendingTriggerDispatchTransport {
    private const val EXTRA_SOURCE_ID = "agent.pending_trigger.source_id"
    private const val EXTRA_ATTEMPT = "agent.pending_trigger.attempt"

    fun write(
        bundle: PersistableBundle,
        token: AndroidAgentPendingTriggerDispatchJobToken
    ) {
        bundle.putString(EXTRA_SOURCE_ID, token.sourceId)
        bundle.putInt(EXTRA_ATTEMPT, token.attempt)
    }

    fun read(bundle: PersistableBundle): Result<AndroidAgentPendingTriggerDispatchJobToken> =
        runCatching {
            AndroidAgentPendingTriggerDispatchJobToken(
                sourceId = requireNotNull(bundle.getString(EXTRA_SOURCE_ID)),
                attempt = bundle.getInt(EXTRA_ATTEMPT, -1)
            )
        }
}

/**
 * Phase661 one-shot Android dispatcher for already-accepted trigger observations.
 *
 * The job id is stable per source, so crash/retry and repeated reconciliation replace the same
 * pending dispatcher instead of accumulating workers. This scheduler owns no planner, ToolFabric,
 * AuthorityGate, model, or execution path.
 */
class AndroidAgentPendingTriggerDispatchScheduler(
    context: Context,
    private val governor: ResourceGovernor = AndroidResourceGovernor(context)
) : AndroidAgentPendingTriggerDispatchRequester {
    private val appContext = context.applicationContext
    private val jobs =
        appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

    override fun request(sourceId: String, attempt: Int): Result<Boolean> = runCatching {
        AndroidAgentPendingTriggerDispatchPolicy.requireAttempt(attempt)
        val plan = AndroidAgentPendingTriggerDispatchPolicy.schedulePlan(
            governor.currentBudget(),
            attempt
        )
        val extras = PersistableBundle().also {
            AndroidAgentPendingTriggerDispatchTransport.write(
                it,
                AndroidAgentPendingTriggerDispatchJobToken(sourceId, attempt)
            )
        }
        val info = JobInfo.Builder(
            AndroidAgentPendingTriggerDispatchJobIdentity.jobIdFor(sourceId),
            ComponentName(appContext, AgentPendingTriggerDispatchJobService::class.java)
        )
            .setPersisted(plan.persistedAcrossReboot)
            .setRequiresBatteryNotLow(plan.requiresBatteryNotLow)
            .setRequiresStorageNotLow(plan.requiresStorageNotLow)
            .setMinimumLatency(plan.minimumLatencyMs)
            .setExtras(extras)
            .build()

        jobs.schedule(info) == JobScheduler.RESULT_SUCCESS
    }

    override fun cancel(sourceId: String) {
        jobs.cancel(AndroidAgentPendingTriggerDispatchJobIdentity.jobIdFor(sourceId))
    }
}

internal object AndroidAgentPendingTriggerDurabilitySequence {
    fun <T> commit(
        expectedEventWakeSchedule: Boolean,
        persistProvenance: () -> Result<Unit>,
        installEventWake: () -> Result<Boolean>,
        bestEffortBeforeAcknowledge: () -> Unit = {},
        acknowledgeFifo: () -> Result<T>
    ): Result<T> = runCatching {
        persistProvenance().getOrThrow()
        val scheduled = installEventWake().getOrThrow()
        require(scheduled == expectedEventWakeSchedule) {
            "Phase657 scheduler result drifted from proactive dispatch disposition"
        }
        runCatching(bestEffortBeforeAcknowledge)
        acknowledgeFifo().getOrThrow()
    }
}

/**
 * Durable Phase661 consumer of the Phase660 pending-observation FIFO.
 *
 * Ordering is strict:
 * 1. resource gate before canonical graph acquisition;
 * 2. bind the oldest accepted observation to its deterministic persistent plan;
 * 3. if runnable, install the verified handoff through the existing Phase657 scheduler;
 * 4. acknowledge the FIFO only after that binding/schedule is durable enough to rediscover;
 * 5. request the next FIFO item only after the current JobService wake has finished.
 */
class AgentPendingTriggerDispatchJobService : JobService() {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "amper-agent-pending-trigger-dispatch").apply { isDaemon = true }
    }
    private val active = ConcurrentHashMap<Int, Future<*>>()

    override fun onStartJob(params: JobParameters): Boolean {
        val token = AndroidAgentPendingTriggerDispatchTransport
            .read(params.extras)
            .getOrElse { return false }

        val future = worker.submit {
            val governor = AndroidResourceGovernor(applicationContext)
            val budget = runCatching { governor.currentBudget() }.getOrNull()
            if (
                budget == null ||
                !governor.allows(agentCount = 1) ||
                !AndroidAgentEventWakeResourcePolicy.shouldExecuteNow(budget)
            ) {
                retryOrFinish(params, token)
                return@submit
            }

            val graph = runCatching {
                AndroidCanonicalSovereignRuntimeBootstrap.acquire(applicationContext)
            }.getOrElse {
                retryOrFinish(params, token)
                return@submit
            }
            val registry = graph.agent.agentTriggerSources
            val state = registry.get(token.sourceId)
            if (
                state == null ||
                !state.source.enabled ||
                state.pendingObservations.isEmpty()
            ) {
                finish(params)
                return@submit
            }

            val binding = graph.agent.agentPendingTriggerDispatch
                .bindOldest(
                    sourceId = token.sourceId,
                    conversationId = graph.runtime.conversations.primary()
                )
                .getOrElse {
                    retryOrFinish(params, token)
                    return@submit
                }
                ?: run {
                    finish(params)
                    return@submit
                }

            // Phase662 crash-safe ordering is locked by a pure tested sequence:
            // canonical binding -> durable immutable provenance -> Phase657 install/cancel -> FIFO ACK.
            // If scheduling fails after provenance is durable, foreground reconciliation can
            // rediscover the exact canonical plan and re-arm the same stable EVENT_WAKE identity.
            val acknowledged = AndroidAgentPendingTriggerDurabilitySequence
                .commit(
                    expectedEventWakeSchedule = binding.requiresEventWakeSchedule,
                    persistProvenance = {
                        graph.agent.agentProactiveLifecycle
                            .recordDispatch(binding)
                            .map { Unit }
                    },
                    installEventWake = {
                        AndroidAgentEventWakeScheduler(
                            applicationContext,
                            graph.governor
                        ).handoff(binding.handoff)
                    },
                    bestEffortBeforeAcknowledge = {
                        graph.agent.agentProactiveAttention
                            .syncPlan(
                                binding.planId,
                                AndroidAgentProactiveAttentionSurfaceMode
                                    .BACKGROUND_TRANSITION
                            )
                            .getOrThrow()
                    },
                    acknowledgeFifo = {
                        registry.acknowledgePending(
                            sourceId = token.sourceId,
                            observationIdentitySha256 = binding.observationIdentitySha256
                        )
                    }
                )
                .getOrElse {
                    // A runnable handoff may already be installed or provenance may already exist.
                    // Retry is safe because plan, lifecycle binding, and Phase657 wake identities are
                    // deterministic/idempotent while FIFO remains the final acknowledgement gate.
                    retryOrFinish(params, token)
                    return@submit
                }

            val hasMore = acknowledged.source.enabled &&
                acknowledged.pendingObservations.isNotEmpty()
            finish(params) {
                if (hasMore) {
                    AndroidAgentPendingTriggerDispatchScheduler(applicationContext)
                        .request(token.sourceId, attempt = 0)
                }
            }
        }
        active.put(params.jobId, future)?.cancel(true)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        active.remove(params.jobId)?.cancel(true)
        // The FIFO remains durable. Do not create an unbounded Android retry loop here; foreground
        // reconciliation, re-enable, or the next source observation will request dispatch again.
        return false
    }

    override fun onDestroy() {
        active.values.forEach { it.cancel(true) }
        active.clear()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun retryOrFinish(
        params: JobParameters,
        token: AndroidAgentPendingTriggerDispatchJobToken
    ) {
        val next = AndroidAgentPendingTriggerDispatchPolicy.nextAttempt(token.attempt)
        finish(params) {
            next?.let {
                AndroidAgentPendingTriggerDispatchScheduler(applicationContext)
                    .request(token.sourceId, it)
            }
        }
    }

    private fun finish(
        params: JobParameters,
        afterFinish: (() -> Unit)? = null
    ) {
        Handler(Looper.getMainLooper()).post {
            active.remove(params.jobId)
            jobFinished(params, false)
            afterFinish?.let { next ->
                Handler(Looper.getMainLooper()).post(next)
            }
        }
    }
}
