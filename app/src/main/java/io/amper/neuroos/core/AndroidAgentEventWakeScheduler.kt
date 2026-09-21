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
import android.os.PowerManager
import io.amper.neuroos.core.v2.AmperAgentEventWakeDisposition
import io.amper.neuroos.core.v2.AmperAgentEventWakeHandoff
import io.amper.neuroos.core.v2.AmperAgentEventWakeHandoffPolicy
import io.amper.neuroos.core.v2.AmperAgentEventWakeHostExecutionState
import io.amper.neuroos.core.v2.AmperAgentPlanTaskCheckpoint
import io.amper.neuroos.core.v2.AmperAgentProactiveEventWakeCoordinator
import io.amper.neuroos.core.v2.AmperAgentTaskAdmission
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

data class AndroidAgentEventWakeSchedulePlan(
    val minimumLatencyMs: Long,
    val requiresBatteryNotLow: Boolean,
    val requiresStorageNotLow: Boolean,
    val persistedAcrossReboot: Boolean
) {
    init {
        require(minimumLatencyMs >= 0L)
        require(requiresBatteryNotLow)
        require(requiresStorageNotLow)
        require(persistedAcrossReboot)
    }
}

/**
 * Mobile resource policy for proactive Agent wakes.
 *
 * Android has no JobScheduler thermal constraint, so severe/critical thermal pressure becomes
 * bounded minimum latency. Battery-not-low is always required. The policy never changes task
 * authority, capabilities, or plan state.
 */
object AndroidAgentEventWakeResourcePolicy {
    const val BASE_WAKE_DELAY_MS: Long = 15_000L
    private const val LOW_MEMORY_BUDGET_MB = 384
    private const val LOW_MEMORY_DELAY_MS = 2L * 60L * 1000L
    private const val SEVERE_THERMAL_DELAY_MS = 5L * 60L * 1000L
    private const val CRITICAL_THERMAL_DELAY_MS = 15L * 60L * 1000L

    fun plan(budget: ResourceBudget): AndroidAgentEventWakeSchedulePlan {
        val latency = when {
            budget.thermalClass >= PowerManager.THERMAL_STATUS_CRITICAL ->
                CRITICAL_THERMAL_DELAY_MS
            budget.thermalClass >= PowerManager.THERMAL_STATUS_SEVERE ->
                SEVERE_THERMAL_DELAY_MS
            budget.memoryMb < LOW_MEMORY_BUDGET_MB ->
                LOW_MEMORY_DELAY_MS
            else -> BASE_WAKE_DELAY_MS
        }
        return AndroidAgentEventWakeSchedulePlan(
            minimumLatencyMs = latency,
            requiresBatteryNotLow = true,
            requiresStorageNotLow = true,
            persistedAcrossReboot = true
        )
    }

    fun shouldExecuteNow(budget: ResourceBudget): Boolean =
        budget.maxConcurrentAgents >= 1 &&
            budget.memoryMb >= LOW_MEMORY_BUDGET_MB &&
            budget.thermalClass < PowerManager.THERMAL_STATUS_SEVERE
}

object AndroidAgentEventWakeSchedulingAdmission {
    fun admit(value: AmperAgentEventWakeHandoff): Result<Boolean> = runCatching {
        val verified = AmperAgentEventWakeHandoffPolicy
            .verifyAndDecode(value)
            .getOrThrow()
        require(verified.disposition == value.disposition)
        value.runnable
    }
}

object AndroidAgentEventWakeJobIdentity {
    private const val JOB_NAMESPACE_MASK = 0x32000000
    private const val JOB_PAYLOAD_MASK = 0x0fffffff

    fun jobIdFor(value: AmperAgentEventWakeHandoff): Int {
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

internal object AndroidAgentEventWakeTransport {
    private const val EXTRA_TASK_ID = "agent.event.task_id"
    private const val EXTRA_PLAN_ID = "agent.event.plan_id"
    private const val EXTRA_TRIGGER_ID = "agent.event.trigger_id"
    private const val EXTRA_TRIGGER_PAYLOAD_SHA = "agent.event.trigger_payload_sha256"
    private const val EXTRA_DISPOSITION = "agent.event.disposition"
    private const val EXTRA_DEDUPE = "agent.event.dedupe"
    private const val EXTRA_ENVELOPE = "agent.event.envelope"
    private const val EXTRA_ENVELOPE_SHA = "agent.event.envelope_sha256"

    fun writeToBundle(
        bundle: PersistableBundle,
        value: AmperAgentEventWakeHandoff
    ) {
        bundle.putString(EXTRA_TASK_ID, value.taskId)
        bundle.putString(EXTRA_PLAN_ID, value.planId)
        bundle.putString(EXTRA_TRIGGER_ID, value.triggerId)
        bundle.putString(EXTRA_TRIGGER_PAYLOAD_SHA, value.triggerPayloadSha256)
        bundle.putString(EXTRA_DISPOSITION, value.disposition.name)
        bundle.putString(EXTRA_DEDUPE, value.dedupeKey)
        bundle.putString(EXTRA_ENVELOPE, value.encodedEnvelope)
        bundle.putString(EXTRA_ENVELOPE_SHA, value.envelopeSha256)
    }

    fun readFromBundle(
        bundle: PersistableBundle
    ): Result<AmperAgentEventWakeHandoff> = runCatching {
        AmperAgentEventWakeHandoff(
            taskId = requireNotNull(bundle.getString(EXTRA_TASK_ID)),
            planId = requireNotNull(bundle.getString(EXTRA_PLAN_ID)),
            triggerId = requireNotNull(bundle.getString(EXTRA_TRIGGER_ID)),
            triggerPayloadSha256 =
                requireNotNull(bundle.getString(EXTRA_TRIGGER_PAYLOAD_SHA)),
            disposition = AmperAgentEventWakeDisposition.valueOf(
                requireNotNull(bundle.getString(EXTRA_DISPOSITION))
            ),
            dedupeKey = requireNotNull(bundle.getString(EXTRA_DEDUPE)),
            encodedEnvelope = requireNotNull(bundle.getString(EXTRA_ENVELOPE)),
            envelopeSha256 = requireNotNull(bundle.getString(EXTRA_ENVELOPE_SHA))
        )
    }
}

/**
 * Phase657 scheduler for already-verified proactive EVENT_WAKE handoffs.
 *
 * Stable job identity means a fresh checkpoint replaces an older pending checkpoint for the same
 * task/plan/trigger observation. Approval-blocked and terminal handoffs are cancelled rather than
 * scheduled.
 */
class AndroidAgentEventWakeScheduler(
    context: Context,
    private val governor: ResourceGovernor = AndroidResourceGovernor(context)
) {
    private val appContext = context.applicationContext
    private val jobScheduler =
        appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

    fun handoff(value: AmperAgentEventWakeHandoff): Result<Boolean> = runCatching {
        val runnable = AndroidAgentEventWakeSchedulingAdmission
            .admit(value)
            .getOrThrow()

        val jobId = AndroidAgentEventWakeJobIdentity.jobIdFor(value)
        if (!runnable) {
            jobScheduler.cancel(jobId)
            return@runCatching false
        }

        val policy = AndroidAgentEventWakeResourcePolicy.plan(governor.currentBudget())
        val extras = PersistableBundle().also {
            AndroidAgentEventWakeTransport.writeToBundle(it, value)
        }
        val info = JobInfo.Builder(
            jobId,
            ComponentName(appContext, AgentEventWakeJobService::class.java)
        )
            .setPersisted(policy.persistedAcrossReboot)
            .setRequiresBatteryNotLow(policy.requiresBatteryNotLow)
            .setRequiresStorageNotLow(policy.requiresStorageNotLow)
            .setExtras(extras)
            .setMinimumLatency(policy.minimumLatencyMs)
            .build()

        jobScheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
    }

    fun cancel(value: AmperAgentEventWakeHandoff) {
        jobScheduler.cancel(AndroidAgentEventWakeJobIdentity.jobIdFor(value))
    }
}

/**
 * Adapter from a qualified proactive admission/checkpoint to the Android scheduler.
 *
 * It creates only the Phase655 verified handoff. It owns no planner, ToolFabric, AuthorityGate, or
 * execution port.
 */
class AndroidAgentProactiveEventWakeAdapter(
    context: Context,
    private val eventWake: AmperAgentProactiveEventWakeCoordinator
) {
    private val scheduler = AndroidAgentEventWakeScheduler(context)

    fun schedule(
        admission: AmperAgentTaskAdmission,
        checkpoint: AmperAgentPlanTaskCheckpoint
    ): Result<Boolean> =
        eventWake
            .handoff(admission, checkpoint)
            .mapCatching { scheduler.handoff(it).getOrThrow() }
}

class AgentEventWakeJobService : JobService() {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "amper-agent-event-wake").apply { isDaemon = true }
    }
    private val active = ConcurrentHashMap<Int, Future<*>>()

    override fun onStartJob(params: JobParameters): Boolean {
        val handoff = AndroidAgentEventWakeTransport
            .readFromBundle(params.extras)
            .getOrElse { return false }

        val future = worker.submit {
            val governor = AndroidResourceGovernor(applicationContext)
            val budget = runCatching { governor.currentBudget() }.getOrNull()
            if (
                budget == null ||
                !governor.allows(agentCount = 1) ||
                !AndroidAgentEventWakeResourcePolicy.shouldExecuteNow(budget)
            ) {
                Handler(Looper.getMainLooper()).post {
                    active.remove(params.jobId)
                    // No Agent execution occurred. Finish this OS wake, then reinstall the same
                    // verified checkpoint under a resource-derived bounded delay.
                    jobFinished(params, false)
                    Handler(Looper.getMainLooper()).post {
                        AndroidAgentEventWakeScheduler(applicationContext)
                            .handoff(handoff)
                    }
                }
                return@submit
            }

            val outcome = AndroidAgentEventWakeConsumer(applicationContext)
                .consume(handoff)

            Handler(Looper.getMainLooper()).post {
                active.remove(params.jobId)

                outcome.fold(
                    onSuccess = { result ->
                        if (result.state != AmperAgentEventWakeHostExecutionState.RETRY_LATER) {
                            surfaceProactiveAttention(handoff.planId)
                        }
                        when (result.state) {
                            AmperAgentEventWakeHostExecutionState.CHECKPOINTED -> {
                                val next = requireNotNull(result.nextHandoff)
                                // Finish the old OS wake before installing only the fresh Phase656
                                // checkpoint under the same stable dedupe/job identity.
                                jobFinished(params, false)
                                Handler(Looper.getMainLooper()).post {
                                    AndroidAgentEventWakeScheduler(applicationContext)
                                        .handoff(next)
                                }
                            }

                            AmperAgentEventWakeHostExecutionState.WAITING_APPROVAL,
                            AmperAgentEventWakeHostExecutionState.TERMINAL_NOOP ->
                                jobFinished(params, false)

                            AmperAgentEventWakeHostExecutionState.RETRY_LATER -> {
                                jobFinished(params, false)
                                Handler(Looper.getMainLooper()).post {
                                    AndroidAgentEventWakeScheduler(applicationContext)
                                        .handoff(handoff)
                                }
                            }
                        }
                    },
                    onFailure = {
                        // Tampered/stale wakes fail closed. They must not become an infinite
                        // JobScheduler retry loop.
                        jobFinished(params, false)
                    }
                )
            }
        }
        active.put(params.jobId, future)?.cancel(true)
        return true
    }

    private fun surfaceProactiveAttention(planId: String) {
        runCatching {
            AndroidCanonicalSovereignRuntimeBootstrap
                .acquire(applicationContext)
                .agent
                .agentProactiveAttention
                .syncPlan(
                    PlanId(planId),
                    AndroidAgentProactiveAttentionSurfaceMode.BACKGROUND_TRANSITION
                )
                .getOrThrow()
        }
        // Attention is discoverability only. Notification permission/settings failures or UI
        // delivery failures must never alter the canonical EVENT_WAKE execution outcome.
    }

    override fun onStopJob(params: JobParameters): Boolean {
        active.remove(params.jobId)?.cancel(true)
        // If interruption happened before persistence, one retry is useful. If the step already
        // committed, Phase655 digest verification rejects the stale checkpoint on the retry.
        return true
    }

    override fun onDestroy() {
        active.values.forEach { it.cancel(true) }
        active.clear()
        worker.shutdownNow()
        super.onDestroy()
    }
}
