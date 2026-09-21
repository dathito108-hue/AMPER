# Phase661 — Durable Accepted-Observation Dispatch Binding

Phase661 closes the gap between the Phase660 encrypted accepted-observation FIFO and the existing
canonical proactive Agent execution path.

It does **not** add a monitor planner, a trigger-specific ToolFabric, a second task database, a second
model path, or a polling loop.

## Canonical dispatch chain

The only accepted chain is:

```
Phase658 bounded source
  -> Phase659/660 encrypted source record + pending FIFO
  -> Phase661 deterministic persistent-plan binding
  -> Phase655 verified EVENT_WAKE handoff
  -> Phase657 governed Android EVENT_WAKE scheduler
  -> Phase656 canonical one-step consumer
```

The oldest FIFO entry is always processed first.

## Deterministic persistent-plan identity

A pending observation already carries a deterministic lowercase SHA-256 identity derived from its
exact source revision and observation.

Phase661 derives its canonical plan id as:

```
agent-trigger-plan:<observationIdentitySha256>
```

`SovereignPlanCoordinator` and `PersistentSovereignPlanCoordinator` now support bound plan
creation. The bound id is present while the canonical planning hooks run; it is not copied onto a
plan after creation.

If a plan with that id already exists, persistent creation reuses it only when conversation and goal
still match. Any incompatible collision fails closed.

Therefore:

- crash after plan persistence but before Android scheduling does not create another plan;
- retry after approval state reuses the same approval-blocked plan;
- retry after terminal state reuses the same terminal plan;
- retry never falls back to a source-specific planner.

## Verified binding before acknowledgement

`AmperAgentPendingTriggerDispatchCoordinator`:

1. reloads the registered source and oldest pending observation;
2. requires the source to still be enabled;
3. requalifies the exact persisted source + observation through the Phase658 policy;
4. verifies the reconstructed observation identity equals the persisted FIFO identity;
5. registers the exact canonical admission in the process admission registry;
6. starts/reloads the deterministic plan through the existing Phase654 proactive task coordinator;
7. creates the Phase655 EVENT_WAKE handoff;
8. verifies and decodes that handoff before returning a dispatch binding.

This coordinator never acknowledges the FIFO and never owns Android scheduling.

## Android one-shot pending dispatcher

`AndroidAgentPendingTriggerDispatchScheduler` uses a separate `0x44xxxxxx` JobScheduler namespace.

Properties:

- one stable job id per source;
- persisted across reboot;
- battery-not-low and storage-not-low constraints;
- thermal/memory-aware latency reused from the Phase657 resource policy;
- bounded explicit retry budget of four attempts;
- retry delay grows from the Phase657 resource-derived base and remains bounded;
- no periodic polling;
- no planner, ToolFabric, AuthorityGate, model, or execution port.

`AgentPendingTriggerDispatchJobService` resource-checks before acquiring the canonical runtime graph.
It then binds only the oldest pending observation.

For a runnable handoff, it must successfully install that exact verified handoff through
`AndroidAgentEventWakeScheduler` before acknowledgement.

For approval-blocked or terminal handoffs, no EVENT_WAKE job is installed. The durable canonical plan
plus verified non-runnable handoff is the completed binding, so the FIFO entry may be acknowledged.

Only after those conditions hold does the service call the Phase660 FIFO acknowledgement.

If more FIFO entries remain, it finishes the current JobService wake before requesting the same
stable one-shot job again.

## Crash/retry windows

### Crash after FIFO acceptance, before plan creation

The FIFO remains authoritative. Foreground reconciliation, source re-enable, or the source adapter
requests pending dispatch again.

### Crash after plan creation, before EVENT_WAKE schedule

The FIFO remains pending. Retry derives the same plan id and reuses the same durable plan.

### Crash after EVENT_WAKE schedule, before FIFO acknowledgement

The FIFO remains pending. Retry reuses the same plan and produces the same Phase657 stable wake
identity for the same durable checkpoint. Reinstalling the job replaces/dedupes rather than creating
another execution identity.

### Scheduler or transient binding failure

The FIFO is not acknowledged. The one-shot dispatcher retries only within its bounded explicit retry
budget. Durable pending state remains available for later reconciliation.

### Source disabled while pending

Dispatch is paused and the one-shot job is cancelled by reconciliation. The FIFO and source
provenance remain intact. Re-enabling the source requests dispatch again.

### Source removal while pending

Phase660 continues to reject removal. After FIFO acknowledgement drains the source, removal cancels
both the periodic source job and the pending-dispatch job.

## Source adapter integration

`AndroidAgentProactiveTriggerSourceController` now:

- requests pending dispatch after a durable APP_LOCAL_EVENT acceptance;
- requests dispatch for every enabled source with pending FIFO work during foreground reconciliation;
- cancels pending dispatch while a source is disabled or has no pending work;
- resumes pending dispatch after re-enable;
- cancels both source scheduling and pending dispatch after successful removal.

`AgentTriggerSourceJobService` remains only a scheduled source clock. After observation acceptance it
may request the Phase661 one-shot dispatcher when FIFO work exists, but it never creates a plan
itself.

## Architecture locks

Phase661 adds:

- `pending-trigger-dispatch-uses-deterministic-canonical-plan-identity`
- `pending-trigger-fifo-acks-only-after-durable-verified-dispatch-binding`
- `pending-trigger-dispatch-reuses-governed-event-wake-scheduler`
- `pending-trigger-dispatch-adds-no-planner-tool-or-model-path`

The M5 exit criteria are extended to require deterministic crash/retry binding, ACK-after-verified
handoff/schedule, bounded one-shot dispatch, and foreground/re-enable/source-event recovery.

## Tests

Phase661 covers:

- repeated bind -> one deterministic durable plan;
- retry after governed approval -> same plan + non-runnable approval handoff;
- retry after terminal completion -> same plan + TERMINAL_NOOP;
- source-revision/observation identity drift -> fail closed before plan creation;
- disabled source -> no binding;
- pending-dispatch job-id namespace separation;
- APP_LOCAL_EVENT acceptance -> dispatch request;
- foreground reconciliation -> recovery request for persisted FIFO work;
- disable/re-enable -> pause then resume;
- pending FIFO -> removal blocked;
- drained FIFO -> both Android scheduling identities cancelled on removal.

## Next M5 slice

Phase662 should build the user-visible governed lifecycle around proactive work without changing this
dispatch chain: inspection/status, approval surfacing/resume, and bounded trigger-task diagnostics
must reuse the same persistent plan, trigger provenance, authority gate, and EVENT_WAKE execution
path.
