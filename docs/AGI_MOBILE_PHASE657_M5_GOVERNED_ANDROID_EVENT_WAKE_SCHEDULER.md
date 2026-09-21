# Phase657 — Governed Android EVENT_WAKE Scheduler

Phase657 connects the verified Phase655/656 proactive wake path to Android JobScheduler without
creating a second planner, executor, ToolFabric, or monitor-owned inference runtime.

## Scheduling boundary

`AndroidAgentEventWakeScheduler` accepts only a Phase655 handoff that passes
`AndroidAgentEventWakeSchedulingAdmission`.

Scheduling admission:

1. verifies and decodes the complete handoff;
2. reconstructs its canonical disposition;
3. rejects tampered metadata;
4. schedules only READY_FOR_EXPLICIT_ADVANCE;
5. cancels the stable job identity for WAITING_APPROVAL or terminal handoffs.

The scheduler never advances a plan or invokes a tool.

## Stable dedupe

`AndroidAgentEventWakeJobIdentity` derives one namespaced JobScheduler id from the Phase655 dedupe
key.

The dedupe key is stable across fresh checkpoints of the same task/plan/trigger observation. A newly
verified checkpoint therefore replaces an older pending checkpoint instead of accumulating duplicate
jobs.

A different trigger payload digest produces a different job identity.

## Resource governance

Every proactive job requires Android battery-not-low and storage-not-low.

The scheduler derives a bounded minimum latency from the live canonical ResourceBudget:

- normal resources: 15 second minimum cadence;
- low AMPER memory budget: 2 minute backoff;
- severe thermal pressure: 5 minute backoff;
- critical thermal pressure: 15 minute backoff.

At JobService wake time the canonical Android resource governor is checked again before the
EVENT_WAKE consumer can acquire the runtime. Low-memory or severe/critical thermal pressure finishes
that OS wake without Agent execution and reinstalls the same still-current verified checkpoint under
the newly derived backoff. This closes the gap between scheduling-time and execution-time resource
conditions.

These constraints affect cadence only. They cannot change capabilities, authority, plan state, or
approval state.

All jobs are persisted across reboot. The app already holds RECEIVE_BOOT_COMPLETED.

## Qualified trigger adapter

`AndroidAgentProactiveEventWakeAdapter` converts an already-admitted proactive task checkpoint into
a Phase655 verified handoff through the canonical
`AmperAgentProactiveEventWakeCoordinator`, then passes only that handoff to JobScheduler.

The adapter owns no planner, ToolFabric, AuthorityGate, or execution port.

## Job execution

`AgentEventWakeJobService` is exported=false and protected by BIND_JOB_SERVICE.

For one OS wake it:

1. reconstructs the handoff from PersistableBundle;
2. calls `AndroidAgentEventWakeConsumer`;
3. that consumer verifies before acquiring the shared canonical runtime;
4. Phase656 advances at most one persistent plan step.

When more work remains, the service finishes the old job first and then schedules only the fresh
Phase656 handoff. It never asks Android to replay the stale old checkpoint after a successful step.

WAITING_APPROVAL and terminal results finish without another job.

If the consumer explicitly returns RETRY_LATER before any advance is available, the service
finishes the old OS wake and reinstalls the same still-current verified checkpoint through the same
bounded resource policy. Tampered/stale failures fail closed with no retry loop.

## Multi-task isolation

The JobService uses one resource-conservative worker thread, but tracks active work by stable job id.
Stopping one proactive job therefore cannot cancel a different task's queued/running Future.

All actual plan advancement remains serialized by the Phase655 canonical wake execution gate.

## Tests

Phase657 adds coverage for:

- fresh checkpoints retain stable JobScheduler identity;
- different trigger payloads get different job identity;
- approval-blocked handoffs are verified but not schedulable;
- tampered disposition fails scheduling admission;
- normal resource conditions use the bounded 15 second cadence with battery/storage guards;
- low-memory, severe-thermal, and critical-thermal conditions back off cadence;
- live resource policy refuses execution under low-memory or severe/critical thermal pressure.

## Architecture lock

Phase657 adds:

`proactive-event-wake-job-scheduling-is-verified-deduped-and-resource-governed`

`event-wake-jobs-chain-only-fresh-checkpoints`

## Next M5 slice

Phase658 should add the first bounded proactive trigger-source contract above this scheduler.

A trigger source must produce explicit `AmperAgentTrigger` provenance and an admitted objective,
but it must not directly execute tools or plans. Candidate sources should be finite, user-configured,
resource-governed observations such as a scheduled time/window or explicit app-local condition.

Phase658 should not introduce continuous high-frequency polling.
