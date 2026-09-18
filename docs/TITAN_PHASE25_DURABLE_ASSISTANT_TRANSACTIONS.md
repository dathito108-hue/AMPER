# Titan Cortex Phase 25 — Durable Assistant Side-Effect Transactions

Phase 25 removes the crash-consistency gap between chat approvals and persistent governed plans.

## Invariants

1. **No side-effect provider runs before a durable claim exists.**
   - The assistant stores a one-step transaction snapshot first.
   - The receipt ledger then records an immutable side-effect claim.
   - Only after both writes succeed may execution continue through `SovereignActionLoop -> BoundToolFabric -> AuthorityGate -> ToolProvider`.

2. **Assistant approval is bound to the reviewed provider identity through execution.**
   - `PendingApproval` captures `ToolId` and `ToolSideEffect` at proposal time.
   - Approval re-resolves the live descriptor and fails closed if either binding changed before the transaction begins.
   - `BoundToolFabric.invokeBound()` independently rechecks the same binding immediately before authorization/provider execution and then executes the exact resolved provider object.
   - A capability-compatible replacement provider cannot silently receive an approval intended for another tool, including a registry swap between coordinator precheck and fabric invocation.

3. **A claimed request is never automatically replayed.**
   - If AMPER stops after claim creation but before a terminal receipt, the claim becomes canonical recovery debt.
   - Recovery Console is the only resolution path for that interrupted claim.
   - Reconciliation records the observed result; it never invokes the provider.

4. **Terminal outcomes clear recovery debt without deleting evidence.**
   - Success, denial, failure, malformed, or unavailable outcomes are converted to terminal one-step plan status and receive an immutable receipt.
   - A provider-binding change detected after claim creation terminalizes as `DENIED`; neither the reviewed nor replacement provider is invoked.
   - The original claim remains audit evidence but no longer locks new approvals once the receipt exists.

5. **Assistant transactions do not become user plans.**
   - Internal transactions are marked by `planningBackendId` prefix `assistant-action:`.
   - `PersistentSovereignPlanCoordinator.latest()` excludes these transactions.
   - Recovery Console can still load them by claim/plan id when manual reconciliation is required.

6. **Global Recovery Interlock still applies before new approvals.**
   - Ordinary conversation and automatic read-only tools remain available.
   - New explicit side-effect approvals are blocked while any unresolved recovery debt exists.

## Crash boundaries

- **Before transaction snapshot:** no provider call occurred; no durable side-effect state exists.
- **After snapshot, before claim:** no provider call occurred; the stale snapshot cannot authorize execution by itself.
- **After claim, before provider:** unresolved recovery debt; no automatic replay.
- **After provider, before terminal receipt:** execution state is ambiguous; unresolved recovery debt requires manual reconciliation.
- **After terminal receipt:** transaction is finalized and cannot be executed again with the same request id.

Phase 25 does not add recursive tool loops, background continuation, automatic retry, replay, or a Run All path.
