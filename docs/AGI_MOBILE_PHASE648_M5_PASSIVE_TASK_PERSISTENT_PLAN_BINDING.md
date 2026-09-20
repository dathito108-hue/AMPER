# Phase648 — M5 Passive Agent Task / Persistent Plan Binding

Phase648 connects explicit USER_REQUEST Agent Core tasks to the already-governed persistent
sovereign-plan path. It does not introduce a second planner, executor, approval surface, or task
database.

## Canonical binding

`AmperAgentPassiveTaskCoordinator` creates and advances work only through an
`AmperAgentPersistentPlanPort`.

The production adapter, `PersistentSovereignAgentPlanPort`, delegates directly to:

- `PersistentSovereignPlanCoordinator.create`;
- `PersistentSovereignPlanCoordinator.advance`;
- the existing `SovereignPlanStore`.

The durable plan id is therefore the Agent Core checkpoint anchor.

## One bounded step per advance

Each Agent Core advance call processes at most one canonical plan step.

The coordinator maps existing plan outcomes into Agent Core state:

- normal remaining work -> READY;
- side effect requiring confirmation -> WAITING_APPROVAL;
- cognitive context drift -> CHECKPOINTED;
- all steps executed -> COMPLETED;
- terminal plan with non-executed outcomes -> FAILED.

The Agent Core adapter exposes no `approve`, `approveBound`, or direct ToolFabric invocation.

## Governed approval remains external

When a plan reaches WAITING_APPROVAL, Agent Core stops.

`resumeAfterGovernedApproval` only re-reads the persistent plan. It refuses to resume while the live
plan step is still `REQUIRES_CONFIRMATION`. Approval must therefore be resolved through the
existing sovereign governed approval surface first.

This preserves:

- live ToolDescriptor rebinding;
- AuthorityGate;
- provider/side-effect binding;
- audit log;
- execution receipts;
- side-effect claim reconciliation.

## Checkpoint semantics

For non-UI-bound tasks, Phase647 requires checkpointing. Phase648 satisfies that requirement by
binding the task to an already-durable SovereignPlan id rather than creating parallel persistence.

Diagnostics expose only:

- task id;
- plan id;
- background mode;
- task state;
- completed/total step counts;
- whether checkpointing is required.

## Architecture lock

Phase648 adds:

`passive-agent-tasks-reuse-persistent-sovereign-plans`

## Next M5 slice

Phase649 should add the execution-continuation checkpoint envelope that lets
FOREGROUND_CONTINUATION and PERSISTED_JOB tasks resume from the exact durable task/plan state after
UI exit or process recreation without replaying an already-completed or approval-pending step.
