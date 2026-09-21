# Phase662 — Proactive Task Lifecycle Surface

Phase662 makes durable proactive work inspectable and governable without creating a second task
database, scheduler, planner, approval system, or execution path.

It builds directly on the canonical Phase658–661 chain:

```
bounded trigger source
  -> encrypted accepted-observation FIFO
  -> deterministic canonical persistent plan
  -> immutable proactive lifecycle provenance
  -> verified Phase655 EVENT_WAKE handoff
  -> Phase657 Android scheduler
  -> Phase656 one-step canonical consumer
```

## Immutable provenance, canonical mutable state

`AmperAgentProactiveTaskLifecycleBinding` stores only immutable provenance:

- source id;
- exact source configuration SHA-256;
- accepted observation identity SHA-256;
- deterministic proactive task id;
- deterministic canonical plan id;
- trigger provenance;
- stable bind timestamp derived from accepted observation / durable plan creation time.

The lifecycle ledger deliberately stores **no mutable task state**.

READY, CHECKPOINTED, WAITING_APPROVAL, COMPLETED, and FAILED are always derived from the current
canonical `SovereignPlan`. Tool outcomes, approval state, receipts, recovery evidence, and plan
execution remain owned by the existing persistent plan system.

This keeps Phase662 from becoming a parallel task database.

## Same sovereign encrypted memory

`MemoryBackedAmperAgentProactiveTaskLifecycleLedger` is backed by the same `MemoryOs` instance used
by the canonical runtime. Android production therefore inherits the existing encrypted,
crash-recoverable sovereign memory journal.

The ledger is a single bounded provenance index with capacity 64.

It is:

- idempotent for an exact repeated binding;
- fail-closed if task/plan/observation identity is already bound to different provenance;
- fail-closed if the ledger record kind or codec is corrupted;
- bounded;
- compacted only for plans proven terminal by the canonical plan store.

Missing plans are never silently removed during ordinary inspection or reconciliation.

## Crash-safe dispatch ordering

Phase662 strengthens the Phase661 dispatch transaction ordering to:

```
1. deterministic canonical plan binding
2. durable immutable lifecycle provenance
3. Phase657 EVENT_WAKE install/cancel
4. Phase660 accepted-observation FIFO acknowledgement
```

`AndroidAgentPendingTriggerDurabilitySequence` locks this ordering in code and tests.

Consequences:

- provenance write failure cannot call the scheduler or FIFO ACK;
- scheduler failure cannot acknowledge the FIFO;
- disposition drift between the verified handoff and Phase657 result cannot acknowledge the FIFO;
- crash after provenance but before scheduling leaves both durable provenance and the accepted FIFO
  available for recovery;
- crash after scheduling but before FIFO ACK is safe because plan, provenance binding, and EVENT_WAKE
  identity are deterministic/idempotent.

Foreground lifecycle reconciliation can therefore rediscover the exact canonical plan even when a
scheduler attempt failed after provenance became durable.

## Lifecycle inspection

`AmperAgentProactiveTaskLifecycleCoordinator` provides a read-oriented lifecycle view.

It:

- looks up immutable provenance;
- loads the canonical persistent plan;
- derives task state from that plan only;
- exposes the waiting approval step index when present;
- can reopen the exact canonical plan;
- can reconstruct the current verified Phase655 handoff without advancing any step;
- can compact old terminal provenance within the bounded ledger.

If a tracked plan is missing, the entry remains visible as unavailable and execution/reconciliation
fails closed.

## Governed approval remains canonical

Phase662 does not approve or reject tool actions.

The existing `PersistentSovereignPlanCoordinator` remains the only governed plan approval/rejection
surface. After that existing surface has durably resolved a `REQUIRES_CONFIRMATION` step,
`AmperAgentProactiveTaskLifecycleCoordinator.resumeAfterGovernedDecision` only:

1. reloads the same canonical plan;
2. reconstructs the narrow proactive admission from immutable provenance and plan capabilities;
3. verifies that governed approval is already resolved;
4. creates the next Phase655 handoff;
5. lets the Android bridge pass that handoff to Phase657.

No tool execution occurs in the lifecycle coordinator.

## Android lifecycle bridge

`AndroidAgentProactiveTaskLifecycleController` owns no new JobService or scheduler.

It reuses `AndroidAgentEventWakeScheduler` for:

- foreground reconciliation after app/process restart;
- re-arming a READY/CHECKPOINTED proactive plan;
- cancelling a stale wake for WAITING_APPROVAL or terminal plans;
- resuming the same canonical EVENT_WAKE path after an explicit governed decision.

Foreground bootstrap reconciles tracked proactive plans after trigger-source reconciliation.

## User-visible lifecycle surface

`ProactiveTaskLifecyclePanel` is read-only.

It shows:

- source;
- trigger timestamp / payload digest prefix;
- canonical plan id;
- derived task state;
- progress;
- exact waiting approval step number;
- goal;
- missing-plan fail-closed state.

Opening a proactive task opens the existing governed plan console.

A proactive lifecycle-tracked plan disables the console's manual **Advance one step** action. This
prevents a foreground UI advance from racing the Phase656/657 EVENT_WAKE owner.

The existing exact approve/reject controls remain available. After they complete, the lifecycle
bridge re-arms or cancels the canonical EVENT_WAKE according to the newly persisted plan state.

Cancellation and recovery mutations also reconcile the same Phase657 handoff instead of creating a
new execution path.

## Architecture locks

Phase662 adds:

- `proactive-lifecycle-binding-persists-before-event-wake-and-trigger-fifo-ack`
- `proactive-lifecycle-ledger-stores-provenance-not-task-state`
- `proactive-task-state-is-derived-only-from-canonical-persistent-plan`
- `proactive-governed-decisions-reuse-existing-plan-approval-and-event-wake`
- `proactive-event-wake-plans-disable-manual-ui-advance`
- `proactive-lifecycle-ledger-is-bounded-and-terminal-compacted`

The M5 exit criteria now require durable trigger-to-task-to-plan provenance, foreground lifecycle
reconciliation, canonical governed approval reuse, UI ownership separation, and bounded provenance
retention.

## Tests

Phase662 covers:

- lifecycle provenance survives journal reopen;
- exact replay is idempotent;
- provenance identity collision fails closed;
- dispatch retry uses a stable durable plan timestamp;
- task state changes only when the canonical plan changes;
- governed approval resolution produces the next verified runnable handoff;
- rejection of the final step produces TERMINAL_NOOP;
- terminal provenance compaction is bounded;
- missing canonical plans remain visible and fail closed;
- canonical runtime exposes the lifecycle ledger from the same sovereign graph;
- provenance → Phase657 → FIFO ACK ordering;
- provenance failure prevents scheduling and ACK;
- scheduler failure/disposition drift prevents ACK;
- verified non-runnable handoffs cancel through Phase657 before ACK;
- Omega architecture locks and M5 exit criteria.

## Next M5 slice

The next slice should harden proactive lifecycle delivery/attention semantics without changing this
execution chain: user notification/attention routing, bounded status surfacing, and approval
discoverability should consume the same lifecycle view and canonical plan/authority paths rather
than adding another autonomous executor.
