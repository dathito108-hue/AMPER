package io.amper.neuroos.core

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

enum class ReflexLearningMaintenanceReason {
    INITIAL_EVIDENCE,
    FRESH_EVIDENCE,
    HARD_EVIDENCE,
    LOW_DRIFT_ACCUMULATION,
    RESOURCE_DEFERRED
}

data class ReflexLearningMaintenanceTicket(
    val evidenceDigest: String,
    val reasons: Set<ReflexLearningMaintenanceReason>,
    val priority: Double,
    val firstQueuedAtEpochMs: Long,
    val lastQueuedAtEpochMs: Long,
    val notBeforeEpochMs: Long,
    val attempts: Int
) {
    init {
        require(evidenceDigest.matches(Regex("[0-9a-f]{64}")))
        require(reasons.isNotEmpty())
        require(priority in 0.0..1.0)
        require(firstQueuedAtEpochMs >= 0L)
        require(lastQueuedAtEpochMs >= firstQueuedAtEpochMs)
        require(notBeforeEpochMs >= 0L)
        require(attempts >= 0)
    }

    val authorityBearing: Boolean
        get() = false
}

fun interface ReflexLearningMaintenanceScheduler {
    fun reconcile(ticket: ReflexLearningMaintenanceTicket?): Boolean
}

object NoopReflexLearningMaintenanceScheduler : ReflexLearningMaintenanceScheduler {
    override fun reconcile(ticket: ReflexLearningMaintenanceTicket?): Boolean = false
}

/**
 * Process-wide gate shared by UI-triggered and OS-triggered Reflex maintenance.
 *
 * Android services and activities live in the same default process. The gate prevents two separately
 * constructed lifecycle instances from entering training/queue mutation concurrently during a
 * component handoff, while remaining platform-neutral for JVM/reference tests.
 */
object ReflexMaintenanceExecutionGate {
    private val lock = Any()

    fun <T> exclusive(block: () -> T): T = synchronized(lock) { block() }
}

interface ReflexLearningMaintenanceQueue {
    fun pending(): ReflexLearningMaintenanceTicket?

    fun enqueue(
        evidenceDigest: String,
        reason: ReflexLearningMaintenanceReason,
        priority: Double,
        notBeforeEpochMs: Long,
        nowEpochMs: Long
    ): ReflexLearningMaintenanceTicket

    fun recordAttempt(
        expectedEvidenceDigest: String,
        nextNotBeforeEpochMs: Long,
        nowEpochMs: Long
    ): ReflexLearningMaintenanceTicket?

    fun clear(expectedEvidenceDigest: String? = null): Boolean
}

class MemoryBackedReflexLearningMaintenanceQueue(
    private val memory: MemoryOs
) : ReflexLearningMaintenanceQueue {
    @Synchronized
    override fun pending(): ReflexLearningMaintenanceTicket? =
        memory.get(TICKET_ID)
            ?.takeIf { it.kind == TICKET_KIND }
            ?.let { decode(it.content) }

    @Synchronized
    override fun enqueue(
        evidenceDigest: String,
        reason: ReflexLearningMaintenanceReason,
        priority: Double,
        notBeforeEpochMs: Long,
        nowEpochMs: Long
    ): ReflexLearningMaintenanceTicket {
        require(evidenceDigest.matches(Regex("[0-9a-f]{64}")))
        require(priority in 0.0..1.0)
        require(nowEpochMs >= 0L)
        require(notBeforeEpochMs >= 0L)
        val existing = pending()
        val sameEvidence = existing?.evidenceDigest == evidenceDigest
        val next = ReflexLearningMaintenanceTicket(
            evidenceDigest = evidenceDigest,
            reasons = buildSet {
                if (sameEvidence) addAll(requireNotNull(existing).reasons)
                add(reason)
            },
            priority = maxOf(if (sameEvidence) requireNotNull(existing).priority else 0.0, priority),
            firstQueuedAtEpochMs =
                if (sameEvidence) requireNotNull(existing).firstQueuedAtEpochMs else nowEpochMs,
            lastQueuedAtEpochMs = nowEpochMs,
            notBeforeEpochMs = if (sameEvidence) {
                max(requireNotNull(existing).notBeforeEpochMs, notBeforeEpochMs)
            } else {
                notBeforeEpochMs
            },
            attempts = if (sameEvidence) requireNotNull(existing).attempts else 0
        )
        persist(next)
        return next
    }

    @Synchronized
    override fun recordAttempt(
        expectedEvidenceDigest: String,
        nextNotBeforeEpochMs: Long,
        nowEpochMs: Long
    ): ReflexLearningMaintenanceTicket? {
        require(expectedEvidenceDigest.matches(Regex("[0-9a-f]{64}")))
        require(nextNotBeforeEpochMs >= 0L)
        require(nowEpochMs >= 0L)
        val existing = pending() ?: return null
        if (existing.evidenceDigest != expectedEvidenceDigest) return existing
        val next = existing.copy(
            attempts = existing.attempts + 1,
            notBeforeEpochMs = nextNotBeforeEpochMs.coerceAtLeast(nowEpochMs)
        )
        persist(next)
        return next
    }

    @Synchronized
    override fun clear(expectedEvidenceDigest: String?): Boolean {
        val existing = pending() ?: return false
        if (
            expectedEvidenceDigest != null &&
            existing.evidenceDigest != expectedEvidenceDigest
        ) {
            return false
        }
        return memory.forget(TICKET_ID)
    }

    private fun persist(ticket: ReflexLearningMaintenanceTicket) {
        memory.remember(
            MemoryRecord(
                id = TICKET_ID,
                kind = TICKET_KIND,
                content = encode(ticket),
                importance = 0.91,
                provenance = Provenance(
                    source = "amper-reflex-maintenance",
                    producer = "reflex-learning-maintenance-queue",
                    confidence = 1.0
                ),
                createdAtEpochMs = ticket.lastQueuedAtEpochMs
            )
        )
    }

    private fun encode(ticket: ReflexLearningMaintenanceTicket): String =
        listOf(
            VERSION,
            ticket.evidenceDigest,
            ticket.reasons.map { it.name }.sorted().joinToString(","),
            ticket.priority.toString(),
            ticket.firstQueuedAtEpochMs.toString(),
            ticket.lastQueuedAtEpochMs.toString(),
            ticket.notBeforeEpochMs.toString(),
            ticket.attempts.toString()
        ).joinToString("|")

    private fun decode(content: String): ReflexLearningMaintenanceTicket {
        val parts = content.split('|')
        require(parts.size == 8 && parts[0] == VERSION) {
            "unsupported Reflex maintenance queue record"
        }
        return ReflexLearningMaintenanceTicket(
            evidenceDigest = parts[1],
            reasons = parts[2]
                .split(',')
                .filter { it.isNotBlank() }
                .map(ReflexLearningMaintenanceReason::valueOf)
                .toSet(),
            priority = parts[3].toDouble(),
            firstQueuedAtEpochMs = parts[4].toLong(),
            lastQueuedAtEpochMs = parts[5].toLong(),
            notBeforeEpochMs = parts[6].toLong(),
            attempts = parts[7].toInt()
        )
    }

    companion object {
        private val TICKET_ID = MemoryId("reflex-learning-maintenance:pending:v1")
        private const val TICKET_KIND = "reflex-learning-maintenance-ticket-v1"
        private const val VERSION = "RLMQ1"
    }
}

enum class ReflexBackgroundMaintenanceStage {
    IDLE,
    WAITING,
    ATTEMPTED,
    COMPLETED,
    FAILED
}

data class ReflexBackgroundMaintenanceResult(
    val stage: ReflexBackgroundMaintenanceStage,
    val lifecycleStage: ReflexNativeLifecycleStage? = null,
    val attempts: Int = 0,
    val detail: String
) {
    init {
        require(attempts >= 0)
        require(detail.isNotBlank())
    }
}

/**
 * Durable queue consumer for process-resident background Reflex maintenance.
 *
 * A charging device can bypass queue delay. Otherwise a ticket is attempted only after a quiet
 * period with no newly coalesced learning evidence. An attempt is durably recorded before entering
 * model training so process death cannot create a hot retry loop; checkpoint identities remain
 * content-bound and make any replayed successful work idempotent.
 */
class ReflexBackgroundMaintenanceCoordinator(
    private val lifecycle: ReflexNativeModelLifecycle,
    private val queue: ReflexLearningMaintenanceQueue,
    private val deviceStatusSource: DeviceStatusSource? = null,
    private val telemetry: ReflexLearningSchedulerTelemetry =
        NoopReflexLearningSchedulerTelemetry,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun pendingTicket(): ReflexLearningMaintenanceTicket? = queue.pending()

    fun tick(force: Boolean = false): Result<ReflexBackgroundMaintenanceResult> {
        var attemptedTicket: ReflexLearningMaintenanceTicket? = null
        var attemptedAtEpochMs: Long? = null
        return runCatching {
        val ticket = queue.pending() ?: return@runCatching ReflexBackgroundMaintenanceResult(
            stage = ReflexBackgroundMaintenanceStage.IDLE,
            detail = "no durable Reflex maintenance ticket"
        )
        val now = clock().coerceAtLeast(0L)
        val device = runCatching { deviceStatusSource?.snapshot() }.getOrNull()
        val charging = device?.charging == true
        val quiet = now >= ticket.lastQueuedAtEpochMs &&
            now - ticket.lastQueuedAtEpochMs >= QUIET_GRACE_MS

        if (!force && !charging && (!quiet || now < ticket.notBeforeEpochMs)) {
            return@runCatching ReflexBackgroundMaintenanceResult(
                stage = ReflexBackgroundMaintenanceStage.WAITING,
                attempts = ticket.attempts,
                detail = "maintenance queued until quiet/resource-favorable window"
            )
        }

        attemptedTicket = ticket
        attemptedAtEpochMs = now
        val retryDelay = retryDelayMs(ticket.attempts)
        queue.recordAttempt(
            expectedEvidenceDigest = ticket.evidenceDigest,
            nextNotBeforeEpochMs = now + retryDelay,
            nowEpochMs = now
        )
        val report = lifecycle.maintain().getOrThrow()
        val completed = when (report.stage) {
            ReflexNativeLifecycleStage.ACTIVE,
            ReflexNativeLifecycleStage.REPLACED -> {
                queue.clear()
                true
            }

            ReflexNativeLifecycleStage.CHALLENGER_REJECTED,
            ReflexNativeLifecycleStage.EVALUATION_REJECTED,
            ReflexNativeLifecycleStage.TRAINING_FAILED,
            ReflexNativeLifecycleStage.DISABLED -> {
                queue.clear()
                true
            }

            else -> false
        }
        telemetry.observeMaintenanceAttempt(
            queueWaitMs = (now - ticket.firstQueuedAtEpochMs).coerceAtLeast(0L),
            completed = completed,
            failed = false,
            observedAtEpochMs = now
        )
        ReflexBackgroundMaintenanceResult(
            stage = if (completed) {
                ReflexBackgroundMaintenanceStage.COMPLETED
            } else {
                ReflexBackgroundMaintenanceStage.ATTEMPTED
            },
            lifecycleStage = report.stage,
            attempts = queue.pending()?.attempts ?: ticket.attempts + 1,
            detail = report.detail
        )
    }.recover { error ->
        val failedTicket = attemptedTicket
        val failedAt = attemptedAtEpochMs
        if (failedTicket != null && failedAt != null) {
            telemetry.observeMaintenanceAttempt(
                queueWaitMs =
                    (failedAt - failedTicket.firstQueuedAtEpochMs).coerceAtLeast(0L),
                completed = false,
                failed = true,
                observedAtEpochMs = failedAt
            )
        }
        ReflexBackgroundMaintenanceResult(
            stage = ReflexBackgroundMaintenanceStage.FAILED,
            attempts = queue.pending()?.attempts ?: 0,
            detail = "background Reflex maintenance failed: " +
                (error.message ?: error::class.java.simpleName)
        )
    }
    }

    private fun retryDelayMs(attempts: Int): Long {
        val shift = attempts.coerceIn(0, MAX_BACKOFF_SHIFT)
        val tunedBase = (
            BASE_RETRY_MS * telemetry.tuningProfile().retryDelayMultiplier.toLong()
            ).coerceAtMost(MAX_RETRY_MS)
        return (tunedBase shl shift).coerceAtMost(MAX_RETRY_MS)
    }

    companion object {
        const val QUIET_GRACE_MS = 60_000L
        const val BASE_RETRY_MS = 60_000L
        const val MAX_RETRY_MS = 15 * 60_000L
        const val MAX_BACKOFF_SHIFT = 4
    }
}

class ProcessResidentReflexMaintenanceLoop(
    private val coordinator: ReflexBackgroundMaintenanceCoordinator,
    private val initialDelayMs: Long = 10_000L,
    private val pollIntervalMs: Long = 30_000L,
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "amper-reflex-maintenance").apply {
                isDaemon = true
            }
        }
) : AutoCloseable {
    private val started = AtomicBoolean(false)

    init {
        require(initialDelayMs >= 0L)
        require(pollIntervalMs >= 1_000L)
    }

    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) return false
        scheduler.scheduleWithFixedDelay(
            { runCatching { coordinator.tick() } },
            initialDelayMs,
            pollIntervalMs,
            TimeUnit.MILLISECONDS
        )
        return true
    }

    override fun close() {
        scheduler.shutdownNow()
    }
}
