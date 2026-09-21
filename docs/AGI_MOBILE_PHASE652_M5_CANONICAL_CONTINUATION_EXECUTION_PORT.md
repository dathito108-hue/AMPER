# Phase652 — M5 Canonical Continuation Execution Port

Phase652 connects the dedicated Android continuation hosts to the existing canonical persistent
sovereign-plan execution path while keeping every OS wake one-step bounded.

## Canonical warm-process wiring

MainActivity now constructs one Agent continuation graph from the existing planner/store:

- PersistentSovereignAgentPlanPort -> existing PersistentSovereignPlanCoordinator + runtime.plans;
- AmperAgentPassiveTaskCoordinator;
- AmperAgentExecutionContinuationCoordinator;
- AmperAgentCanonicalContinuationExecutionPort.

The final execution port is registered in AndroidAgentContinuationProcessRegistry while the full
AMPER process is alive and is unregistered with the same Compose lifecycle.

No second planner, ToolFabric, AuthorityGate, plan store, or execution engine is created.

## One verified wake -> one persistent step

The execution port receives only a Phase651 host call that has already passed Phase650 handoff
verification.

For each call it:

1. restores the exact Phase649 continuation envelope;
2. loads the exact persistent sovereign plan;
3. calls AmperAgentPassiveTaskCoordinator.advance exactly once;
4. maps the new durable state;
5. if work remains, creates a fresh Phase649 envelope and Phase650 handoff;
6. returns that exact next handoff to the Android host.

The foreground service or persisted JobService then schedules the fresh handoff. It never reuses the
old plan digest.

WAITING_APPROVAL returns no next handoff. Terminal state removes the process-local admission and
returns TERMINAL_NOOP.

## Narrow admission reconstruction

The process-local admission registry is an optimization, not authority.

If the RAM admission entry is unavailable, Phase652 may reconstruct one from the exact durable plan
already bound by the continuation envelope:

- objective = persisted plan goal;
- allowedCapabilities = exactly the capabilities present in persisted plan steps;
- task id = continuation task id;
- mode = continuation background mode;
- origin = USER_REQUEST.

This reconstruction can only narrow authority to the already-persisted plan. The reconstructed
admission must reproduce the exact background mode, then the normal Phase649 restore still verifies
the plan-state SHA-256 before any advance occurs.

## Android checkpoint chaining

Phase651 hosts now understand a CHECKPOINTED execution result carrying exactly one next handoff.

- foreground host re-handoffs the fresh checkpoint while remaining visible;
- persisted JobService finishes the old job, then schedules the new handoff;
- RETRY_LATER remains an OS reschedule request only when no canonical execution port is available;
- WAITING_APPROVAL and terminal results never chain.

## Architecture lock

Phase652 adds:

`android-agent-wakes-advance-one-canonical-persistent-step`

M5 also records that warm Android continuation chains one-step checkpoints through the canonical
persistent planner.

## Next M5 slice

Phase653 should make persisted-job continuation survive a fully cold process/reboot by extracting the
existing MainActivity sovereign runtime/tool/planner construction into one reusable Android
bootstrap. The JobService must build that same canonical graph, register the same execution port,
execute at most one verified step, then dispose it. A reduced background-only tool executor is not
allowed because it would bypass the live ToolFabric/AuthorityGate contract.
