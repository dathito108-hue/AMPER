# Phase669 — M5 Background Transition / Foreground Refresh

Phase669 makes a currently visible proactive lifecycle/history surface refresh when the existing
background EVENT_WAKE path changes canonical proactive state.

It reuses the Phase663 background attention transition. It does not add polling, a second lifecycle
observer, a broadcast receiver, a task database, a scheduler, or execution authority.

## Existing transition reused

Phase657's EVENT_WAKE host already calls the Phase663 attention adapter after a non-retry execution
result:

```
Phase656 EVENT_WAKE result
  -> Phase663 syncPlan(planId, BACKGROUND_TRANSITION)
  -> notification discoverability
```

Phase669 attaches a best-effort process-local UI invalidation to that same
`BACKGROUND_TRANSITION` call:

```
Phase663 BACKGROUND_TRANSITION
  -> notification surface
  -> process-local Phase667 read-model invalidation
```

There is no additional polling loop and no independent observer of plan state.

## Process-local invalidation registry

`AndroidAgentProactiveSurfaceInvalidationRegistry` stores at most one in-process listener.

It persists nothing and owns no:

- plan state;
- lifecycle state;
- task state;
- receipt/claim state;
- scheduler state;
- authority decision;
- execution port.

A cold JobService process with no foreground MainActivity listener returns a no-op.

Listener registration follows identity semantics:

- a newer activity may replace an older listener;
- disposing an older activity unregisters only if its listener is still the active identity;
- an older dispose therefore cannot clear a newer activity's listener.

## Mode policy

`AndroidAgentProactiveSurfaceInvalidationPolicy` allows invalidation only for:

`BACKGROUND_TRANSITION`

It rejects:

- `FOREGROUND_RECONCILE`;
- `USER_INTERACTION`.

Those modes already use Phase667 explicit refresh points. This avoids duplicate refresh work.

## Non-gating behavior

The background invalidation is invoked from `syncPlan` in a `finally` block so a missing tracked
plan or notification-surface failure cannot prevent the foreground from being told to reread.

The registry itself catches listener exceptions and returns failure as a value. It never throws back
into the attention adapter.

The existing Phase657 caller already treats attention as best effort, so foreground invalidation can
never change:

- the Phase656 execution result;
- checkpoint chaining;
- Phase657 scheduling;
- FIFO acknowledgement;
- plan state.

## MainActivity integration

MainActivity registers one stable listener inside its existing process-lifecycle
`DisposableEffect`.

The listener:

1. posts onto the Activity UI thread;
2. verifies the Activity is not finishing/destroyed;
3. advances only the Phase667 transient `proactiveSurfaceRefreshRevision`;
4. causes the read-only Phase668 coherent history projection to be reread.

`rememberUpdatedState` keeps the stable listener identity while pointing it at the current refresh
callback.

On dispose, MainActivity identity-unregisters the listener.

## Architecture locks

Phase669 adds:

- `background-proactive-transition-invalidates-foreground-read-model`
- `proactive-surface-invalidation-reuses-attention-transition-not-polling`
- `proactive-surface-invalidation-is-process-local-and-unpersisted`
- `proactive-surface-invalidation-listener-is-identity-unregistered`
- `proactive-surface-invalidation-failure-never-gates-event-wake`
- `foreground-and-user-attention-sync-do-not-duplicate-background-invalidation`

M5 exit criteria require reuse of the existing background attention transition, process-local/no-op
cold behavior, identity-safe activity ownership, non-gating callback failure, and no duplicate
foreground/user invalidation.

## Tests

Phase669 tests cover:

- only `BACKGROUND_TRANSITION` requests foreground invalidation;
- foreground/user modes do not request it;
- listener invocation is process-local and succeeds when registered;
- identity-mismatched unregister does not clear the active listener;
- matching unregister clears it;
- listener exceptions are swallowed and reported as best-effort failure.

## Architecture audit

Base:

`main@60082e2b45c237c46c40a27ee24aa8a1004ec245`

Phase669 changes only the Phase663 attention adapter, MainActivity process-local UI bridge, tests,
architecture locks, and this document.

It does not modify:

- `app/src/amneNative/**`;
- `app/build.gradle.kts`;
- AMI2/AMNE2;
- Titan;
- ToolFabric or authority policy;
- lifecycle/plan/receipt persistence formats;
- Phase655/656 execution;
- Phase657 scheduling.

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

Phase670 should harden burst/coalescing behavior for multiple background proactive transitions while a
foreground surface is live, using transient invalidation state only and without polling or persistent
observer queues.
