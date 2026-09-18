package io.amper.neuroos.core

/**
 * Canonical view of recovery debt. A durable pre-execution claim is unresolved only
 * while it has neither a terminal execution receipt nor a manual reconciliation.
 * Claims remain immutable evidence after resolution and therefore must not by
 * themselves keep AMPER in recovery mode forever.
 */
class SovereignRecoveryState(
    private val ledger: SovereignPlanReceiptLedger?
) {
    fun unresolvedClaims(limit: Int = 32): List<PlanSideEffectClaim> {
        require(limit in 0..64) { "recovery claim limit must be between 0 and 64" }
        if (limit == 0 || ledger == null) return emptyList()
        return ledger.pendingClaims((limit * 2).coerceAtMost(128))
            .asSequence()
            .filter { claim -> ledger.receipt(claim.planId, claim.requestId) == null }
            .filter { claim -> ledger.reconciliation(claim.planId, claim.requestId) == null }
            .take(limit)
            .toList()
    }

    fun hasUnresolvedClaims(): Boolean = unresolvedClaims(1).isNotEmpty()
}
