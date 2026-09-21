# Phase668 — M5 Proactive Snapshot Coherence

Phase668 hardens the Phase665/667 proactive history read model against concurrent EVENT_WAKE,
foreground approval/rejection, recovery, and receipt transitions.

It does not add a lock manager, task database, snapshot database, scheduler, or execution path.

## Problem

Before Phase668, one history read could observe:

1. lifecycle state from canonical plan revision A;
2. a subsequently loaded plan revision B;
3. receipt evidence that changed again while the UI projection was being built.

Each source was canonical, but the combined UI row could be internally mixed.

## Bounded optimistic coherence

`AmperAgentProactiveTaskHistoryProjection.recent()` now uses at most three attempts.

For each attempt:

1. capture the bounded lifecycle window;
2. load each exact canonical plan;
3. derive lifecycle state from that exact already-loaded plan object;
4. require that derived view to equal the lifecycle view captured at the beginning;
5. read canonical receipt evidence twice for the same loaded plan and require stable equality;
6. capture the lifecycle window again;
7. return only if the before/after lifecycle windows are equal.

A mismatch retries from canonical storage.

Continuous churn after three attempts returns a visible failure rather than presenting a mixed
lifecycle/receipt snapshot.

## No second plan load for validation

Phase668 adds internal
`AmperAgentProactiveTaskLifecycleCoordinator.inspectLoadedPlan(plan)`.

This helper:

- reuses immutable lifecycle provenance;
- derives task state from the exact supplied canonical `SovereignPlan` object;
- validates PlanId binding;
- performs no plan write;
- performs no scheduler operation;
- registers no admission;
- executes no step.

It exists only to verify that the plan used for receipt projection is the same lifecycle revision the
history row claims to represent.

## Receipt evidence coherence

`SovereignPlanExecutionInspector` remains the only receipt presentation inspector.

Phase668 runs it twice against the same loaded plan during a projection attempt. Claim, receipt, or
reconciliation changes between the two reads invalidate the attempt.

No receipt is copied, rewritten, or cached.

## Concurrency semantics

The design intentionally does not hold a lock across:

- persistent plan reads;
- receipt ledger reads;
- EVENT_WAKE scheduling;
- plan execution;
- approval/rejection;
- recovery reconciliation.

Writers therefore retain the existing canonical execution behavior. The UI reader either obtains a
stable point-in-time projection or fails visible after bounded retries.

## Architecture locks

Phase668 adds:

- `proactive-history-snapshot-coherence-uses-bounded-optimistic-reread`
- `proactive-history-loaded-plan-view-avoids-second-plan-load`
- `proactive-history-lifecycle-before-after-must-match`
- `proactive-history-receipt-evidence-must-repeat-stably`
- `proactive-history-churn-fails-visible-after-bounded-retries`
- `proactive-history-coherence-adds-no-lock-scheduler-or-snapshot-store`

M5 exit criteria now require bounded optimistic coherence for both plan lifecycle state and receipt
evidence, with no new persistence or execution coordination layer.

## Tests

Phase668 tests cover:

- a canonical plan transition occurring between lifecycle capture and plan load retries and returns
  one coherent stable state;
- continuously alternating plan revisions fail visible after bounded retries;
- continuously changing receipt evidence fails visible after bounded retries;
- existing stable receipt projection remains read-only and unchanged.

## Architecture audit

Base:

`main@c2fa8a1c138ed751ff27c05db6342fa02ef3e27d`

Phase668 changes only lifecycle read validation, proactive history projection logic, tests,
architecture locks, and this document.

It does not modify:

- `app/src/amneNative/**`;
- `app/build.gradle.kts`;
- AMI2/AMNE2;
- Titan;
- ToolFabric or authority policy;
- plan persistence format;
- receipt persistence format;
- trigger/FIFO persistence;
- Phase655/656/657 scheduling/execution.

The canonical product architecture remains:

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

Use one pull-request-triggered Android CI run.

Required merge gate:

- `verify` PASS;
- unit tests PASS;
- `Build canonical debug APK` PASS;
- `amper-core-arm64` SKIP because no native/build-impact file is changed.

Merge only when the verified CI head equals the PR head.

## Next M5 slice

Phase669 should harden proactive foreground visibility around background terminal transitions and
stale UI refresh timing, reusing the Phase663 attention/Phase667 refresh mechanisms without adding a
second observer or polling loop.
