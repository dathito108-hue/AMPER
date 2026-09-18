# Titan Cortex Phase 27 — Typed Approval Binding Persistence

Phase 27 removes security dependence on human-readable `ActionOutcome.detail` when restoring side-effect approval checkpoints.

## Invariants

1. **Side-effect class is first-class governed data.**
   - `ActionOutcome.sideEffect` carries `ToolSideEffect` independently of display text.
   - Governed action evaluation, explicit approvals and bound fabric outcomes populate the typed field whenever a live provider binding exists.

2. **Persistent plan snapshots use `AMPER_PLAN_STATE_V2`.**
   - V2 adds the typed side-effect class to each persisted step outcome.
   - Tool id, action status, output and detail remain persisted as before.
   - The Library record kind remains stable so existing encrypted journals remain discoverable.

3. **V1 plan snapshots remain readable.**
   - `SovereignPlanCodec.decode()` accepts both V1 and V2.
   - V1 outcomes have `sideEffect = null` after decoding.
   - Recovery and claim validation may derive the side-effect class from the canonical V1 detail prefix only when no typed field exists.

4. **V2 uses typed binding as the authority.**
   - `SovereignPlanRecoveryGuard` and the side-effect claim ledger prefer `ActionOutcome.sideEffect`.
   - Human-readable detail may change without invalidating a V2 approval binding.
   - If both typed binding and a parseable legacy detail are present but disagree, AMPER fails closed.

5. **Claim and reconciliation records remain typed.**
   - Durable side-effect claims already store `ToolSideEffect` directly.
   - Manual reconciliation preserves that typed class in the terminal action outcome.

6. **Receipt V1 hashes remain unchanged.**
   - Phase 27 deliberately does not add the new field to the V1 receipt canonical hash.
   - Existing terminal receipts therefore continue to verify across the migration.
   - Approval safety is bound before execution by typed plan state plus typed side-effect claims.

7. **No autonomy expansion.**
   - No automatic retry, replay, recursive tool loop, Run All path or background continuation is added.

## Migration behavior

- New plan snapshot: V2 with typed side-effect field.
- Existing V1 snapshot with canonical approval detail: accepted through legacy fallback.
- V2 typed field with arbitrary non-security display detail: accepted.
- Typed field conflicting with a parseable legacy detail: rejected fail-closed.
