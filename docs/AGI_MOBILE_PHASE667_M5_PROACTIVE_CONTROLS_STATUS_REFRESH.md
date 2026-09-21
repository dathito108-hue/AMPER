# Phase667 — M5 Proactive Controls / Lifecycle-History Status Refresh

Phase667 hardens the user-facing proactive task surface so it refreshes immediately after canonical
state changes while keeping all mutation authority on the existing governed surfaces.

Canonical ownership remains unchanged:

```
read-only proactive surface
  -> Phase662 lifecycle-derived plan state
  -> Phase665 canonical receipt projection
  -> Phase666 exact Recovery Console navigation
```

The surface is not a task controller.

## Control policy

`AmperAgentProactiveTaskControlPolicy` contains only:

- `REFRESH_STATUS`;
- `OPEN_GOVERNED_PLAN`;
- `OPEN_RECOVERY`.

There is intentionally no proactive-surface control for:

- approve;
- reject;
- cancel;
- advance;
- execute;
- reconcile.

Those mutations remain on the existing canonical plan/history/recovery surfaces.

A missing canonical plan exposes refresh only. A present plan exposes governed-plan navigation.
Exact recovery navigation is exposed only when the Phase665/666 canonical projection contains a
validated unresolved recovery target.

## Transient refresh revision

`AmperAgentProactiveSurfaceRefreshRevision` is an in-process UI invalidation counter only.

It is never:

- persisted;
- written into lifecycle provenance;
- written into plan state;
- written into receipts or acknowledgement state;
- used as scheduler identity;
- used as execution identity;
- used as permission or authority state.

Changing the revision only causes `ProactiveTaskLifecyclePanel` to re-read the existing Phase665
read-only projection.

## Canonical mutation refresh points

MainActivity advances the refresh revision only after existing canonical operations report success:

- plan-history cancellation completed and persisted;
- governed approval completed;
- governed rejection completed;
- Recovery Console reconciliation completed.

The callbacks do not perform an additional mutation. They only invalidate the read model after the
canonical mutation already succeeded.

Failure callbacks do not advance the revision and therefore do not present a synthetic success
transition.

## Foreground reconciliation

On foreground runtime setup, successful
`AndroidAgentProactiveTaskLifecycleController.reconcileTracked()` also invalidates the read model.

This makes lifecycle/history UI re-read durable changes that may have occurred while the process/UI
was absent, while still leaving Phase657 scheduling and Phase656 execution ownership unchanged.

Users retain an explicit "Refresh proactive tasks" control that performs the same read-only
invalidation without changing canonical state.

## Architecture locks

Phase667 adds:

- `proactive-surface-controls-are-refresh-or-navigation-only`
- `proactive-surface-exposes-no-approve-reject-cancel-advance-execute-control`
- `proactive-surface-refresh-revision-is-transient-ui-state-only`
- `proactive-surface-refresh-never-mutates-canonical-task-state`
- `successful-canonical-user-mutations-invalidate-proactive-read-model`
- `failed-canonical-user-mutations-do-not-claim-new-proactive-state`
- `foreground-lifecycle-reconciliation-invalidates-proactive-read-model`

M5 exit criteria now require navigation/refresh-only proactive controls, transient refresh identity,
success-only mutation invalidation, failure honesty, and foreground durable-state re-read.

## Tests

Phase667 tests cover:

- missing plan -> refresh only;
- canonical plan -> refresh + governed-plan navigation;
- exact unresolved recovery -> refresh + governed-plan + recovery navigation;
- recovery control cannot exist without canonical plan;
- proactive control enum contains no mutation control names;
- transient refresh revision increments and wraps without persistence;
- Omega architecture locks and M5 exit criteria.

## Architecture audit

Base:

`main@07682693aceddddca134acc46ca1343240b4f7cb`

Phase667 changes only read-only proactive controls, transient Compose refresh state, tests,
architecture locks, and this document.

It does not modify:

- `app/src/amneNative/**`;
- `app/build.gradle.kts`;
- AMI2/AMNE2;
- Titan;
- ToolFabric or authority policy;
- plan/receipt/lifecycle persistence formats;
- approval/rejection/cancellation/recovery implementation;
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

Use one pull-request-triggered Android CI run.

Required merge gate:

- `verify` PASS;
- unit tests PASS;
- `Build canonical debug APK` PASS;
- `amper-core-arm64` SKIP because no native/build-impact file is changed.

Merge only when the verified CI head equals the PR head.

## Next M5 slice

Phase668 should harden proactive lifecycle/history snapshot coherence under concurrent EVENT_WAKE and
foreground reads, using canonical re-read/validation only and without introducing locks that create a
second execution scheduler or task state store.
