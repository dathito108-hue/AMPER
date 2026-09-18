# Titan Cortex Phase 28 — Atomic Recovery Ledger

Phase 28 hardens the persistent claim/receipt/reconciliation ledger against concurrent approvals and conflicting recovery writes inside one AMPER runtime.

## Invariants

1. `claimSideEffect()` is an atomic ledger mutation. Two concurrent approvals for the same plan/request cannot both create the pre-execution claim.
2. `recordTerminal()` is atomic. Conflicting terminal outcomes for the same request cannot both become valid immutable receipts.
3. `reconcileClaim()` is atomic. Conflicting manual recovery decisions cannot both commit.
4. Read APIs remain unchanged; synchronization is limited to mutation boundaries.
5. Assistant and planner approval paths both benefit because they share the same `SovereignPlanReceiptLedger` instance owned by the Plan OS store.
6. The existing order remains unchanged: persistent snapshot -> side-effect claim -> AuthorityGate/provider -> terminal receipt.
7. A losing concurrent claim attempt fails before provider execution and therefore cannot duplicate a side effect.
8. Immutable claim, receipt and reconciliation formats remain unchanged; Phase 28 changes concurrency semantics, not persisted schema.
9. Recovery Console remains the only resolution path for an interrupted claimed side effect; no retry/replay path is added.
10. This phase does not add recursive tool loops, background continuation, Run All, or automatic side-effect approval.

## Scope

The synchronization guarantee is process/runtime-local to one `MemoryBackedSovereignPlanReceiptLedger` instance. Cross-process or multi-device coordination would require a storage-level compare-and-set/transaction primitive and is intentionally outside this phase.
