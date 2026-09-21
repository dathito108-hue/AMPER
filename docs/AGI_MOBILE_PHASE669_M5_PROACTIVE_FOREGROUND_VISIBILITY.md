# Phase669 — M5 Proactive Foreground Visibility / Stale Refresh Timing

Phase669 closes the foreground visibility gap that remained after Phase663 attention,
Phase667 read-model invalidation, and Phase668 snapshot coherence.

A proactive EVENT_WAKE may finish or change state while the Activity is paused or backgrounded.
When the user returns to AMPER, the lifecycle/history UI must re-read canonical state immediately and
stale terminal notifications must not remain as if the app were still backgrounded.

Phase669 does this without a polling loop, worker, service, task store, or second scheduler.

## One Activity lifecycle observer

MainActivity reuses its existing `DisposableEffect(Unit)`.

Phase669 registers one `LifecycleEventObserver` on the existing ComponentActivity lifecycle and
handles only `Lifecycle.Event.ON_RESUME`.

The observer is removed from the same `DisposableEffect` during disposal.

There is no timer, interval, polling API, BroadcastReceiver, foreground service, JobService, or new
process registry for foreground visibility.

## Existing reconciliation ports only

`AndroidAgentProactiveForegroundVisibilityCoordinator` is a thin ordering adapter over:

1. `AndroidAgentProactiveTaskLifecycleController.reconcileTracked()` from Phase662;
2. `AndroidAgentProactiveAttentionController.reconcileTracked(FOREGROUND_RECONCILE)` from
   Phase663.

It owns no Android lifecycle observer itself and has no Context.

The coordinator contains no planner, scheduler, ToolFabric, AuthorityGate, task persistence,
notification policy, or execution port.

## Fail-closed ordering

Foreground reconciliation order is deliberate:

1. canonical lifecycle reconciliation must succeed;
2. only then is attention reconciliation attempted;
3. a successful lifecycle result invalidates the Phase667 proactive read model;
4. notification permission status is re-read.

If lifecycle reconciliation fails, the coordinator returns failure and does not cancel or rewrite
attention based on unverified lifecycle state.

If attention reconciliation fails, the lifecycle result still succeeds and the read model still
refreshes. Discoverability failure therefore cannot gate canonical state visibility.

## Terminal notification behavior

Phase663 surface policy remains authoritative.

During `FOREGROUND_RECONCILE`:

- `COMPLETED` -> do not post; existing tagged notification is cancelled;
- `FAILED` -> do not post; existing tagged notification is cancelled;
- `APPROVAL_REQUIRED` -> remains discoverable under the existing policy;
- `PLAN_UNAVAILABLE` -> remains discoverable and fail-closed;
- `NONE` -> cancelled.

No direct approval action is added to notifications.

## No duplicate initial foreground path

The previous one-time direct lifecycle/attention reconcile in `DisposableEffect` is removed.
Foreground visibility now has one lifecycle-driven entry path: the Activity lifecycle observer.

AndroidX lifecycle registration synchronizes the observer to the current Activity state, and later
resume transitions use the same `ON_RESUME` path. There is no separate periodic or immediate
polling path competing with it.

## Architecture locks

Phase669 adds:

- `proactive-foreground-visibility-is-on-resume-event-driven`
- `proactive-foreground-visibility-reuses-phase662-and-phase663-reconcile`
- `proactive-foreground-visibility-adds-no-polling-loop-service-or-scheduler`
- `foreground-lifecycle-reconcile-gates-read-model-refresh`
- `foreground-attention-failure-never-gates-lifecycle-refresh`
- `foreground-terminal-attention-is-not-reposted`
- `main-activity-owns-one-proactive-lifecycle-observer`

M5 exit criteria require lifecycle-event-driven foreground reconciliation, canonical lifecycle
gating, non-gating attention failure, terminal-notification cleanup, and no second observer/service
or polling architecture.

## Tests

Phase669 tests cover:

- lifecycle reconciliation failure skips attention reconcile and fails closed;
- attention failure does not gate a successful lifecycle result;
- successful resume returns the existing lifecycle and attention reports;
- foreground visibility coordinator exposes no polling/timer/interval/schedule API;
- both COMPLETED and FAILED terminal attention are suppressed during
  `FOREGROUND_RECONCILE`;
- Omega architecture locks and M5 exit criteria.

## Architecture audit

Base:

`main@60082e2b45c237c46c40a27ee24aa8a1004ec245`

Phase669 changes only foreground lifecycle wiring, a thin reconciliation adapter, tests,
architecture locks, and this document.

It does not modify:

- `app/src/amneNative/**`;
- `app/build.gradle.kts`;
- AMI2/AMNE2;
- Titan;
- ToolFabric or `DenyByDefaultAuthorityGate`;
- plan/receipt/lifecycle persistence formats;
- Phase655/656/657 scheduler or execution contracts;
- Phase663 notification copy/navigation privacy;
- Phase668 snapshot persistence semantics because no snapshot store exists.

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

Use one pull-request-triggered Android CI run.

Required merge gate:

- `verify` PASS;
- unit tests PASS;
- `Build canonical debug APK` PASS;
- `amper-core-arm64` SKIP because no native/build-impact file is changed.

Merge only when the verified CI head equals the PR head.

## Next M5 slice

Phase670 should audit and close the M5 proactive/always-on milestone as a whole: verify the canonical
chain from trigger source through process-death execution, lifecycle/attention/history/recovery,
foreground visibility, and architecture locks before deciding whether M5 exit criteria are complete
or one final bounded hardening slice is still required.
