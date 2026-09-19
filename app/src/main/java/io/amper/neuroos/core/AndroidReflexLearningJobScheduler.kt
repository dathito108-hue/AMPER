package io.amper.neuroos.core

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

class AndroidReflexLearningJobScheduler(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis
) : ReflexLearningMaintenanceScheduler {
    private val appContext = context.applicationContext
    private val jobScheduler =
        appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

    override fun reconcile(ticket: ReflexLearningMaintenanceTicket?): Boolean {
        if (reconcileSuppressed.get() == true) return false
        val now = clock().coerceAtLeast(0L)
        val plan = ReflexDeferredJobPlanner.plan(ticket, now)
        if (!plan.scheduled) {
            jobScheduler.cancel(JOB_ID)
            return true
        }

        val info = JobInfo.Builder(
            JOB_ID,
            ComponentName(appContext, ReflexLearningJobService::class.java)
        )
            .setRequiresCharging(plan.requiresCharging)
            .setRequiresDeviceIdle(plan.requiresDeviceIdle)
            .setRequiresBatteryNotLow(plan.requiresBatteryNotLow)
            .setRequiresStorageNotLow(plan.requiresStorageNotLow)
            .setPersisted(plan.persistedAcrossReboot)
            .apply {
                if (plan.minimumLatencyMs > 0L) {
                    setMinimumLatency(plan.minimumLatencyMs)
                }
            }
            .build()

        return jobScheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
    }

    companion object {
        const val JOB_ID = 0x414D5045
        private val reconcileSuppressed = ThreadLocal.withInitial { false }

        fun <T> withoutReconcile(block: () -> T): T {
            val previous = reconcileSuppressed.get()
            reconcileSuppressed.set(true)
            return try {
                block()
            } finally {
                reconcileSuppressed.set(previous)
            }
        }
    }
}

/**
 * Process-local registry lets the OS JobService reuse the Activity's already-open runtime whenever
 * available. If the UI process has no registered coordinator, the service restores the encrypted
 * sovereign runtime from disk and executes the same canonical maintenance lifecycle.
 */
object AndroidReflexMaintenanceProcessRegistry {
    @Volatile
    private var coordinator: ReflexBackgroundMaintenanceCoordinator? = null

    @Synchronized
    fun register(value: ReflexBackgroundMaintenanceCoordinator) {
        coordinator = value
    }

    @Synchronized
    fun unregister(expected: ReflexBackgroundMaintenanceCoordinator) {
        if (coordinator === expected) {
            coordinator = null
        }
    }

    fun current(): ReflexBackgroundMaintenanceCoordinator? = coordinator
}

class ReflexLearningJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "amper-reflex-os-maintenance").apply {
            isDaemon = true
        }
    }

    @Volatile
    private var active: Future<*>? = null

    override fun onStartJob(params: JobParameters): Boolean {
        active = executor.submit {
            val scheduler = AndroidReflexLearningJobScheduler(applicationContext)
            val outcome = runCatching {
                val coordinator = AndroidReflexMaintenanceProcessRegistry.current()
                    ?: restoreCoordinator()
                AndroidReflexLearningJobScheduler.withoutReconcile {
                    coordinator.tick(force = true).getOrThrow()
                }
                coordinator.pendingTicket()
            }
            Handler(Looper.getMainLooper()).post {
                active = null
                if (outcome.isSuccess) {
                    jobFinished(params, false)
                    scheduler.reconcile(outcome.getOrNull())
                } else {
                    jobFinished(params, true)
                }
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
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun restoreCoordinator(): ReflexBackgroundMaintenanceCoordinator =
        ReflexMaintenanceExecutionGate.exclusive {
            val governor = AndroidResourceGovernor(applicationContext)
            val deviceStatusSource = AndroidDeviceStatusSource(applicationContext)
            val runtime = AmperRuntime.persistentEncrypted(
                rootDir = filesDir,
                governor = governor,
                deviceStatusSource = deviceStatusSource
            )
            val sovereignDir = File(filesDir, "amper-sovereign")
            val artifacts = FileReflexLinearArtifactStore(
                File(sovereignDir, "native-reflex")
            )
            val lifecycle = ReflexNativeModelLifecycle(
                runtime = runtime,
                artifacts = artifacts,
                maintenanceScheduler = NoopReflexLearningMaintenanceScheduler
            )
            ReflexBackgroundMaintenanceCoordinator(
                lifecycle = lifecycle,
                queue = runtime.reflexLearningMaintenanceQueue,
                deviceStatusSource = deviceStatusSource
            )
        }
}
