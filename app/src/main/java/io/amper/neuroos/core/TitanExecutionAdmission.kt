package io.amper.neuroos.core

/**
 * Ephemeral process-local capacity visible to one planning pass.
 *
 * [budget] subtracts slots and known memory already reserved by active executions. Once any known
 * reservation exists, new routes must expose a positive memory estimate so the planner cannot pick
 * an unknown-cost route that the final execution lease would necessarily reject. Backends whose
 * declared execution concurrency is already saturated, or whose maintenance lease is currently
 * held, are exposed separately so route planning can skip them before touching backend-local state.
 */
internal data class TitanExecutionPlanningAvailability(
    val budget: ResourceBudget,
    val requireKnownMemoryEstimate: Boolean,
    val saturatedBackendIds: Set<String> = emptySet(),
    val saturatedExecutionGroupIds: Set<String> = emptySet()
)

internal data class TitanBackendExecutionAdmission(
    val backendId: String,
    val maxConcurrentExecutions: Int,
    val executionGroupId: String = backendId
) {
    init {
        require(backendId.isNotBlank()) { "backend execution admission requires a backend id" }
        require(executionGroupId.isNotBlank()) {
            "backend execution admission requires an execution group id"
        }
        require(maxConcurrentExecutions > 0) { "backend execution concurrency must be positive" }
    }
}

private data class TitanActiveExecutionReservation(
    val memoryMb: Int?,
    val backendAdmission: TitanBackendExecutionAdmission?
)

/**
 * Process-local governed execution admission for model preparation and inference.
 *
 * Route planning proves that one candidate fits the current [ResourceBudget]. This gate closes the
 * concurrency gap by proving that all executions admitted at the same time still fit the slot and
 * known-memory budget together. Unknown-memory routes are admitted only when they can run alone.
 * Backends may additionally declare an execution concurrency limit; that limit is reserved in the
 * same atomic lease as global resources, so a single-session backend cannot accumulate hidden work
 * behind its own internal lock. Phase 109 extends the same domain to backend maintenance: resource
 * reconciliation never overlaps execution for that backend, never waits for it, and temporarily
 * excludes the backend from planning while maintenance is active. The gate never waits.
 */
internal class TitanExecutionAdmissionGate {
    private var nextLeaseId: Long = 1L
    private val activeReservations = linkedMapOf<Long, TitanActiveExecutionReservation>()
    private val activeBackendMaintenance = linkedMapOf<String, String>()

    /**
     * Snapshot the capacity still available for route planning without reserving it.
     * Final [acquire] remains mandatory because another thread may win capacity after this snapshot.
     */
    @Synchronized
    fun planningAvailability(
        baseBudget: ResourceBudget
    ): Result<TitanExecutionPlanningAvailability> = runCatching {
        validateBudget(baseBudget)
        require(activeReservations.size < baseBudget.maxConcurrentAgents) {
            "concurrent execution limit reached before route planning"
        }
        require(activeReservations.values.none { it.memoryMb == null }) {
            "active unknown-memory execution requires exclusive admission"
        }

        val reservedMemoryMb = reservedKnownMemoryMb()
        require(reservedMemoryMb <= baseBudget.memoryMb.toLong()) {
            "active execution reservations exceed current memory budget"
        }
        val remainingMemoryMb = (baseBudget.memoryMb.toLong() - reservedMemoryMb)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val remainingSlots = baseBudget.maxConcurrentAgents - activeReservations.size

        TitanExecutionPlanningAvailability(
            budget = baseBudget.copy(
                maxConcurrentAgents = remainingSlots,
                memoryMb = remainingMemoryMb
            ),
            requireKnownMemoryEstimate = activeReservations.isNotEmpty(),
            saturatedBackendIds = saturatedBackendIds() + activeBackendMaintenance.keys,
            saturatedExecutionGroupIds =
                saturatedExecutionGroupIds() + activeBackendMaintenance.values
        )
    }

    @Synchronized
    fun acquire(
        budget: ResourceBudget,
        estimatedMemoryMb: Int?,
        backendAdmission: TitanBackendExecutionAdmission? = null
    ): Result<TitanExecutionAdmissionLease> = runCatching {
        validateBudget(budget)

        val memory = estimatedMemoryMb?.takeIf { it > 0 }
        require(activeReservations.size < budget.maxConcurrentAgents) {
            "concurrent execution limit reached"
        }

        if (memory == null) {
            require(activeReservations.isEmpty()) {
                "unknown-memory execution requires exclusive admission"
            }
        } else {
            require(activeReservations.values.none { it.memoryMb == null }) {
                "known-memory execution cannot overlap an unknown-memory execution"
            }
            val reservedMemoryMb = reservedKnownMemoryMb()
            require(reservedMemoryMb + memory.toLong() <= budget.memoryMb.toLong()) {
                "concurrent execution memory budget exceeded"
            }
        }

        backendAdmission?.let { requested ->
            require(requested.backendId !in activeBackendMaintenance) {
                "backend maintenance active: ${requested.backendId}"
            }
            require(requested.executionGroupId !in activeBackendMaintenance.values) {
                "execution-group maintenance active: ${requested.executionGroupId}"
            }
            val sameGroup = activeReservations.values
                .mapNotNull { it.backendAdmission }
                .filter { it.executionGroupId == requested.executionGroupId }
            require(
                sameGroup.all {
                    it.maxConcurrentExecutions == requested.maxConcurrentExecutions
                }
            ) {
                "execution-group concurrency contract changed while active: " +
                    requested.executionGroupId
            }
            require(sameGroup.size < requested.maxConcurrentExecutions) {
                "execution-group concurrency limit reached: ${requested.executionGroupId}"
            }
        }

        val leaseId = nextLeaseId
        nextLeaseId = if (nextLeaseId == Long.MAX_VALUE) 1L else nextLeaseId + 1L
        require(leaseId !in activeReservations) {
            "execution lease id space exhausted"
        }
        activeReservations[leaseId] = TitanActiveExecutionReservation(
            memoryMb = memory,
            backendAdmission = backendAdmission
        )
        TitanExecutionAdmissionLease { release(leaseId) }
    }

    /**
     * Try to reserve exclusive backend maintenance without waiting.
     *
     * Returns null when the backend is executing or another maintenance pass already owns it.
     * Callers should defer that backend and continue; execution/maintenance races are closed because
     * final execution acquire checks this same reservation set atomically.
     */
    @Synchronized
    fun tryAcquireBackendMaintenance(
        backendId: String,
        executionGroupId: String = backendId
    ): TitanBackendMaintenanceLease? {
        require(backendId.isNotBlank()) { "backend maintenance admission requires a backend id" }
        require(executionGroupId.isNotBlank()) {
            "backend maintenance admission requires an execution group id"
        }
        if (backendId in activeBackendMaintenance) return null
        if (executionGroupId in activeBackendMaintenance.values) return null
        if (
            activeReservations.values
                .mapNotNull { it.backendAdmission }
                .any { it.executionGroupId == executionGroupId }
        ) {
            return null
        }
        activeBackendMaintenance[backendId] = executionGroupId
        return TitanBackendMaintenanceLease { releaseBackendMaintenance(backendId) }
    }

    private fun validateBudget(budget: ResourceBudget) {
        require(budget.maxConcurrentAgents > 0) {
            "execution budget permits no concurrent work"
        }
        require(budget.memoryMb >= 0) {
            "execution budget has negative memory"
        }
    }

    private fun reservedKnownMemoryMb(): Long = activeReservations.values
        .mapNotNull { it.memoryMb }
        .fold(0L) { total, value -> total + value.toLong() }

    private fun saturatedBackendIds(): Set<String> = activeReservations.values
        .mapNotNull { it.backendAdmission }
        .groupBy { it.backendId }
        .mapNotNullTo(linkedSetOf()) { (backendId, reservations) ->
            val limit = reservations.first().maxConcurrentExecutions
            check(reservations.all { it.maxConcurrentExecutions == limit }) {
                "backend execution concurrency contract changed while active: $backendId"
            }
            backendId.takeIf { reservations.size >= limit }
        }

    private fun saturatedExecutionGroupIds(): Set<String> = activeReservations.values
        .mapNotNull { it.backendAdmission }
        .groupBy { it.executionGroupId }
        .mapNotNullTo(linkedSetOf()) { (groupId, reservations) ->
            val limit = reservations.first().maxConcurrentExecutions
            check(reservations.all { it.maxConcurrentExecutions == limit }) {
                "execution-group concurrency contract changed while active: $groupId"
            }
            groupId.takeIf { reservations.size >= limit }
        }

    @Synchronized
    private fun release(leaseId: Long) {
        check(activeReservations.containsKey(leaseId)) {
            "failed to release execution admission lease"
        }
        activeReservations.remove(leaseId)
    }

    @Synchronized
    private fun releaseBackendMaintenance(backendId: String) {
        check(activeBackendMaintenance.remove(backendId) != null) {
            "failed to release backend maintenance lease: $backendId"
        }
    }
}

internal class TitanExecutionAdmissionLease(
    private val releaseAction: () -> Unit
) : AutoCloseable {
    private var closed: Boolean = false

    override fun close() {
        val shouldRelease = synchronized(this) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (shouldRelease) releaseAction()
    }
}

internal class TitanBackendMaintenanceLease(
    private val releaseAction: () -> Unit
) : AutoCloseable {
    private var closed: Boolean = false

    override fun close() {
        val shouldRelease = synchronized(this) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (shouldRelease) releaseAction()
    }
}
