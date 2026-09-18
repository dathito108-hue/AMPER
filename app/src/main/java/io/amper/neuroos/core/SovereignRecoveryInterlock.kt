package io.amper.neuroos.core

/**
 * Global fail-closed gate for explicit side-effect/approval paths.
 *
 * Read-only evaluation and ordinary conversation remain available. Any explicit
 * approval is blocked while unresolved recovery debt exists so AMPER cannot create
 * another ambiguous side effect before the previous crash window is reconciled.
 */
class SovereignRecoveryInterlock(
    private val state: SovereignRecoveryState
) {
    fun requireApprovalAllowed(): Result<Unit> = runCatching {
        val unresolved = state.unresolvedClaims(1)
        require(unresolved.isEmpty()) {
            val request = unresolved.first().requestId.value
            "side-effect approvals are recovery-locked by unresolved request $request; reconcile it first"
        }
    }

    fun locked(): Boolean = state.hasUnresolvedClaims()
}
