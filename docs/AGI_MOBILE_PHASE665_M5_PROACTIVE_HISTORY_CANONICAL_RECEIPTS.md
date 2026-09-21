# Phase665 — M5 Proactive History / Canonical Receipt Presentation

Phase665 adds a bounded read-only history surface for proactive tasks and their existing canonical
execution receipts. It does not add a task database, receipt database, history ledger, provider path,
planner, scheduler, approval path, or execution path.

Canonical execution ownership remains unchanged:

```
Phase658 trigger source
  -> Phase659/660 encrypted source record + FIFO
  -> Phase661 deterministic persistent-plan binding
  -> Phase662 immutable lifecycle provenance + canonical plan-derived state
  -> Phase655 verified EVENT_WAKE handoff
  -> Phase657 Android scheduler
  -> Phase656 one-step canonical consumer
```

Phase663/664 provide notification discoverability and durable acknowledgement only. Phase665 reads the
same canonical state for user-facing history.

## Read-only history projection

`AmperAgentProactiveTaskHistoryProjection` reads:

1. `AmperAgentProactiveTaskLifecycleCoordinator.inspect` for the bounded Phase662 lifecycle window;
2. `openPlan` for the exact canonical persistent `SovereignPlan`;
3. `SovereignPlanExecutionInspector` for durability evidence backed by the existing
   `SovereignPlanReceiptLedger`.

The projection persists nothing. Calling `recent()` cannot create or update a task, plan, claim,
receipt, reconciliation, acknowledgement, FIFO entry, or scheduler state.

The Android canonical runtime graph exposes one instance of this projection using the same lifecycle
coordinator and `runtime.plans.receipts`.

## Bounded receipt presentation

Each projected step contains only:

- step index;
- capability id;
- canonical plan step status;
- action status;
- durability evidence classification;
- receipt SHA-256 when one exists;
- reconciliation decision when one exists.

The Phase665 receipt view intentionally does not contain reason text, tool input, tool output, or goal
text. Existing goal/lifecycle context remains on the Phase662 lifecycle view rather than being copied
into a new receipt record.

UI receipt digests are abbreviated for presentation only. The canonical full receipt remains solely
in the existing receipt ledger.

## Canonical evidence semantics

The existing `SovereignPlanExecutionInspector` remains the evidence authority:

- `NONE` — no durable claim/receipt evidence;
- `CLAIMED_UNRESOLVED` — durable side-effect claim requires Recovery Console handling;
- `RECEIPTED` — canonical terminal receipt exists;
- `RECONCILED` — existing manual reconciliation exists and takes presentation precedence;
- `LEDGER_UNAVAILABLE` — receipt evidence is not inferred.

Phase665 does not invent provider identity or receipt state.

A missing canonical plan remains visible from the immutable lifecycle provenance, but its receipt list
is empty. No synthetic receipt is manufactured.

## Recovery presentation

The proactive lifecycle panel now shows:

- terminal receipt/reconciliation count;
- unresolved durable-claim count;
- per-step durability classification;
- abbreviated canonical receipt digest;
- reconciliation decision.

This is display-only. Recovery actions still belong to the existing Recovery Console and governed plan
surfaces. History presentation never invokes or replays a provider.

## Bounds

The projection accepts at most 64 lifecycle entries, matching the Phase662 lifecycle capacity. The
normal UI requests 8 entries.

There is no separate history retention policy because no history is persisted by Phase665.

## Architecture locks

Phase665 adds:

- `proactive-history-is-read-only-projection-not-task-database`
- `proactive-history-reuses-canonical-plan-receipt-ledger`
- `proactive-history-reuses-existing-execution-inspector`
- `proactive-history-receipt-view-excludes-reason-input-output`
- `proactive-history-is-bounded-by-lifecycle-window`
- `proactive-history-missing-plan-never-synthesizes-receipts`
- `proactive-history-recovery-state-comes-from-canonical-receipt-evidence`

M5 exit criteria now require read-only projection semantics, zero receipt writes during history reads,
bounded receipt metadata, no sensitive reason/input/output copy, no synthetic receipts, and reuse of
existing recovery evidence.

## Tests

Phase665 tests cover:

- terminal canonical receipt projection;
- receipt-memory record count unchanged by history reads;
- unresolved durable claim drives recovery presentation;
- missing canonical plan remains visible with no synthetic receipts;
- 64-entry upper bound;
- receipt projection class exposes no reason/input/output/goal fields;
- Omega architecture locks and M5 exit criteria.

## Architecture audit

Base:

`main@2935243d1676ea65fa20174776f57a64dbeb1d10`

Phase665 changes only a read-only M5 projection, its UI wiring, tests, architecture locks, and this
document.

It does not modify:

- `app/src/amneNative/**`;
- `app/build.gradle.kts`;
- AMI2/AMNE2;
- Titan;
- ToolFabric or `DenyByDefaultAuthorityGate`;
- sovereign plan persistence;
- canonical receipt persistence;
- trigger/FIFO persistence;
- Phase655/656/657 execution.

The product architecture remains:

```
one AMPER foundation
  -> AMI2 / AMNE2
  -> Titan
  -> audited ToolFabric
  -> DenyByDefaultAuthorityGate
  -> SovereignPlanCoordinator
  -> PersistentSovereignPlanCoordinator
```

GGUF remains import-source weights only.

## CI gate

Use exactly one pull-request-triggered Android CI run.

Required merge gate:

- `verify` PASS;
- unit tests PASS;
- `Build canonical debug APK` PASS;
- `amper-core-arm64` SKIP because there is no native/build-impact change.

Merge only when the verified CI head equals the PR head.

## Next M5 slice

Phase666 should harden proactive history integrity/navigation around recovery-required tasks while
continuing to reuse the existing Recovery Console and canonical receipt ledger instead of introducing
new recovery authority.
