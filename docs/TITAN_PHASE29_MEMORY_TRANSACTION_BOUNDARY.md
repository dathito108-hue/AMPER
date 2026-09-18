# Titan Cortex Phase 29 — Memory OS Atomic Transaction Boundary

Phase 29 moves recovery-ledger atomicity below a single ledger instance and into the shared `MemoryOs` boundary.

## Invariants

1. **Insert-if-absent is atomic inside one Memory OS instance.**
   - `MemoryOs.rememberIfAbsent()` provides a process-local/object-local CAS-style insert.
   - `PersistentMemoryOs` performs the existence check, journal append, in-memory index update and trimming under its existing lock.

2. **Compound ledger mutations use one Memory OS transaction.**
   - `claimSideEffect()` executes validation + immutable claim insert inside `MemoryOs.transaction()`.
   - `recordTerminal()` validates reconciliation compatibility and creates/compares the immutable receipt inside the same transaction.
   - `reconcileClaim()` verifies the claim, verifies no terminal receipt exists, and creates the immutable reconciliation inside the same transaction.

3. **Separate ledger instances sharing one Memory OS cannot bypass each other.**
   - Ledger-level `@Synchronized` remains defense in depth.
   - Correctness no longer depends on callers reusing one `MemoryBackedSovereignPlanReceiptLedger` object.

4. **Reconciliation and terminal receipt cannot commit contradictory truth.**
   - If a terminal receipt wins first, a new reconciliation fails closed.
   - If reconciliation wins first, only the terminal status matching that reconciliation decision may be recorded.
   - `CONFIRMED_EXECUTED` permits only EXECUTED/EXECUTED.
   - `CONFIRMED_NOT_EXECUTED` permits only FAILED/FAILED.

5. **Persistence and encryption formats do not change.**
   - Claim, receipt and reconciliation record formats remain V1.
   - Android encrypted journal behavior remains unchanged.

## Scope of the guarantee

This phase provides atomicity for threads and components sharing the same `MemoryOs` object within one process. It does **not** claim multi-process safety, cross-process file locking, distributed transactions, or filesystem-level compare-and-swap. A future phase may add an inter-process journal lock if AMPER introduces multiple Android processes that can mutate sovereign memory concurrently.

No automatic retry/replay, recursive tool loop, Run All path, background continuation, or automatic side-effect approval is introduced.
